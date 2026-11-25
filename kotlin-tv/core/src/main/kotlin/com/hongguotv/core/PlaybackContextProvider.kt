// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject

class PlaybackAccountException(message: String) : IOException(message)
class PlaybackCooldownException(val retryAfterSeconds: Int) : IOException("播放服务暂时无响应，请 $retryAfterSeconds 秒后重试")

internal object AccountCookies {
    fun encode(c: Cookie): String = JSONObject().put("name", c.name).put("value", c.value)
        .put("domain", c.domain).put("path", c.path).put("expiresAt", c.expiresAt)
        .put("hostOnly", c.hostOnly).put("secure", c.secure).put("httpOnly", c.httpOnly).toString()

    fun decode(raw: String): Cookie? = runCatching {
        val j = JSONObject(raw)
        Cookie.Builder().name(j.getString("name")).value(j.getString("value"))
            .path(j.getString("path")).expiresAt(j.getLong("expiresAt")).apply {
                if (j.getBoolean("hostOnly")) hostOnlyDomain(j.getString("domain")) else domain(j.getString("domain"))
                if (j.getBoolean("secure")) secure()
                if (j.getBoolean("httpOnly")) httpOnly()
            }.build()
    }.getOrNull()

    fun merge(previous: List<String>, fresh: List<Cookie>, now: Long): List<String> {
        val kept = previous.mapNotNull(::decode).filter { old ->
            old.expiresAt > now && fresh.none { it.name == old.name && it.domain == old.domain && it.path == old.path }
        }
        return (kept + fresh.filter { it.expiresAt > now }).map(::encode)
    }
}

