// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.runtime.*
import com.hongguotv.core.*
import kotlinx.coroutines.*

interface AccountGateway {
    fun profile(account: AccountCredentials): CloudProfile
    fun favorites(account: AccountCredentials): List<CloudFavorite>
    fun history(account: AccountCredentials): List<CloudProgress>
    fun reservations(account: AccountCredentials, online: Boolean): List<CloudReservation>
    fun setFavorite(account: AccountCredentials, id: String, add: Boolean)
    fun reserve(account: AccountCredentials, work: RelatedWork)
    fun upload(account: AccountCredentials, progress: CloudProgress, count: Int)
    fun registerDevice(): DeviceRegister.Registered?
}
class LiveAccountGateway(private val client: CloudAccountClient) : AccountGateway {
    override fun profile(account: AccountCredentials) = client.profile(account)
    override fun favorites(account: AccountCredentials) = client.favorites(account)
    override fun history(account: AccountCredentials) = client.history(account)
    override fun reservations(account: AccountCredentials, online: Boolean) = client.reservations(account, online)
    override fun setFavorite(account: AccountCredentials, id: String, add: Boolean) = client.setFavorite(account, id, add)
    override fun reserve(account: AccountCredentials, work: RelatedWork) = client.reserve(account, work)
    override fun upload(account: AccountCredentials, progress: CloudProgress, count: Int) = client.upload(account, progress, count)
    override fun registerDevice() = client.registerDevice()
}

/** UI-thread owner. Every async result belongs to one login generation. Credentials are only persisted through the opt-in OS vault. */
class DesktopAccount(private val scope: CoroutineScope, private val client: AccountGateway = LiveAccountGateway(CloudAccountClient(DesktopNetwork.client())), private val store: CredentialStore = DesktopCredentialStore()) {
    private val http: okhttp3.OkHttpClient = DesktopNetwork.client()
    private val gate = PhoneLogin.RetryGate()
    private var phone = PhoneLogin(http, gate)
    private var web: WebLogin? = null
    private var job: Job? = null
    private var generation = 0
    private var clearing = false
    private var credentials: AccountCredentials? = null
    private var feed: RecommendationFeed? = null
    suspend fun recommendations(page: Int): CatalogPage {
        if (page == 1 || feed == null) feed = RecommendationFeed(http, credentials, playbackSession = PlaybackDeviceSession.shared)
        val current = feed!!
        return io { current.page(page) }
    }
    var profile by mutableStateOf<CloudProfile?>(null); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("登录后同步电视端的收藏、历史与预约"); private set
    var qr by mutableStateOf<ByteArray?>(null); private set
    var cooldown by mutableStateOf(0); private set
    var codeSent by mutableStateOf(false); private set
    var favorites by mutableStateOf<List<CloudFavorite>>(emptyList()); private set
    var history by mutableStateOf<List<CloudProgress>>(emptyList()); private set
    var reservations by mutableStateOf<List<CloudReservation>>(emptyList()); private set
    var favoritesLoaded by mutableStateOf(false); private set
    private var lastUpload = 0L
    private var uploadJob: Job? = null
    var changed: () -> Unit = {}
    var rememberLogin by mutableStateOf(false)
    fun restore() { action { ticket ->
        val value = io { store.load() } ?: return@action
        rememberLogin = true
        accept(value, ticket)
    } }

