// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

/** One persistent registration shared by playback, prefetch and opt-in App login. */
class PlaybackDeviceSession(
    private val store: PlaybackDeviceStore? = null,
    private val device: Map<String, String> = PlaybackDeviceDefaults.values,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceFactory: (() -> Map<String, String>)? = null,
    private val register: ((OkHttpClient) -> DeviceRegister.Registered?)? = null,
) {
    private fun registerRequest(http: OkHttpClient, values: Map<String, String>): DeviceRegister.Registered? {
        register?.let { return it(http) }
        val body = DeviceRegister.payload(values, clock())
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, values, clock()).forEach { (key, value) -> request.header(key, value) }
        request.post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            val text = try { cloudResponse(response) } catch (_: EmptyAccountResponseException) {
                throw EmptyPlaybackResponseException("stage=register")
            }
            DeviceRegister.parse(text, response.headers("Set-Cookie"))
        }
    }
    companion object {
        val shared = PlaybackDeviceSession()
        private const val EMPTY_MODEL_PREFETCH_PAUSE_MILLIS = 5_000L
    }

    internal data class Profile(
        val values: Map<String, String>,
        val cookie: String,
        val routes: PlaybackRoutes = PlaybackRoutes(),
    ) {
        override fun toString() = "PlaybackProfile([redacted])"
        private var playbackSigner: Signer? = null
        private var recommendationSigner: Signer? = null
        private data class Model(val entry: org.json.JSONObject, val owner: Any, val savedAt: Long)
        private val models = linkedMapOf<String, Model>()
        // Background prefetch reads only the primary model endpoint.
        @Volatile private var emptyPrimaryModelAt: Long? = null

        fun recordEmptyPrimaryModel(now: Long) { emptyPrimaryModelAt = now }

        fun recordPlayablePrimaryModel() { emptyPrimaryModelAt = null }

        fun shouldDeferPlaybackPrefetch(now: Long): Boolean =
            emptyPrimaryModelAt?.let { now - it in 0 until EMPTY_MODEL_PREFETCH_PAUSE_MILLIS } == true

        // 首页进入全集可一次性交接刚取得的模型；同仓库主动重试始终重新读取，不复用旧地址。
        @Synchronized
        fun takeModel(id: String, owner: Any, now: Long): org.json.JSONObject? {
            models.entries.removeAll { now - it.value.savedAt !in 0..15_000 }
            val model = models.remove(id) ?: return null
            return if (model.owner === owner) null else org.json.JSONObject(model.entry.toString())
        }

        @Synchronized
        fun offerModel(id: String, entry: org.json.JSONObject, owner: Any, now: Long) {
            val encoded = entry.toString()
            if (encoded.toByteArray(Charsets.UTF_8).size > 256 * 1024) return
            models.entries.removeAll { now - it.value.savedAt !in 0..15_000 }
            models[id] = Model(org.json.JSONObject(encoded), owner, now)
            while (models.size > 6) models.remove(models.keys.first())
        }


        // 同一注册会话的进程 nonce 与启动时间保持一致，跨仓库实例也不重新生成。
        @Synchronized
        fun signer(clock: () -> Long, values: Map<String, String>): Signer =
            playbackSigner ?: Signer(clock, values).also { playbackSigner = it }

        // 推荐使用另一版本请求上下文，不能借用已绑定播放器版本的签名器。
        @Synchronized
        fun recommendationSigner(clock: () -> Long, values: Map<String, String>): Signer =
            recommendationSigner ?: Signer(clock, values).also { recommendationSigner = it }
    }

    private var context: PlaybackDeviceContext? = null
    private fun context(): PlaybackDeviceContext = context ?: run {
        val saved = store?.load()
        val value = if (saved != null) PlaybackDeviceContext.parse(saved)
            else PlaybackDeviceContext.fresh(deviceFactory?.invoke() ?: device, clock()).also { store?.save(it.toJson()) }
        value.also { context = it }
    }

    /** Registration and login use the same full identity. Values returned to callers are immutable copies. */
    fun deviceValues(http: OkHttpClient): Map<String, String> = profile(http).values.toMap()
    @Volatile private var ready: Profile? = null
    private var registrationFailures = 0
    private var registrationRetryAt = 0L

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        val seed = context()
        val registered = seed.registered ?: run {
            if (clock() < registrationRetryAt)
                throw PlaybackCooldownException(((registrationRetryAt - clock() + 999) / 1000).toInt())
            try {
                registerDevice(http, seed.values).also { registrationFailures = 0 }
            } catch (error: IOException) {
                RequestScope.ensureActive()
                if (++registrationFailures >= 2) registrationRetryAt = clock() + 30_000
                throw error
            }
        }
        val complete = PlaybackDeviceContext(seed.values, registered)
        if (seed.registered == null) store?.save(complete.toJson())
        context = complete
        return toProfile(registered, seed.values).also { ready = it }
    }

    internal fun shouldDeferPlaybackPrefetch(now: Long): Boolean =
        ready?.shouldDeferPlaybackPrefetch(now) == true

    @Synchronized
    internal fun existing(): Profile? = ready

    @Synchronized
    internal fun takeModel(id: String, owner: Any, now: Long) = ready?.takeModel(id, owner, now)

    /**
     * 在调用方的失败期限制内尝试替换匿名身份，部分历史采样有效，但不保证恢复。
     * 若其他仓库实例已替换 [expected]，直接复用替代身份，不重复注册。
     * 替代身份就绪时返回 true；失败时保留原身份。
     */
    @Synchronized
    internal fun rotate(http: OkHttpClient, expected: Profile? = null): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        // 共享会话的其他仓库实例已替换该失败身份，直接复用。
        if (expected != null && ready !== expected && ready != null) return true
        val seed = PlaybackDeviceContext.fresh(deviceFactory?.invoke() ?: device, clock())
        val device =
            try {
                registerRequest(http, seed.values) ?: return false
            } catch (error: InterruptedIOException) {
                // 网络超时也可能使用此异常类型，不能据此把正常工作线程标为已取消。
                return false
            } catch (error: IOException) {
                return false
            }
        if (!validDevice(device)) return false
        val complete = PlaybackDeviceContext(seed.values, device)
        store?.save(complete.toJson())
        context = complete
        ready = toProfile(device, seed.values)
        return true
    }

    private fun registerDevice(http: OkHttpClient, values: Map<String, String>): DeviceRegister.Registered {
        val device =
            try {
                registerRequest(http, values) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        if (!validDevice(device)) throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return device
    }

    private fun validDevice(device: DeviceRegister.Registered) =
        validId.matches(device.deviceId) && validId.matches(device.installId)

    private fun toProfile(device: DeviceRegister.Registered, values: Map<String, String>): Profile =
        Profile(
            values +
                mapOf(
                    "device_id" to device.deviceId,
                    "iid" to device.installId,
                ) + if (device.klinkEgdi.isBlank()) emptyMap() else mapOf("klink_egdi" to device.klinkEgdi),
            device.cookies
                .filterKeys {
                    it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                }
                .entries
                .joinToString("; ") { (name, value) -> "$name=$value" },
        )
}

private val validId = Regex("[1-9][0-9]{0,29}")