/** Only model API requests may receive an account. Never apply this policy to CDN/VOD URLs. */
internal fun isAccountModelUrl(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty() &&
    url.host in setOf("reading.snssdk.com", "api5-normal-sinfonlinea.fqnovel.com", "api5-normal-sinfonlineb.fqnovel.com") &&
    (url.encodedPath.startsWith("/novel/player/") || url.encodedPath == "/reading/bookapi/video_tab/multi_video_model/v1/")

internal class AppAccountSession(
    initial: AccountCredentials,
    private val clock: () -> Long,
    private val allowed: (HttpUrl) -> Boolean = ::isAccountModelUrl,
    private val changed: (AccountCredentials, AccountCredentials) -> Unit,
) : Interceptor {
    private val state = java.util.concurrent.atomic.AtomicReference(initial)
    val credentials: AccountCredentials get() = state.get()
    fun replace(previous: AccountCredentials, next: AccountCredentials): Boolean {
        val current = state.get()
        return current == previous && state.compareAndSet(current, next)
    }
    @Volatile private var expired = false

    init {
        val d = initial.device
        if (initial.source != "app" || initial.appId != "8662" ||
            !initial.deviceId.matches(Regex("[1-9][0-9]{0,29}")) ||
            !initial.installId.matches(Regex("[1-9][0-9]{0,29}")) ||
            d["device_id"] != initial.deviceId || d["iid"] != initial.installId ||
            listOf("cdid", "version_code", "version_name", "device_type", "device_brand", "os_version", "channel").any { d[it].isNullOrBlank() })
            throw PlaybackAccountException("当前登录态尚不支持账号播放，请开启实验功能后使用短信重新登录")
    }

    @Synchronized private fun update(response: Response, previous: AccountCredentials) {
        if (credentials != previous) return
        val cookies = AccountCookies.merge(previous.scopedCookies, Cookie.parseAll(response.request.url, response.headers), clock())
        val token = response.header("X-Tt-Token") ?: previous.token
        val next = if (response.code == 401) {
            previous.copy(token = "", cookie = "", scopedCookies = emptyList())
        } else previous.copy(token = token, scopedCookies = cookies)
        if (next != previous && replace(previous, next)) {
            if (response.code == 401) expired = true
            changed(previous, next)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        RequestScope.ensureActive()
        val request = chain.request()
        if (!allowed(request.url)) return chain.proceed(request)
        val account = credentials
        if (account.token.length > 16_384 || account.token.any { it.code !in 0x20..0x7e })
            throw PlaybackAccountException("播放会话格式异常，请重新登录")
        val cookies = account.scopedCookies.mapNotNull(AccountCookies::decode)
            .filter { it.expiresAt > clock() && it.matches(request.url) }
        if (expired || account.token.isBlank() && cookies.none { it.name in setOf("sessionid", "sessionid_ss", "sid_tt") })
            throw PlaybackAccountException("App 播放会话已失效或尚未取得，请重新登录")
        val builder = request.newBuilder().removeHeader("Cookie").removeHeader("X-Tt-Token")
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
        if (account.token.isNotBlank()) builder.header("X-Tt-Token", account.token)
        builder.header("sdk-version", "2").header("passport-sdk-version", "5051452")
        val response = chain.proceed(builder.build())
        try {
            RequestScope.ensureActive()
            update(response, account)
            if (response.code == 401) throw PlaybackAccountException("账号播放会话已过期，请重新登录")
            return response
        } catch (error: Exception) { response.close(); throw error }
    }
}

/** One process-wide owner in the app, shared by the homepage and full player. */
class PlaybackContextProvider(
    val devices: PlaybackDeviceSession,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onAccountUpdated: (AccountCredentials, AccountCredentials) -> Unit = { _, _ -> },
    val requestSigner: AppRequestSigner = KotlinAppRequestSigner(),
) {
    @Volatile var accountPlaybackEnabled = false
        private set
    private var account: AccountCredentials? = null
    private var accountReady = false
    private var current: Context? = null
    private var epoch = 0L
    private var scope = RequestScope()

    @Synchronized fun configure(enabled: Boolean, credentials: AccountCredentials?, restored: Boolean = true): Boolean {
        val identityChanged = enabled != accountPlaybackEnabled || enabled && (credentials != account || restored != accountReady)
        accountPlaybackEnabled = enabled
        account = credentials
        accountReady = restored
        if (!identityChanged) return false
        scope.cancel()
        scope = RequestScope()
        epoch++
        current = null
        return true
    }

    /** A response from another account API can refresh credentials without changing playback generation. */
    @Synchronized fun refreshAccount(previous: AccountCredentials, next: AccountCredentials) {
        if (account != previous) return
        val session = current?.accountSession
        if (session != null && !session.replace(previous, next)) return
        account = next
    }

    @Synchronized fun close() { scope.cancel(); epoch++; current = null }

    internal class Context(val profile: PlaybackDeviceSession.Profile, val accountSession: AppAccountSession?, val scope: RequestScope,
                           val requestSigner: AppRequestSigner) {
        val gate = PlaybackRequestGate()
        private val models = linkedSetOf<String>()
        private var decoded = false
        @Volatile var authSent = "not-sent"
        @Synchronized fun modelReady(id: String) { models += id; if (models.size > 16) models.remove(models.first()) }
        @Synchronized fun firstFrame(id: String) { if (id in models) decoded = true }
        @Synchronized fun validation() = if (decoded) "first-frame" else if (models.isNotEmpty()) "model-ready" else "unverified"
        fun transport(http: OkHttpClient): OkHttpClient = http.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .apply {
                interceptors().removeAll { it is AppAccountSession || it is AppSigningInterceptor }
                accountSession?.let { interceptors().add(0, it) }
                interceptors().add(if (accountSession == null) 0 else 1,
                    AppSigningInterceptor(requestSigner, {
                        AppSigningContext(profile.values, accountSession?.credentials?.signingGeneration())
                    }, observed = { token, cookie ->
                        authSent = when { token && cookie -> "token+cookie"; token -> "token"; cookie -> "cookie"; else -> "none" }
                    }))
            }.build()
    }

    internal fun snapshot(http: OkHttpClient, speculative: Boolean): Context {
        val credentials: AccountCredentials?
        val generation: Long
        val owner: RequestScope
        synchronized(this) {
            current?.let { return it }
            if (accountPlaybackEnabled && !accountReady) throw PlaybackAccountException("正在恢复账号，请稍后重试")
            credentials = account.takeIf { accountPlaybackEnabled }
            generation = epoch
            owner = scope
        }
        val session = credentials?.let { initial ->
            AppAccountSession(initial, clock) { previous, next ->
                synchronized(this) {
                    if (epoch != generation || account != previous) return@AppAccountSession
                    account = next
                    onAccountUpdated(previous, next)
                }
            }
        }
        val profile = owner.alongside {
            if (session != null) PlaybackDeviceSession.Profile(credentials!!.device.toMap(), "")
            else if (speculative) devices.existing() ?: throw IOException("播放设备尚未就绪")
            else devices.profile(http)
        }
        synchronized(this) {
            if (epoch != generation) throw java.io.InterruptedIOException("播放身份已变更")
            return current ?: Context(profile, session, owner, requestSigner).also { current = it }
        }
    }

    internal fun <T> run(context: Context, action: () -> T): T = context.scope.alongside {
        val result = action()
        RequestScope.ensureActive()
        result
    }

    internal fun deferPrefetch(): Boolean = synchronized(this) { current?.gate?.isCooling(clock()) == true } ||
        devices.shouldDeferPlaybackPrefetch(clock())

    internal fun resolve(context: Context, id: String, action: () -> StreamLadder): StreamLadder =
        try {
            run(context) { context.gate.run(id, clock, action) }.also {
                synchronized(this) { if (current === context) context.modelReady(id) }
            }
        } catch (error: IOException) {
            error.addSuppressed(PlaybackDiagnosticException(contextSummary(context)))
            throw error
        }

    @Synchronized fun recordFirstFrame(id: String) { current?.firstFrame(id) }

    @Synchronized fun diagnosticSummary(): String = current?.let(::contextSummary) ?: "media=unverified"

    @Synchronized fun accountPlaybackStatus(): String {
        if (!accountPlaybackEnabled) return "账号播放未开启"
        if (!accountReady) return "正在恢复播放会话"
        if (account == null) return "当前使用游客播放"
        if (account?.source != "app") return "需通过短信登录建立 App 播放会话"
        if (account?.token.isNullOrBlank() && account?.scopedCookies.isNullOrEmpty()) return "播放会话已失效，请重新登录"
        return when (current?.validation()) {
            "first-frame" -> "本次账号会话已成功播放"
            "model-ready" -> "已取得播放信息，等待实际播放验证"
            else -> "账号已登录，播放尚未验证"
        }
    }

    private fun contextSummary(context: Context): String =
        "mode=${if (context.accountSession == null) "guest" else "account"} " +
            "signer=${if (context.requestSigner.backend == SigningBackend.NATIVE_SDK) "native" else "kotlin-v5"} " +
            "auth=${context.authSent} media=${context.validation()}"
}

/** Coalesces overlapping model reads and shares backoff across episodes and manual retry buttons. */
internal class PlaybackRequestGate {
    private val active = mutableMapOf<String, CompletableFuture<StreamLadder>>()
    private var failures = 0
    private var blockedUntil = 0L
    @Synchronized fun isCooling(now: Long) = now < blockedUntil

    fun run(id: String, clock: () -> Long, action: () -> StreamLadder): StreamLadder {
        val future: CompletableFuture<StreamLadder>
        val owner: Boolean
        synchronized(this) {
            val pending = active[id]
            if (pending != null) { future = pending; owner = false }
            else {
                if (isCooling(clock())) throw PlaybackCooldownException(((blockedUntil - clock() + 999) / 1000).toInt())
                future = CompletableFuture(); active[id] = future; owner = true
            }
        }
        if (!owner) {
            val end = RequestScope.deadlineAtMost(System.nanoTime() + TimeUnit.SECONDS.toNanos(8))
            while (true) {
                RequestScope.ensureActive()
                if (System.nanoTime() >= end) throw java.io.InterruptedIOException("等待播放信息超时")
                try { return future.get(50, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { }
                catch (error: ExecutionException) { throw error.cause ?: error }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); RequestScope.ensureActive() }
            }
        }
        try {
            val result = action()
            RequestScope.ensureActive()
            synchronized(this) { failures = 0; blockedUntil = 0 }
            future.complete(result)
            return result
        } catch (error: Throwable) {
            synchronized(this) {
                if (error is EmptyPlaybackResponseException || error is PlaybackServiceBackoffException) {
                    failures++
                    if (failures >= 2 || error is PlaybackServiceBackoffException) blockedUntil = clock() + 30_000
                }
            }
            future.completeExceptionally(error)
            throw error
        } finally { synchronized(this) { active.remove(id) } }
    }
}
