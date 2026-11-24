// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Signing returns the final URL because v5 selects its digest branch through the request ticket. */
internal data class MobileSignedRequest(val url: String, val headers: Map<String, String>)

internal fun interface MobilePlaybackSigner {
    fun sign(url: String, body: ByteArray, device: Map<String, String>): MobileSignedRequest
}

internal object MobilePlayback {
    // 2026-11-23 server migration: the retired player/v1 endpoint and 7.4.3.32 profile
    // return HTTP 200 with an empty body. This is the endpoint/profile currently served
    // to the official client; it also preserves this fork's injected signer and error
    // taxonomy instead of importing the other project's StreamSource framework.
    private const val MODEL_ENDPOINT = "https://reading.snssdk.com/novel/player/multi_video_model/preload/v1"
    private val v5Signer = Signer(mobileV5 = true)
    val signer = MobilePlaybackSigner { url, body, _ ->
        val parsed = url.toHttpUrl()
        val values = parsed.queryParameterNames.associateWith { parsed.queryParameter(it)!! }
        val now = values.getValue("_rticket").toLong()
        val request = v5Signer.signPlayback(parsed.newBuilder().query(null).build().toString(), values, body, now)
        MobileSignedRequest(request.url, request.headers)
    }

    internal fun endpointForHost(host: String): String =
        if (host == MODEL_ENDPOINT.toHttpUrl().host) MODEL_ENDPOINT
        else "https://$host/novel/player/multi_video_model/preload/v1"

    private fun query(device: Map<String, String>) =
        linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74532", "manifest_version_code" to "74532",
            "update_version_code" to "74532", "version_name" to "7.4.5.32",
            "channel" to "update_64", "device_platform" to "android", "os" to "android",
            "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
            "language" to "zh", "os_api" to "36", "os_version" to "16",
            "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi",
            "host_abi" to "arm64-v8a",
            "device_id" to device.getValue("device_id"),
            "iid" to device.getValue("iid"), "cdid" to device.getValue("cdid"),
        )

    /** The player GET also requires v5, including MD5(empty) rather than a zero body digest.
     * Wire profile verified against hongguo-drama 92df757 and hongguo-tv 9e7386f.
     */
    fun videoTabRequest(id: String, device: Map<String, String>, host: String, now: Long,
                        signing: MobilePlaybackSigner = signer): Request.Builder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val values = query(device) + linkedMapOf(
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
            "video_platform" to "1024", "source" to "0", "from_video_id" to "", "_rticket" to now.toString(),
        )
        val url = "https://$host/reading/bookapi/video_tab/multi_video_model/v1/"
            .toHttpUrl().newBuilder().apply { values.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val signed = signing.sign(url.toString(), ByteArray(0), values)
        RequestScope.ensureActive()
        return Request.Builder().url(signed.url).get()
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                signed.headers.forEach { (k, v) -> header(k, v) }
                // Empty bytes are signing input only: the GET has no body or POST headers.
                removeHeader("x-ss-stub")
                removeHeader("Content-Type")
                removeHeader("Content-Encoding")
            }
    }

    fun ladder(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        signer: MobilePlaybackSigner,
        now: Long,
        host: String = MODEL_ENDPOINT.toHttpUrl().host,
        endpoint: String = endpointForHost(host),
    ): StreamLadder {
        require(id.matches(Regex("[0-9]{1,30}")))
        RequestScope.ensureActive()
        val query = query(device)
        val url = endpoint.toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("_rticket", now.toString())
            .build()
        // Server now negotiates compatible renditions with device_level=2 on preload/v1.
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":2,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"preload"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val request = Request.Builder().url(url)
            .header("User-Agent", System.getProperty("http.agent")?.takeIf { it.isNotBlank() } ?: "okhttp/4.12.0")
            .header("Accept", "application/json").header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        val signed = signer.sign(url.toString(), bytes, query + ("_rticket" to now.toString()))
        request.url(signed.url)
        signed.headers.forEach { (k,v) -> request.header(k,v) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED, response.code)
            val body = response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val limit = 2 * 1024 * 1024L
            if (body.contentLength() > limit) throw IOException("播放信息过大")
            val source = body.source()
            source.request(limit + 1)
            val bytesRead = source.buffer.size
            if (bytesRead > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) {
                if (!response.header("Retry-After").isNullOrBlank()) throw PlaybackServiceBackoffException()
                throw EmptyPlaybackResponseException("stage=mobile-model").apply {
                    addSuppressed(PlaybackDiagnosticException("mobile-model=${response.code}/${bytesRead}B"))
                }
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            }
            PlaybackModels.checkStatus(root)
            val data = root.optJSONObject("data")
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val entries = data.optJSONObject("1004") ?: data
            val model = PlaybackModels.objectValue(entries.optJSONObject(id)?.opt("video_model"))
                ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            val rows = PlaybackModels.renditions(model)
            if (rows.isEmpty()) throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
            StreamLadder(rows)
        }
    }
}
