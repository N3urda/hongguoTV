// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.view.View
import com.hongguotv.core.*
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.json.JSONArray

/** Owns one foreground session; no background service, wake lock, web view or player. */
internal class PhoneConsole(
    private val activity: Activity,
    private val tools: TvTools,
    private val catalog: PhoneControlCatalog,
    private val control: (JSONObject) -> JSONObject,
    private val addressProvider: () -> String? = {
        LanInputAddresses.find(activity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager).firstOrNull()?.address
    },
    private val httpProvider: () -> OkHttpClient = { ContentRepository().http },
    private val showSearch: (String, Int, CatalogPage) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var starting = false
    private var generation = 0
    private var enabled = false
    private var foreground = false
    private var address: String? = null

    private class Session(val http: OkHttpClient, val repository: ContentRepository, val active: AtomicBoolean) {
        val browsing = AtomicBoolean(false)
        val suggesting = AtomicBoolean(false)
        val authenticating = AtomicBoolean(false)
        var login: WebLogin? = null
        var loginScope = 0
        var resendAt = 0L
        var qrExpiresAt = 0L
        var pollAt = 0L
        private var window = System.nanoTime()
        private var commands = 0
        @Synchronized fun allowCommand(): Boolean {
            val now = System.nanoTime()
            if (now - window >= TimeUnit.SECONDS.toNanos(1)) { window = now; commands = 0 }
            return ++commands <= 12
        }
        lateinit var server: PhoneControlServer
        fun close() {
            active.set(false)
            login?.cancel()
            login = null
            if (::server.isInitialized) server.close()
            Thread({ http.dispatcher.cancelAll(); http.connectionPool.evictAll() }, "hongguotv-phone-cleanup").apply { isDaemon = true; start() }
        }
    }

    fun resume() { foreground = true; if (enabled) start(null, false) }
    fun stop() {
        foreground = false
        generation++
        starting = false
        session?.close()
        session = null
        address = null
        catalog.clear()
    }
    fun disconnect() { enabled = false; stop(); foreground = true }
    fun networkChanged() {
        if (!foreground || !enabled) return
        if (findAddress() != address) { stop(); foreground = true }
        if (session == null) start(null, false)
    }
    private fun findAddress() = runCatching(addressProvider).getOrNull()

    fun show(anchor: View?) {
        enabled = true
        start(anchor, true)
    }

    private fun start(anchor: View?, show: Boolean) {
        if (!foreground || starting) return
        session?.let { if (show) tools.phoneConsole(it.server.url, anchor, ::disconnect); return }
        val selected = findAddress()
        if (selected == null) {
            if (show) tools.info("手机控制台", "请将手机与电视连接到可互通的 Wi-Fi 或有线局域网，然后重试。", anchor)
            return
        }
        address = selected
        starting = true
        val ticket = ++generation
        Thread({
            val active = AtomicBoolean(true)
            val http = httpProvider().newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    if (!active.get()) throw java.io.IOException("Session closed")
                    val response = chain.proceed(chain.request())
                    val body = response.body ?: return@addInterceptor response
                    if (body.contentLength() > 1024 * 1024) { response.close(); throw java.io.IOException("Metadata too large") }
                    val limited = object : ForwardingSource(body.source()) {
                        var count = 0L
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            val read = super.read(sink, minOf(byteCount, 8192))
                            if (read > 0) count += read
                            if (count > 1024 * 1024) throw java.io.IOException("Metadata too large")
                            return read
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = limited
                    }).build()
                }.connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS)).build()
            val created = Session(http, ContentRepository(http), active)
            try {
                created.server = PhoneControlServer(selected) { body -> request(created, body) }
                main.post {
                    if (ticket != generation || !foreground) { created.close(); return@post }
                    starting = false
                    session = created
                    if (show) tools.phoneConsole(created.server.url, anchor, ::disconnect)
                }
            } catch (_: Exception) {
                created.close()
                main.post {
                    if (ticket == generation) {
                        starting = false
                        if (show) tools.info("手机控制台", "无法开启连接，请检查局域网后重试。", anchor)
                    }
                }
            }
        }, "hongguotv-phone-start").apply { isDaemon = true; start() }
    }

    private fun <T> onMain(current: Session, action: () -> T): T {
        val task = FutureTask {
            check(current.active.get() && session === current && foreground) { "Session closed" }
            action()
        }
        main.post(task)
        return try { task.get(2, TimeUnit.SECONDS) }
        finally { task.cancel(false); main.removeCallbacks(task) }
    }

    private fun request(current: Session, body: JSONObject): JSONObject {
        val action = body.getString("action")
        if (action != "status" && !current.allowCommand()) return JSONObject().put("error", "操作过快，请稍候再试")
        if (action in setOf("loginQr", "loginSend", "loginVerify", "loginPoll")) return login(current, body)
        if (action in setOf("search", "related", "suggest", "discover")) {
            val reading = if (action == "suggest") current.suggesting else current.browsing
            if (!reading.compareAndSet(false, true)) return JSONObject().put("error", "正在读取，请稍候再试")
            try {
                val scope = onMain(current) { control(JSONObject().put("action", "status")).getInt("scope") }
                if (action == "suggest") {
                    val query = body.getString("query").trim()
                    require(query.length in 1..80 && query.none { it.isISOControl() })
                    return JSONObject().put("items", JSONArray(current.repository.searchSuggestions(query).take(8).map { it.take(100) }))
                }
                if (action == "discover") {
                    val raw = body.optJSONObject("filters") ?: JSONObject()
                    val selection = DiscoverySelection(raw.keys().asSequence().associateWith { raw.getString(it) })
                    val cursor = body.optJSONObject("cursor")?.let {
                        DiscoveryCursor(it.getInt("offset"), it.getString("session"), it.getInt("page"))
                    }
                    val result = current.repository.filteredDiscovery(selection, cursor)
                    return onMain(current) {
                        check(scope == control(JSONObject().put("action", "status")).getInt("scope"))
                        catalog.result(result.items.map { RelatedWork(it, "", false) }, result.next != null)
                            .put("groups", JSONArray(result.groups.map { group ->
                                JSONObject().put("type", group.type).put("label", group.label)
                                    .put("options", JSONArray(group.options.map { JSONObject().put("id", it.id).put("label", it.label) }))
                            })).put("cursor", result.next?.let { JSONObject().put("offset", it.offset).put("session", it.session).put("page", it.page) })
                    }
                }
                if (action == "related") {
                    val series = onMain(current) { catalog.series(body.getString("id")) }
                    val rows = current.repository.related(series.id).take(40)
                    return onMain(current) {
                        check(scope == control(JSONObject().put("action", "status")).getInt("scope"))
                        catalog.result(rows)
                    }
                }
                val query = body.getString("query").trim()
                val page = body.optInt("page", 1)
                require(query.length in 1..80 && query.none { it.isISOControl() } && page in 1..100)
                val result = current.repository.searchAll(query, page)
                return onMain(current) {
                    check(scope == control(JSONObject().put("action", "status")).getInt("scope"))
                    if (body.optBoolean("sync")) showSearch(query, page, result)
                    catalog.result(result.items.map { RelatedWork(it, "", false) }, result.hasMore)
                }
            } finally { reading.set(false) }
        }
        return onMain(current) {
            when (action) {
                "list" -> catalog.list(body)
                "play", "favorite", "reserve" -> catalog.act(body)
                "disconnect" -> { main.postDelayed({ if (session === current) disconnect() }, 200); JSONObject().put("message", "正在断开") }
                else -> { catalog.checkScope(); control(body) }
            }
        }
    }
    private fun login(current: Session, body: JSONObject): JSONObject {
        if (!current.authenticating.compareAndSet(false, true)) return JSONObject().put("error", "登录处理中，请稍候")
        try {
            val action = body.getString("action")
            val scope = onMain(current) { control(JSONObject().put("action", "status")).getInt("scope") }
            val now = android.os.SystemClock.elapsedRealtime()
            if (current.login != null && current.loginScope != scope) {
                current.login?.cancel(); current.login = null; current.qrExpiresAt = 0
            }
            if (action == "loginQr" || action == "loginSend") {
                if (action == "loginSend" && now < current.resendAt)
                    return JSONObject().put("error", "请等待倒计时结束再获取验证码")
                val login = current.login ?: WebLogin(current.http).also { current.login = it; current.loginScope = scope }
                check(current.loginScope == scope) { "账户已变化，请重新连接" }
                if (action == "loginQr") {
                    val qr = login.qr()
                    require(qr.image.size <= 128 * 1024)
                    current.qrExpiresAt = android.os.SystemClock.elapsedRealtime() + qr.expiresIn * 1000L
                    return JSONObject().put("image", "data:image/png;base64," + android.util.Base64.encodeToString(qr.image, android.util.Base64.NO_WRAP))
                        .put("expiresIn", qr.expiresIn).put("appUrl", qr.appUrl)
                }
                val mobile = PhoneLogin.parse(body.getString("mobile").also { require(it.length <= 32) })
                current.qrExpiresAt = 0
                current.resendAt = now + 60000
                val sent = login.send(mobile)
                val seconds = sent.retrySeconds.coerceIn(60, 300)
                current.resendAt = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
                return JSONObject().put("retrySeconds", seconds).put("message", "验证码已发送")
            }
            val login = current.login ?: return JSONObject().put("error", "请先获取验证码或二维码")
            check(current.loginScope == scope) { "账户已变化，请重新连接" }
            val credentials = when (action) {
                "loginVerify" -> login.verify(body.getString("code").also { require(it.matches(Regex("[0-9]{4,8}"))) })
                "loginPoll" -> {
                    if (current.qrExpiresAt == 0L) return JSONObject().put("error", "请先获取授权链接")
                    if (now < current.pollAt) return JSONObject().put("pending", true)
                    current.pollAt = now + 2000
                    if (!login.poll()) return if (now >= current.qrExpiresAt) JSONObject().put("error", "授权已过期，请重新获取") else JSONObject().put("pending", true)
                    login.credentials()
                }
                else -> throw IllegalArgumentException("Unknown login action")
            }
            onMain(current) { catalog.login(credentials, scope) }
            current.login = null
            return JSONObject().put("submitted", true).put("message", "验证成功，正在同步到电视…")
        } catch (e: PhoneLogin.Failure) {
            return JSONObject().put("error", e.message ?: "红果验证失败")
        } finally { current.authenticating.set(false) }
    }

}
