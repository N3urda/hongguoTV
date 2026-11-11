// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 伴机交互失败：message 为用户可读文案，经 playbackFailureSummary 原样透出。 */
internal class RelayException(message: String) : java.io.IOException(message)

/** 一次伴机转码会话的接入信息：sessionId/hlsUrl + 用户可见的分辨率标签。 */
internal data class RelayPlayback(val sessionId: String, val hlsUrl: String, val qualityLabel: String)

/** 伴机设置持久化（独立 SharedPreferences，不与观看偏好混存）。 */
internal class RelayStore(context: Context) {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit().putString("host", value).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()
}

/**
 * 果映伴机客户端：健康检查、会话创建/seek/停止。短超时，任何网络异常转 null/false 由调用方
 * 降级回现有兜底 UX；伴机端协议见 relay/ 模块（零登录态，TV 传 stream info）。
 * 令牌非空时自动附加 X-Relay-Token 头；伴机 401 以 RelayException 透出（文案可读）。
 */
internal class RelayClient(
    private val http: OkHttpClient,
    private val host: String,
    private val token: String = "",
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    /** 带默认端口补全的规范化地址；不合法返回 null（交由调用方提示）。 */
    companion object {
        const val DEFAULT_PORT = 46521
        private val SHAPE = Regex("^([A-Za-z0-9.\\-]+):(\\d{2,5})$")

        fun normalizeHost(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val candidate =
                if (trimmed.contains(':')) trimmed
                else "$trimmed:$DEFAULT_PORT"
            val match = SHAPE.matchEntire(candidate) ?: return null
            val port = match.groupValues[2].toIntOrNull() ?: return null
            if (port !in 1024..65535) return null
            return candidate
        }
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): Request {
        val builder = Request.Builder().url("http://$host$path")
        if (token.isNotBlank()) builder.header("X-Relay-Token", token)
        when {
            body != null -> builder.post(body.toString().toRequestBody(json))
            method == "DELETE" -> builder.delete()
        }
        return builder.build()
    }

    private fun send(path: String, method: String = "GET", body: JSONObject? = null): JSONObject? =
        try {
            http.newCall(request(path, method, body)).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(
                        "HongguoTV",
                        "relay $method $path → HTTP ${response.code}",
                    )
                    return null
                }
                val text = response.body?.string() ?: return null
                if (text.isBlank()) null else JSONObject(text)
            }
        } catch (problem: Exception) {
            // 伴机探测失败原因必须可诊断：超时/拒绝/网段问题在日志里一眼可分。
            android.util.Log.w("HongguoTV", "relay $method $path → ${problem.javaClass.simpleName}: ${problem.message?.take(120)}")
            null
        }

    /** GET /health：null = 不可达。 */
    fun health(): JSONObject? = send("/health")

    /**
     * POST /session：创建转码会话，返回 sessionId to hlsUrl。
     * 伴机被占用（409）/参数拒绝/网络失败一律 null。会话创建含伴机侧取流与采样表扫描，
     * 超时放宽到 20s（正常秒级）。
     */
    fun createSession(videoUrl: String, keyB64: String?, quality: String): Pair<String, String>? {
        val payload = JSONObject().put("url", videoUrl).put("quality", quality)
        if (keyB64 != null) payload.put("keyB64", keyB64)
        val longCall = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

        fun post(): Pair<Int, JSONObject?> =
            try {
                longCall.newCall(request("/session", body = payload)).execute().use { response ->
                    val text = response.body?.string()
                    val parsed =
                        text?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                    response.code to parsed
                }
            } catch (problem: Exception) {
                android.util.Log.w(
                    "HongguoTV",
                    "relay POST /session → ${problem.javaClass.simpleName}: ${problem.message?.take(120)}",
                )
                0 to null
            }

        fun materialize(code: Int, body: JSONObject?): Pair<String, String>? {
            if (code == 401) throw RelayException("伴机令牌不匹配，请在播放设置核对令牌")
            if (code != 200 || body == null) {
                android.util.Log.w("HongguoTV", "relay POST /session → HTTP $code")
                return null
            }
            val sessionId = body.optString("sessionId")
            val hlsUrl = body.optString("hlsUrl")
            return if (sessionId.isNotBlank() && hlsUrl.isNotBlank()) sessionId to hlsUrl else null
        }

        var (code, body) = post()
        if (code == 409) {
            // 快速重进场景：旧会话的异步 DELETE 可能尚未生效，主动停掉占用会话后重试一次。
            val busyId = body?.optString("sessionId").orEmpty()
            if (busyId.isNotBlank()) {
                android.util.Log.w("HongguoTV", "relay session busy ($busyId), stopping and retrying")
                stop(busyId)
                val retry = post()
                code = retry.first
                body = retry.second
            }
        }
        return materialize(code, body)
    }

    fun seek(sessionId: String, offsetSec: Double): Boolean =
        runCatching {
            http.newCall(
                    request(
                        "/session/$sessionId/seek",
                        body = JSONObject().put("offsetSec", offsetSec),
                    ),
                )
                .execute()
                .use { it.isSuccessful }
        }.getOrDefault(false)

    fun stop(sessionId: String): Boolean =
        runCatching {
            http.newCall(request("/session/$sessionId", method = "DELETE"))
                .execute()
                .use { it.isSuccessful }
        }.getOrDefault(false)
}
