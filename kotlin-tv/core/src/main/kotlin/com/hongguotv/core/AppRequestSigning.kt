// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

enum class SigningBackend { KOTLIN_V5, NATIVE_SDK }
class AppSigningException(message: String) : IOException(message)

class AppSigningContext(val device: Map<String, String>, val accountGeneration: String? = null) {
    val authenticated get() = accountGeneration != null
    val deviceId get() = device.getValue("device_id")
    val installId get() = device.getValue("iid")
    override fun toString() = "AppSigningContext([redacted])"
}

class AppSigningInput(val url: String, val method: String, val headers: Map<String, List<String>>,
                      val body: ByteArray, val context: AppSigningContext) {
    override fun toString() = "AppSigningInput([redacted])"
}

class AppSigningResult(val url: String, val headers: Map<String, String>) {
    override fun toString() = "AppSigningResult([redacted])"
}

interface AppRequestSigner {
    val backend: SigningBackend
    fun sign(input: AppSigningInput): AppSigningResult
    /** SDK policy updates belong to the same API transport; never feed CDN responses here. */
    fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) { }
}

/** One process-scoped Kotlin state, also used by login/account APIs. */
class KotlinAppRequestSigner : AppRequestSigner {
    override val backend = SigningBackend.KOTLIN_V5
    private val signer = Signer(mobileV5 = true)
    @Synchronized override fun sign(input: AppSigningInput): AppSigningResult {
        val url = input.url.toHttpUrl()
        val values = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
        val now = values["_rticket"]?.toLongOrNull() ?: System.currentTimeMillis()
        val result = signer.signPlayback(url.newBuilder().query(null).build().toString(), values,
            input.body, now, input.context.device)
        return AppSigningResult(result.url, result.headers.filterKeys { it.lowercase() in securityHeaders })
    }
}

private val securityHeaders = setOf("x-khronos", "x-helios", "x-medusa")
private val obsoleteSecurityHeaders = securityHeaders + setOf("x-gorgon", "x-argus", "x-ladon")

internal fun isAppSigningUrl(url: HttpUrl): Boolean = isAccountModelUrl(url) ||
    url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
    (url.host == "security.snssdk.com" && url.encodedPath.startsWith("/passport/") ||
        url.host == "reading.snssdk.com" && url.encodedPath.startsWith("/reading/"))

/** Prepare the actual body and scoped account headers before invoking either signing engine. */
internal fun signAppRequest(request: Request, context: AppSigningContext, signer: AppRequestSigner): Request {
    RequestScope.ensureActive()
    if (!isAppSigningUrl(request.url)) throw AppSigningException("签名请求不在允许的接口范围内")
    val builder = request.newBuilder()
    obsoleteSecurityHeaders.forEach(builder::removeHeader)
    val body = request.body
    if (body != null && (body.isDuplex() || body.isOneShot() || body.contentLength() > 2 * 1024 * 1024))
        throw AppSigningException("签名请求正文无法安全重放")
    val bytes = body?.let { Buffer().apply { it.writeTo(this) }.readByteArray() } ?: ByteArray(0)
    if (bytes.size > 2 * 1024 * 1024) throw AppSigningException("签名请求正文过大")
    if (body != null) {
        body.contentType()?.let { builder.header("Content-Type", it.toString()) }
        builder.header("x-ss-stub", digest("MD5", bytes).hex().uppercase())
    } else builder.removeHeader("x-ss-stub")
    builder.header("x-ss-req-ticket", request.url.queryParameter("_rticket") ?: System.currentTimeMillis().toString())
        .header("sdk-version", "2").header("passport-sdk-version", "5051452").header("x-xs-from-web", "0")
    val prepared = builder.build()
    val result = try {
        signer.sign(AppSigningInput(prepared.url.toString(), prepared.method, prepared.headers.toMultimap(), bytes, context))
    } catch (error: AppSigningException) { throw error }
    catch (_: Exception) { throw AppSigningException("请求签名失败，请稍后重试") }
    RequestScope.ensureActive()
    val signedUrl = try { result.url.toHttpUrl() } catch (_: Exception) {
        throw AppSigningException("签名结果地址无效")
    }
    // Kotlin can adjust only the ticket to select its supported digest branch.
    fun withoutTicket(url: HttpUrl) = url.newBuilder().removeAllQueryParameters("_rticket").build()
    if (withoutTicket(signedUrl) != withoutTicket(prepared.url) || signedUrl.queryParameterValues("_rticket").size != 1)
        throw AppSigningException("签名结果改变了请求上下文")
    val signed = result.headers.mapKeys { it.key.lowercase() }
    if (signed.keys != securityHeaders || signed.values.any { it.isBlank() || it.length > 16_384 || it.any { c -> c.code !in 0x20..0x7e } })
        throw AppSigningException("签名结果不完整")
    builder.url(signedUrl).header("x-ss-req-ticket", signedUrl.queryParameter("_rticket")!!)
    signed.forEach { (name, value) -> builder.header(name, value) }
    return builder.build()
}

internal fun observeAppResponse(response: Response, signer: AppRequestSigner) {
    RequestScope.ensureActive()
    if (!isAppSigningUrl(response.request.url)) throw AppSigningException("签名响应不在允许的接口范围内")
    try {
        signer.observeResponse(response.request.url.toString(), response.code, response.headers.toMultimap())
    } catch (error: AppSigningException) { throw error }
    catch (_: Exception) { throw AppSigningException("签名状态更新失败，请稍后重试") }
    RequestScope.ensureActive()
}

/** Installed immediately after account injection; never signs a media/CDN/VOD request. */
internal class AppSigningInterceptor(
    private val signer: AppRequestSigner,
    private val context: () -> AppSigningContext,
    private val allowed: (HttpUrl) -> Boolean = ::isAccountModelUrl,
    private val observed: (Boolean, Boolean) -> Unit = { _, _ -> },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!allowed(request.url)) return chain.proceed(request)
        val signed = signAppRequest(request, context(), signer)
        observed(!signed.header("X-Tt-Token").isNullOrBlank(), !signed.header("Cookie").isNullOrBlank())
        val response = chain.proceed(signed)
        try {
            observeAppResponse(response, signer)
            return response
        } catch (error: Exception) { response.close(); throw error }
    }
}

internal fun appUnsignedUrl(base: String, device: Map<String, String>, params: Map<String, String> = emptyMap()): String =
    base.toHttpUrl().newBuilder().apply {
        (device.filterKeys { it !in setOf("user_agent", "apk_first_install_time", "clientudid", "openudid", "device_token", "device_manufacturer") } + params +
            ("_rticket" to System.currentTimeMillis().toString())).forEach { (k, v) -> addQueryParameter(k, v) }
    }.build().toString()

internal fun AccountCredentials.signingGeneration(): String = signingSessionId.ifBlank {
    // Compatibility for already encrypted App logins. Stable across Token refresh; not logged.
    "restored-" + digest("SHA-256", (deviceId + "\u0000" + cookie).toByteArray()).hex()
}
