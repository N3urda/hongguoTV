// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** SDK report transport (j3/f5), isolated from all account cookies and app interceptors. */
internal class NativeSdkTransport(private val decode: (String, IntArray) -> String) {
    private val attempts = java.util.concurrent.atomic.AtomicInteger()
    private val completed = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastStatus = 0
    internal fun diagnosticCounts() = "attempts=${attempts.get()},completed=${completed.get()},status=$lastStatus"
    private val http = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private fun d(key: String, vararg bytes: Int) = decode(key, bytes)

    fun post(url: String, arguments: Array<*>): Array<Any?> {
        attempts.incrementAndGet()
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty())
        require(listOf("bytedance.com", "zijieapi.com", "snssdk.com").any {
            parsed.host == it || parsed.host.endsWith(".$it")
        })
        val payload = arguments[0] as ByteArray
        require(payload.size <= 2 * 1024 * 1024)
        val builder = Request.Builder().url(parsed)
            .header(d("bb6f03",80,111,74,25,6,33), d("f42ee8",100,51,82,2,83,32,107,28,103,104) + arguments[1])
            .header(d("b61158",82,55,65,64,26,59), d("be8502",57,40,1))
            .header(d("eb4292",65,115,66,84,75,4,97,70,107,118), d("216986",1,42,81,72,35,32,63,19,98,36,14,0,118,105,44))
            .header(d("65c531",4,56,30,85,9,40,33,89,6,124,55,50), d("d77c04",116,37,84,27,6,32,102,2,111,60,123,122,75,20,27,38,115,91,117,39,103,48,69,26))
        (arguments.getOrNull(3) as? Array<*>)?.let { pairs ->
            require(pairs.size % 2 == 0)
            pairs.toList().chunked(2).forEach { builder.header(it[0] as String, it[1] as String) }
        }
        (arguments[2] as? String)?.takeIf { it.isNotBlank() }?.let {
            builder.header(d("2e05cf",59,42,87,85,17,99,52,85,116,96,48,115,14,85,93,118), it)
        }
        val contentType = d("cd687e",115,118,85,64,1,113,97,81,110,103,124,41,74,79,28,119,116,8,116,124,96,99,68,65).toMediaType()
        return try {
            http.newCall(builder.post(payload.toRequestBody(contentType)).build()).execute().use { response ->
                lastStatus = response.code
                completed.incrementAndGet()
                val body = response.body ?: return@use arrayOf(response.code.toString(), null, 99999)
                val source = body.source()
                source.request(2L * 1024 * 1024 + 1)
                if (source.buffer.size > 2L * 1024 * 1024) return@use arrayOf(response.code.toString(), null, 99999)
                arrayOf(response.code.toString(), source.readByteArray(), if (response.code == 200) 0 else 80005)
            }
        } catch (_: java.io.IOException) {
            lastStatus = -1
            completed.incrementAndGet()
            // f5 reserves 99999 for an unclassified transport exception; never manufacture 200/success.
            arrayOf("-1", null, 99999)
        }
    }
}