    init { scope.launch { while (isActive) { delay(1000); cooldown = maxOf(cooldown - 1, gate.remainingSeconds()) } } }
    private fun action(block: suspend (Int) -> Unit) {
        if (busy) return
        val ticket = generation
        busy = true
        job = scope.launch {
            try { block(ticket) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) status = when (e) {
                is AccountNotProvisionedException -> e.message.orEmpty()
                is PhoneLogin.Failure -> "登录未完成（${e.errorCode ?: "请重试"}）。请检查验证码，或改用扫码；如官方 App 要求验证，请先完成验证。"
                else -> if (profile == null) "登录请求未完成或二维码已过期，请重新获取二维码或使用短信登录" else "请求未完成，请检查网络并刷新。涉及收藏或预约时，请先刷新核对结果再重试。"
            } }
            finally { if (ticket == generation) { busy = false; if (profile == null) qr = null; changed() } }
        }
    }
    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO, block)
    internal suspend fun accept(value: AccountCredentials, ticket: Int = generation) {
        val account = io { client.profile(value) }
        if (ticket != generation) return
        credentials = value; feed = null; profile = account; qr = null; codeSent = false
        status = "已登录 ${account.nickname}"
        if (rememberLogin) {
            try { io { store.save(value) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ticket == generation) status = "已登录，但系统安全存储失败；下次启动需重新登录" }
        }
        if (ticket != generation) return
        refreshData(value, ticket)
    }
    fun startQr() {
        if (busy) return
        cancelLogin()
        val login = WebLogin(http); web = login
        action { ticket ->
            val image = io { login.qr() }
            if (ticket != generation) return@action
            qr = image.image; status = "请使用红果 App 扫码，并在手机确认登录"
            val deadline = System.nanoTime() + image.expiresIn * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                delay(2000)
                if (io { login.poll() }) {
                    val value = io {
                        val raw = login.credentials()
                        val device = client.registerDevice() ?: error("设备初始化失败")
                        raw.copy(deviceId = device.deviceId, installId = device.installId)
                    }
                    accept(value, ticket); return@action
                }
            }
            qr = null; status = "二维码已过期，请重新获取"
        }
    }
    fun sendCode(country: String, number: String) {
        if (busy || cooldown > 0) return
        val mobile = try { PhoneLogin.format(country, number) } catch (_: Exception) { status = "请输入有效的国家区号和手机号"; return }
        phone = PhoneLogin(http, gate); codeSent = false
        action { ticket ->
            // Set a local cooldown before sending, including uncertain network failures.
            cooldown = 60
            val sent = io { phone.send(mobile) }
            if (ticket == generation) { codeSent = true; cooldown = maxOf(60, sent.retrySeconds); status = "验证码已发送，请输入验证码" }
        }
    }
    fun verify(code: String) {
        if (!codeSent || !code.matches(Regex("[0-9]{4,8}"))) { status = "请输入 4–8 位验证码"; return }
        action { ticket -> accept(io { phone.verify(code) }, ticket) }
    }
    fun cancelLogin() {
        if (profile != null || clearing) return
        generation++; job?.cancel(); web?.cancel(); web = null; qr = null; busy = false; codeSent = false; phone = PhoneLogin(http, gate)
    }
    fun logout(clearStored: Boolean = true) {
        generation++; job?.cancel(); uploadJob?.cancel(); web?.cancel(); web = null
        credentials = null; feed = null; profile = null; qr = null; busy = false; codeSent = false
        phone = PhoneLogin(http, gate); favorites = emptyList(); history = emptyList(); reservations = emptyList(); favoritesLoaded = false; lastUpload = 0
        status = "已退出登录，本机收藏和观看记录仍保留"; changed()
        if (clearStored) {
            busy = true; clearing = true
            val ticket = generation
            job = scope.launch {
                try { io { store.clear() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (ticket == generation) status = "已退出本次会话，但系统保存的登录态未删除，请重试退出登录" }
                finally { if (ticket == generation) { busy = false; clearing = false } }
            }
        }
    }
    private suspend fun refreshData(account: AccountCredentials, ticket: Int) {
        // Apply each successful section independently; a reservations outage must not hide favorites.
        if (ticket != generation) return
        val shelf = io { client.favorites(account) }
        if (ticket != generation) return
        favorites = shelf; favoritesLoaded = true; changed()
        val progress = io { client.history(account) }
        if (ticket != generation) return
        history = progress; changed()
        val reserved = io { client.reservations(account, false) + client.reservations(account, true) }
        if (ticket == generation) reservations = reserved.distinctBy { it.series.id }
    }
    fun refresh() { val account = credentials ?: return; action { ticket -> refreshData(account, ticket); if (ticket == generation) status = "云端数据已刷新" } }
    fun toggle(series: Series) {
        val account = credentials ?: return
        if (!favoritesLoaded) { refresh(); return }
        val add = favorites.none { it.series.id == series.id }
        action { ticket ->
            var failed = false
            favoritesLoaded = false
            try { io { client.setFavorite(account, series.id, add) } } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
            val rows = io { client.favorites(account) }
            if (ticket != generation) return@action
            favorites = rows; favoritesLoaded = true
            status = if (rows.any { it.series.id == series.id } == add) "云收藏已确认" else if (failed) "操作结果未确认，请刷新核对后再操作" else "云收藏尚未更新，请稍后刷新"
        }
    }
    fun reserve(work: RelatedWork) {
        val account = credentials ?: run { status = "请先登录后预约"; return }
        if (reservations.any { it.series.id == work.series.id }) return
        action { ticket ->
            try { io { client.reserve(account, work) } } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Read back before deciding. */ }
            val rows = io { client.reservations(account, false) + client.reservations(account, true) }
            if (ticket == generation) { reservations = rows.distinctBy { it.series.id }; status = if (rows.any { it.series.id == work.series.id }) "预约已确认" else "预约已提交，请稍后刷新核对" }
        }
    }
    fun upload(progress: BackupProgress, count: Int, force: Boolean = false) {
        val account = credentials ?: return
        val now = System.nanoTime() / 1_000_000
        if (uploadJob?.isActive == true || (!force && now - lastUpload < 30_000)) return
        val ticket = generation; lastUpload = now
        uploadJob = scope.launch {
            try {
                io { client.upload(account, CloudProgress(progress.series, progress.episodeId, progress.episodeIndex, progress.position, progress.duration, progress.updatedAt, count), count) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ticket == generation) status = "本机进度已保存，云端进度同步失败；播放期间会继续同步" }
        }
    }
}
