// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 主页面入口：协调栏目切换、详情和正式播放；具体界面组件及数据请求由协作对象负责。

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val bg = TvStyle.background
    private val surface = TvStyle.surface
    private val accent = TvStyle.accent
    private val white = TvStyle.text
    private val muted = TvStyle.muted
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val repository = ContentRepository(playbackSession = PlaybackDeviceSession.shared)
    // 榜单专用并行加载通道：最多四榜各自在途、互不取消，与全局单飞 work() 隔离。
    private val rankWorker = Executors.newFixedThreadPool(3)
    private val networkCleanup = Executors.newSingleThreadExecutor()
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient? = null
    private var playbackHttp: okhttp3.OkHttpClient? = null

    // 伴机兜底（bytevc2 锁定集）：会话与尝试标记随每次 playEpisode 重置。
    private var relayAttempted = false
    private var relaySessionId: String? = null
    private var relaySlowCount = 0
    private var relaySlowHintShown = false
    private var relaySpeedWatch: Runnable? = null
    private val relayStore by lazy { RelayStore(this) }
    private val relayHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val preparedNext =
        PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>? = null
    private var prefetchScope: RequestScope? = null

    private data class PendingEpisodePreparation(
        val key: String,
        val job: java.util.concurrent.Future<*>,
        val scope: RequestScope,
        val http: okhttp3.OkHttpClient,
    )

    private var promotedPreparation: PendingEpisodePreparation? = null
    private var lastPlaybackFailure = ""
    private var lastPlaybackDetail: String? = null
    private var autoRecoveredPlay = false
    private var queuedNext: RemoteVideo? = null
    private var queuedHttp: okhttp3.OkHttpClient? = null
    private var queuedEpisode = -1
    private var episodeDuration = 0L
    private var prefetchedFor = ""
    private var readySince = 0L
    private lateinit var artwork: ArtworkLoader

    private data class DeferredUi(val apply: () -> Unit, val discard: () -> Unit = {})

    private var deferredWork: DeferredUi? = null
    private var initialCatalogPending = true
    private var restoreStoppedPage = false
    private var pendingDetail: Series? = null
    // 仅在当前详情中保留云端续播信息，并按账号会话隔离，不预先导入本机历史。
    private var detailCloudProgress: CloudProgress? = null
    private var detailCloudScope = -1
    private var seasonGeneration = 0
    private var seasonBanner: TextView? = null
    private var nextSeasonWork: RelatedWork? = null
    private var seasonSuppressed = false
    private var seasonPending = false
    private var detailAutoplay = true
    private var libraryLoadError: Throwable? = null
    private var workScope: RequestScope? = null
    private var workJob: java.util.concurrent.Future<*>? = null
    private var playerListener: Player.Listener? = null
    private lateinit var playbackTitle: TextView
    private var directPlayback = false
    private var collection: String? = null
    private var collectionReturn: CatalogState? = null
    private var collectionFocus = ""
    private var collectionBack: View? = null
    private val pageSize = 20
    private var continuousCatalog: ContinuousCatalogView? = null
    private val continuousPositions = linkedMapOf<String, ContinuousCatalogView.Position>()
    private var continuousKey = ""
    private var catalogScroll: ScrollView? = null
    private var catalogBody: LinearLayout? = null
    private var homePosition: HomeScreen.Position? = null
    private var catalogPageKey = ""
    private val scrollPositions = mutableMapOf<String, Int>()
    private val catalogCards = linkedMapOf<String, View>()
    private val cardImages = linkedMapOf<String, Pair<ImageView, String>>()
    private var prefetchAttemptAt = 0L
    private var prefetchAttempts = 0
    private val homeFeedState = DramaFeedState()
    private var homeFeed: HomeFeedScreen? = null
    private var homeFeedIdentity = ""
    private var homeScreen: HomeScreen? = null
    private var selectionPreview: SeriesPreview? = null
    private var homeFromCache = false
    private var homeUpdateCount = 0
    private var launchStarted = android.os.SystemClock.elapsedRealtime()
    private val activityStarted = launchStarted
    private var homeContentLogged = false
    private lateinit var library: Library
    private var searchDraft: String? = null
    private var searchSuggestions: SearchSuggestionsView? = null
    private var tvSearchPanel: TvSearchPanel? = null
    private var submittedSearch: String? = null
    private var tvSearchOpen = false
    private val discoveryBrowser = DiscoveryBrowserState()
    private var discoveryScreen: DiscoveryScreen? = null
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater

    private data class DetailVisit(
        val detail: Detail,
        val episode: Int,
        val group: Int,
        val cloudProgress: CloudProgress?,
        val cloudScope: Int,
    )

    private val detailTrail = java.util.ArrayDeque<DetailVisit>()
    private var relatedSection: RelatedWorksView? = null
    private var accountFavoriteButton: TextView? = null
    private var reservationOnline = true
    private val reservationFilters = mutableListOf<View>()
    private var accountHistoryScope = 0
    private lateinit var cloudAccount: AccountController
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer = SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped = false
    private var playerView: PlayerView? = null
    private val social by lazy {
        TvSocial(
            this,
            { player },
            {
                if (screen == "player" || screen == "detail")
                    detail?.let { data ->
                        data.episodes.getOrNull(episodeIndex)?.let { data.series.id to it }
                    }
                else null
            },
        )
    }
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges = mutableMapOf<String, TextView>()
    private var favoriteStatus: TextView? = null
    private var favoriteCheck: TextView? = null
    private var homeUpdates: TextView? = null
    private val resumeCards = mutableListOf<View>()
    private var episodePanel: EpisodePanel? = null
    private val recovery = RecoveryBudget()
    private var retryPending = false
    private var autoRecovery = false
    private var recoveryScheduledAt = 0L
    private var recoveryDelayMillis = 0L

    /**
     * 恢复等待期的逐秒倒计时（issue #41 后续）：慢段单次等待最长 105 秒，静态文案看起来像死机。
     * 只按剩余毫秒刷新文本，调度权仍在 scheduleRecovery/retryRunnable；remaining 归零即停，
     * 由 retryRunnable 接管。
     */
    private val recoveryCountdown =
        object : Runnable {
            override fun run() {
                if (!retryPending) return
                val remaining =
                    recoveryScheduledAt + recoveryDelayMillis - android.os.SystemClock.uptimeMillis()
                if (remaining <= 0) return
                showRecoveryStatus(remaining)
                main.postDelayed(this, 1000)
            }
        }
    private var startPosition = 0L
    private var retryPosition = 0L
    private var requestedAutoplay = true
    private var waitingForFirstFrame = false
    private var hideAutoplayOverlay = false
    private var recoveryDisplayActive = false
    private var allowSlowRecovery = false
    private var playbackPreparationUntil = 0L
    private var recoveryAction: TextView? = null
    private var retryAutoplay = true
    private var foreground = false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered = false
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                main.post {
                    if (connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch()
                    if (foreground && screen == "player" && playError && autoRecovery)
                        scheduleRecovery()
                }
            }

            override fun onAvailable(network: Network) {
                changed()
            }

            override fun onLost(network: Network) {
                changed()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                changed()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                changed()
            }
        }
    private val retryRunnable = Runnable {
        retryPending = false
        if (foreground && screen == "player" && playError && autoRecovery) {
            if (!networkAvailable()) scheduleRecovery()
            else if (!allowSlowRecovery && android.os.SystemClock.elapsedRealtime() >= playbackPreparationUntil) scheduleRecovery()
            else if (recovery.consume()) {
                android.util.Log.i(
                    "HongguoTV",
                    "Automatic playback recovery ${recovery.attempts}/${recovery.total} at ${retryPosition}ms",
                )
                autoRecoveredPlay = true
                playEpisode(episodeIndex, retryPosition, retryAutoplay, recovering = true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation = 0
    private var screen = "catalog"
    private var tab = 0
    private var startupApplied = false
    private var settingsAccountStatus: TextView? = null
    private var settingsGroup = 0
    private val settingsPositions = IntArray(4)
    private var settingsSidebar = emptyList<View>()
    private var settingsRows = emptyList<View>()
    private lateinit var librarySources: LibrarySources
    private val sourceButtons = mutableListOf<View>()
    private var page = 1
    private var query = ""
    private var catalog = emptyList<Series>()
    private var hasMore = false
    private var catalogFocus = ""

    private data class CatalogState(
        val page: Int,
        val items: List<Series>,
        val hasMore: Boolean,
        val ranking: ComicRanking?,
    )

    private val rankBrowser = RankBrowserState()
    private var ranking: ComicRanking? = null
    private var rankRefresh: View? = null
    private var rankSubtitle: TextView? = null
    private val tabState = mutableMapOf<Int, CatalogState>()
    private val tabFocus = mutableMapOf<Int, String>()
    private val nav = mutableListOf<View>()
    private val typeButtons = mutableMapOf<ContentType, View>()
    private var searchInput: View? = null
    private var searchButton: View? = null
    private val searchActions = mutableListOf<View>()
    private var historyManage: View? = null
    private var detail: Detail? = null
    private var episodeIndex = 0
    private var group = 0
    private var synopsisExpanded = false
    private var player: ExoPlayer? = null
    private var video: RemoteVideo? = null
    private var playbackReady = false
    private var pausedForLifecycle = false
    private lateinit var hud: LinearLayout
    private var persistentProgress: LinearLayout? = null
    private var persistentTime: TextView? = null
    private var persistentBar: ProgressBar? = null
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel = false
    private var playbackClock: TextClock? = null
    private var transportPlay: TextView? = null
    private var sleepButton: TextView? = null
    private var relayDialog: AlertDialog? = null
    private var pendingSeek: Long? = null
    private var playError = false
    private var lastSaved = 0L
    private var quality = ""
    private var attemptedQuality = 0
    private var currentTrack: VideoTracks.Track? = null
    private var currentRendition: Rendition? = null
    private var currentTiers: List<Rendition> = emptyList()
    private var autoDowngrades = 0
    private val stallGuard = PlaybackStallGuard()
    private var lastPlaybackDiagnosticAt = 0L
    private val tick =
        object : Runnable {
            override fun run() {
                if (!foreground || isDestroyed) return
                if (screen == "player") {
                    if (sleepTimer.poll()) stopForSleep()
                    updateClock()
                    if (hud.visibility == View.VISIBLE || library.alwaysShowProgress) updatePlaybackText()
                    maybePrefetch()
                    maybePrepareSeason()
                    updateSeasonBanner()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (android.util.Log.isLoggable("HGPlayback", android.util.Log.DEBUG) && now - lastPlaybackDiagnosticAt >= 5000) {
                        player?.let { p ->
                            android.util.Log.d("HGPlayback", "state=${p.playbackState} playing=${p.isPlaying} suppressed=${p.playbackSuppressionReason} position=${p.currentPosition} buffered=${p.totalBufferedDuration} audio=${p.audioDecoderCounters?.renderedOutputBufferCount} audioQueued=${p.audioDecoderCounters?.queuedInputBufferCount} audioMime=${p.audioFormat?.sampleMimeType} audioRate=${p.audioFormat?.sampleRate} audioChannels=${p.audioFormat?.channelCount} video=${p.videoDecoderCounters?.renderedOutputBufferCount}")
                        }
                        lastPlaybackDiagnosticAt = now
                    }
                    player?.let { p ->
                        val frames = p.videoDecoderCounters?.renderedOutputBufferCount
                        if (stallGuard.offer(
                            episodeId = detail?.episodes?.getOrNull(episodeIndex).orEmpty(),
                            now = now,
                            frames = frames,
                            eligible = playbackReady && !waitingForFirstFrame && !playError &&
                                pendingSeek == null && p.isPlaying && root.hasWindowFocus() &&
                                p.playbackState == Player.STATE_READY && p.totalBufferedDuration >= 5000,
                        )) {
                            playerFailure(PlaybackException(
                                "视频解码停滞，正在尝试兼容恢复", null,
                                PlaybackException.ERROR_CODE_DECODING_FAILED,
                            ))
                        }
                    }
                    if (player?.isPlaying == true && now - lastSaved > 5000) {
                        saveProgress()
                        lastSaved = now
                    }
                }
                if (screen != "player") stallGuard.suspendObservation()
                main.postDelayed(this, 1000)
            }
        }
    private val hideHud = Runnable {
        if (screen == "player" && player?.isPlaying == true && !playError && !waitingForFirstFrame && root.hasWindowFocus())
            hidePlaybackOverlay()
    }
    private val seekRunnable = Runnable {
        pendingSeek?.let { player?.seekTo(it) }
        pendingSeek = null
    }

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    private fun widthDp() = resources.displayMetrics.widthPixels / resources.displayMetrics.density

    private fun lp(
        w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
    ) = LinearLayout.LayoutParams(w, h)

    private fun rounded(color: Int, border: Int = Color.TRANSPARENT) =
        TvStyle.shape(this, color, border)

    private fun text(value: String, size: Float = 16f, color: Int = white) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun focusStyle(view: View, selected: Boolean = false) {
        view.id = View.generateViewId()
        view.isSelected = selected
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        fun paint(focused: Boolean) {
            view.background = TvStyle.card(this, focused, selected)
        }
        paint(false)
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    // 统一创建可点击的文字按钮；将界面样式、焦点效果与业务回调连接起来。
    private fun button(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(42)
            if (selected) setTextColor(accent)
            focusStyle(this, selected)
            setOnClickListener { onClick() }
        }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val view = button(label, selected, onClick)
        parent.addView(
            view,
            lp().apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                rightMargin = dp(8)
            },
        )
        return view
    }

    // 启动入口：创建根容器，初始化账户、更新和媒体控制，再异步读取本机记录。
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ContentRepository.deviceStore = PreferencesPlayerDeviceStore(
            getSharedPreferences("native-player-device", MODE_PRIVATE)
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        librarySources = LibrarySources(this)
        library = Library(this)
        artwork = ArtworkLoader(this)
        tvTools = TvTools(this)
        root = FrameLayout(this).apply { background = TvVisual.backdrop(this@MainActivity) }
        setContentView(root)
        CodecCapabilities.init(this)
        CodecCapabilities.logDeviceCapabilities()
        cloudAccount =
            AccountController(this) { remote ->
                openDetail(remote.series, true, cloudProgress = remote)
            }
        cloudAccount.onHistoryChanged = {
            settingsAccountStatus?.text = accountSettingsLabel()
            if (homeFeedIdentity != cloudAccount.recommendationIdentity) {
                homeFeedState.refresh(resetRecommendations = true)
                homeFeedIdentity = cloudAccount.recommendationIdentity
                if (homeFeed != null || (screen == "catalog" && tab == 0 && library.isLoaded)) showCatalog()
            }
            homeFeed?.refreshAccountLabel()
            relatedSection?.refreshLabels()
            detail?.series?.let {
                accountFavoriteButton?.text = cloudAccount.favoriteActionLabel(it.id)
            }
            if (
                screen == "detail" &&
                    relatedSection != null &&
                    cloudAccount.signedIn &&
                    cloudAccount.reservationState(false).message == "尚未读取预约记录"
            )
                cloudAccount.refreshReservations()
            if (accountHistoryScope != cloudAccount.historyScope) {
                accountHistoryScope = cloudAccount.historyScope
                listOf(3, 4, 5).forEach {
                    tabState.remove(it)
                    tabFocus.remove(it)
                }
                if (accountCatalog()) {
                    page = 1
                    catalogFocus = ""
                }
            }
            if (screen == "catalog" && accountCatalog() && library.isLoaded) {
                val focusNav = nav.any { it.hasFocus() }
                val focusSource = sourceButtons.any { it.hasFocus() }
                val focusManage = historyManage?.hasFocus() == true
                showCatalog(focusNav = focusNav)
                if (focusSource) sourceButtons.getOrNull(1)?.requestFocus()
                else if (focusManage) historyManage?.requestFocus()
                if (cloudAccount.signedIn) {
                    if (
                        tab == 5 &&
                            cloudAccount.reservationState(reservationOnline).message == "尚未读取预约记录"
                    )
                        cloudAccount.refreshReservations()
                    if (isAccountFavorites() && cloudAccount.favoriteState().message == "尚未读取账户收藏")
                        cloudAccount.refreshFavorites()
                }
            }
        }
        updater =
            AppUpdater(this) {
                foreground &&
                    library.isLoaded &&
                    screen == "catalog" &&
                    homeFeed == null &&
                    !tvTools.showing &&
                    !cloudAccount.showing
            }
        connectivity = NetworkCompat.manager(this)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24)
                connectivity.registerDefaultNetworkCallback(networkCallback)
            else
                connectivity.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback,
                )
            networkRegistered = true
        }
        favoriteMonitor = FavoriteMonitor(library, repository) { refreshFavoriteLabels() }
        mediaSession =
            TvMediaSession(
                this,
                { if (foreground && screen == "player") requestPlayback(true) },
                { if (foreground && screen == "player") requestPlayback(false) },
                { position -> if (foreground && screen == "player") seekTo(position) },
                { direction -> skipEpisode(direction) },
                { if (foreground && screen == "player") returnToDetail() },
            )
        message(base(), "正在读取本机记录…")
        loadLibrary()
    }

    private fun showLibraryError(problem: Throwable) {
        val body = base()
        error(body, problem) {
            message(base(), "正在读取本机记录…")
            loadLibrary()
        }
    }

    // 本机记录加载成功后才展示内容；失败时保留错误并在前台提供重试入口。
    private fun loadLibrary() {
        libraryLoadError = null
        library.loadAsync(
            onFailure = { problem ->
                if (!isDestroyed) {
                    libraryLoadError = problem
                    if (foreground) showLibraryError(problem)
                }
            }
        ) {
            if (!isDestroyed && !startupApplied) {
                tab = library.initialTab()
                startupApplied = true
            }
            if (!isDestroyed && foreground) {
                showCatalog(load = true)
                refreshAccountSection()
                favoriteMonitor.check()
            }
        }
    }

    // 创建页面公共容器；清除旧视图后设置边距，可按需添加标题和返回按钮。
    private fun base(title: String? = null): LinearLayout {
        root.removeAllViews()
        root.background = TvVisual.backdrop(this)
        val container = column()
        val horizontal = dp(widthDp() * .05f)
        val vertical = (resources.displayMetrics.heightPixels * .05f).toInt()
        container.setPadding(horizontal, vertical, horizontal, vertical)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        if (title != null) {
            val heading = row()
            addButton(heading, "‹ 返回") { goBack() }
            heading.addView(
                text(title, 22f).apply { setTypeface(null, Typeface.BOLD) },
                lp(0, dp(44)).apply { weight = 1f },
            )
            container.addView(heading)
        }
        return container
    }

    private fun loadCover(view: ImageView, url: String) {
        var ancestor: View? = view
        var focused = false
        while (ancestor != null && ancestor !== root) {
            if (ancestor.isFocused) {
                focused = true
                break
            }
            ancestor = ancestor.parent as? View
        }
        artwork.load(
            view,
            url,
            if (focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE,
        )
    }

    private fun cancelWork() {
        deferredWork?.discard?.invoke()
        deferredWork = null
        workScope?.cancel()
        workScope = null
        workJob?.cancel(true)
        workJob = null
        cancelPromotedPreparation()
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }

    private fun invalidatePage() {
        cancelNextSeason()
        generation++
        cancelWork()
        artwork.cancelPage()
    }

    private fun releaseCatalogViews() {
        settingsAccountStatus = null
        settingsSidebar = emptyList()
        settingsRows = emptyList()
        searchSuggestions?.close()
        searchSuggestions = null
        tvSearchPanel = null
        discoveryScreen?.close()
        discoveryScreen = null
        continuousCatalog?.let { view ->
            view.capture()?.let { continuousPositions[continuousKey] = it }
            view.close()
        }
        continuousCatalog = null
        while (continuousPositions.size > 20) continuousPositions.remove(
            continuousPositions.keys.first()
        )
        val closingFeed = homeFeed
        homeFeed = null
        closingFeed?.close()
        relatedSection = null
        accountFavoriteButton = null
        homeScreen?.let { homePosition = it.capturePosition() }
        catalogScroll?.let { scrollPositions[catalogPageKey] = it.scrollY }
        catalogBody = null
        homeScreen = null
        selectionPreview = null
        catalogScroll = null
        nav.clear()
        sourceButtons.clear()
        typeButtons.clear()
        searchInput = null
        searchButton = null
        searchActions.clear()
        historyManage = null
        rankRefresh = null
        rankSubtitle = null
        collectionBack = null
        favoriteBadges.clear()
        favoriteStatus = null
        favoriteCheck = null
        homeUpdates = null
        resumeCards.clear()
        catalogCards.clear()
        cardImages.clear()
    }

    // 后台执行耗时任务；回到主线程前校验页面代次，避免旧请求覆盖新页面。
    private fun <T> work(action: () -> T, done: (T) -> Unit, failed: (Throwable) -> Unit) {
        cancelWork()
        val ticket = generation
        val scope = RequestScope()
        workScope = scope
        workJob =
            io.submit {
                try {
                    val result = scope.run(action)
                    main.post {
                        val discard = {
                            if (result is java.io.Closeable) result.close()
                            Unit
                        }
                        if (isDestroyed || generation != ticket) discard()
                        else if (foreground) done(result)
                        else {
                            deferredWork?.discard?.invoke()
                            deferredWork = DeferredUi({ done(result) }, discard)
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        if (!isDestroyed && generation == ticket) {
                            if (foreground) failed(e) else deferredWork = DeferredUi({ failed(e) })
                        }
                    }
                }
            }
    }

    private fun message(parent: LinearLayout, label: String) {
        parent.addView(text(label, 17f, muted).apply { setPadding(0, dp(28), 0, dp(18)) })
    }

    private fun error(parent: LinearLayout, problem: Throwable, retry: () -> Unit) {
        message(parent, "暂时无法加载，请检查网络后重试。")
        val detail =
            if (problem is java.net.UnknownHostException) "无法连接内容网站"
            else if (problem is java.net.SocketTimeoutException) "连接超时"
            else problem.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(160)
        parent.addView(text(detail, 13f, muted))
        addButton(parent, "重试", onClick = retry).requestFocus()
    }

    // 导航按钮的点击入口：记录当前栏目状态，再切换到目标栏目。
    private fun switchTab(next: Int) {
        if (tab == 1) {
            saveDiscoveryDraft()
            continuousCatalog?.capture()?.let { catalogFocus = it.id }
        }
        leaveCollection()
        tabState[tab] = CatalogState(page, catalog, hasMore, ranking)
        tabFocus[tab] = catalogFocus
        tab = next
        val saved = tabState[next]
        page = saved?.page ?: 1
        catalog = saved?.items ?: emptyList()
        hasMore = saved?.hasMore ?: false
        ranking = saved?.ranking
        catalogFocus = tabFocus[next].orEmpty()
        showCatalog(load = (next <= 2 && catalog.isEmpty()))
        refreshAccountSection()
    }

    private fun switchContentType(type: ContentType) {
        if (library.contentType == type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query = it.text.toString().trim() }
        library.contentType = type
        tabState.remove(0)
        tabFocus.remove(0)
        releaseCatalogViews()
        page = 1
        catalog = emptyList()
        hasMore = false
        ranking = null
        catalogFocus = ""
        homePosition = null
        showCatalog(load = (tab == 0 || query.isNotBlank()), focusType = true)
    }

    private fun leaveCollection() {
        if (collection == null) return
        collection = null
        collectionReturn?.let {
            page = it.page
            catalog = it.items
            hasMore = it.hasMore
            ranking = it.ranking
        }
        catalogFocus = collectionFocus
        collectionReturn = null
    }

    private fun showCollection(kind: String) {
        collectionReturn = CatalogState(page, catalog, hasMore, ranking)
        collectionFocus = catalogFocus
        collection = kind
        page = 1
        catalogFocus = ""
        showCatalog()
    }

    private fun inlineHomeFilters() =
        tab == 0 && resources.configuration.fontScale <= 1.2f && widthDp() >= 1200

    private fun isAccountHistory() = tab == 4 && librarySources.accountHistory

    private fun isAccountFavorites() = tab == 3 && librarySources.accountFavorites

    private fun isLocalHistory() = tab == 4 && !librarySources.accountHistory

    private fun isLocalFavorites() = tab == 3 && !librarySources.accountFavorites

    private fun accountCatalog() = isAccountHistory() || isAccountFavorites() || tab == 5

    private fun refreshAccountSection() {
        if (isAccountHistory()) cloudAccount.refreshHistory()
        if (isAccountFavorites()) {
            cloudAccount.refreshFavorites()
            cloudAccount.refreshHistory()
        }
        if (tab == 5) {
            cloudAccount.refreshReservations()
            cloudAccount.refreshHistory()
        }
    }

    // 卡片与焦点预览共用同一集数来源，账号片单不混入本机进度。
    private fun collectionEpisodeTotal(series: Series): Int {
        val remote = isAccountFavorites() || isAccountHistory() || tab == 5
        val cloud = if (remote) cloudAccount.progress(series.id) else null
        val local = if (!remote) library.progress(series.id) else null
        val update = if (!remote) library.favoriteUpdate(series.id) else null
        return maxOf(
                EpisodeSummary.total(series.badge),
                EpisodeSummary.total(cloud?.series?.badge.orEmpty()),
                EpisodeSummary.total(local?.series?.badge.orEmpty()),
                cloud?.episodeCount ?: 0,
                update?.total ?: 0,
            )
    }

    private fun collectionPreviewSeries(series: Series): Series {
        if (tab !in 3..5 || (tab == 5 && !reservationOnline)) return series
        val total = collectionEpisodeTotal(series)
        return if (total > 0) series.copy(badge = "共 $total 集") else series
    }

    private fun collectionEpisodeLabel(series: Series): String {
        val remote = isAccountFavorites() || isAccountHistory() || tab == 5
        val cloud = if (remote) cloudAccount.progress(series.id) else null
        val local = if (!remote) library.progress(series.id) else null
        val update = if (!remote) library.favoriteUpdate(series.id) else null
        return EpisodeSummary.label(
            collectionEpisodeTotal(series),
            cloud?.episodeIndex ?: local?.episodeIndex,
            account = remote,
            ready = cloudAccount.historyReady,
            loading = cloudAccount.historyLoading,
            markedWatched = !remote && library.watched(series.id),
            pending = tab == 5 && !reservationOnline,
            added = update?.added ?: 0,
        )
    }

    private fun switchLibrarySource(account: Boolean) {
        if (tab == 3) librarySources.accountFavorites = account
        else librarySources.accountHistory = account
        page = 1
        catalogFocus = ""
        catalog = emptyList()
        tabState.remove(tab)
        tabFocus.remove(tab)
        showCatalog()
        sourceButtons.getOrNull(if (account) 1 else 0)?.requestFocus()
        refreshAccountSection()
    }

    private fun localCatalog() = tab in 3..5 || collection != null

    private fun localItems(): List<Series> =
        when (collection) {
            "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
            "later" -> library.later()
            "updates" ->
                library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0) > 0 }
            "resume" ->
                library.history().filterNot { library.watched(it.series.id) }.map { it.series }
            else ->
                when {
                    isAccountFavorites() -> cloudAccount.favoriteState().rows.map { it.series }
                    isAccountHistory() -> cloudAccount.historyRows().map { it.series }
                    tab == 5 ->
                        cloudAccount.reservationState(reservationOnline).rows.map { it.series }
                    isLocalFavorites() -> library.favorites()
                    else -> library.history().map { it.series }
                }
        }

    // 内容页总入口：构建顶部导航，再根据当前栏目挂载首页、搜索或其他列表。
    private fun showCatalog(
        load: Boolean = false,
        focusNav: Boolean = false,
        focusType: Boolean = false,
    ) {
        val loadRemote = load && !localCatalog()
        relatedSection = null
        detailTrail.clear()
        reservationFilters.clear()
        initialCatalogPending = false
        restoreStoppedPage = false
        pendingDetail = null
        // Never cache a new page number with the previous page's results while a request is
        // pending.
        if (loadRemote) {
            val cached =
                if (tab == 0 && page == 1) library.cachedHome(library.contentType) else null
            catalog = cached?.items ?: emptyList()
            hasMore = cached?.hasMore ?: false
            ranking = null
            homeFromCache = cached != null
        }
        invalidatePage()
        screen = "catalog"
        releaseCatalogViews()
        val container = base()
        if (tab == 0) container.setPadding(dp(20), dp(12), dp(20), dp(12))
        if (tab != 0 && foreground && library.isLoaded && !favoriteMonitor.running)
            main.post { if (foreground && screen == "catalog") favoriteMonitor.check() }
        val top = row().apply { setPadding(0, 0, 0, dp(8)) }
        val brand = row()
        brand.addView(
            ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_icon) },
            lp(dp(28), dp(28)).apply { rightMargin = dp(9) },
        )
        brand.addView(
            text("红果 TV", if (widthDp() < 800) 17f else 22f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        )
        top.addView(brand, lp(dp(if (widthDp() < 800) 105 else 140), dp(44)))
        listOf("首页", "找剧", "排行榜", "收藏", "观看历史", "账户预约", "设置").forEachIndexed { index, label ->
            val item = addButton(top, label, index == tab) { switchTab(index) }
            item.textSize = if (widthDp() < 1000) 13f else 15f
            item.setPadding(dp(9), dp(10), dp(9), dp(10))
            fun paint(focused: Boolean) {
                item.background = if (tab == 0) HomeGlass.panel(this, focused, index == tab) else TvVisual.tab(this, focused, index == tab)
                item.setTextColor(if (tab == 0 && (index == tab || focused)) Color.rgb(103, 211, 255) else if (index == tab) TvStyle.accent else if (focused) white else muted)
                item.setTypeface(null, if (focused || index == tab) Typeface.BOLD else Typeface.NORMAL)
            }
            paint(false)
            item.setOnFocusChangeListener { _, focused -> paint(focused) }
            nav += item
        }
        container.addView(top)
        container.addView(
            View(this).apply { setBackgroundColor(TvStyle.outline) },
            lp(-1, dp(1)).apply { bottomMargin = dp(6) },
        )
        if (tab == 6) {
            val body = column()
            container.addView(body, lp(-1, 0).apply { weight = 1f })
            settings(body)
            if (focusNav) nav[tab].requestFocus()
            return
        }
        if (tab == 0) {
            favoriteMonitor.stop()
            homeFeedIdentity = cloudAccount.recommendationIdentity
            if (cloudAccount.recommendationLoading) {
                container.addView(text("正在恢复账号推荐…", 18f, muted))
                if (focusNav) nav[0].requestFocus()
                return
            }
            val feed =
                HomeFeedScreen(
                    this,
                    homeFeedState,
                    nav.toList(),
                    { openDetail(it, false) },
                    { series, anchor ->
                        if (cloudAccount.signedIn) accountFavoriteAction(series, anchor)
                        else {
                            library.toggle(series)
                            homeFeed?.refreshAccountLabel()
                            Toast.makeText(this, if (library.favorite(series.id)) "已加入本机收藏" else "已取消本机收藏", Toast.LENGTH_SHORT).show()
                        }
                    },
                    { id -> if (cloudAccount.signedIn) cloudAccount.favoriteActionLabel(id)
                        else if (library.favorite(id)) "已收藏 · 本机" else "加入本机收藏" },
                    PublicFeedBackend(
                        library.maxQuality,
                        account = cloudAccount.recommendationCredentials(),
                        startupCache = cloudAccount.recommendationStartupCache(),
                    ),
                    Media3TrialPlayback(this, library.playbackSpeed, library.frameMode),
                    autoPreview = library.homeAutoplay,
                    loadCover = ::loadCover,
                    related = ::showHomeRelated,
                    autoNext = library.autoNext,
                    restoreProgress = library::progress,
                    saveProgress = { progress, count, force ->
                        library.save(progress)
                        cloudAccount.upload(CloudProgress(progress.series, progress.episodeId,
                            progress.episodeIndex, progress.position, progress.duration, progress.updatedAt),
                            count, force || progress.completed || !foreground)
                    },
                )
            homeFeed = feed
            container.addView(feed, lp(-1, 0).apply { weight = 1f })
            feed.start(foreground, focusNav)
            return
        }
        if (tab == 2) {
            val ranks =
                RankingScreen(
                    this,
                    rankBrowser,
                    nav,
                    tvTools,
                    ::loadCover,
                    { item -> openDetail(item, false) },
                    { item, anchor -> quickActions(item, anchor) },
                    { board, done ->
                        // 榜单加载走独立并行通道：不与全局 work() 互相取消，快速切换
                        // 多个榜单时各自的读取都会完成并写入会话缓存。
                        rankWorker.submit {
                            val result = runCatching { repository.rankSnapshot(board) }
                            main.post {
                                if (isDestroyed) return@post
                                result.fold(
                                    { done(it, null) },
                                    { done(null, it.message ?: "网络暂不可用") },
                                )
                            }
                        }
                    },
                )
            container.addView(ranks, lp(-1, 0).apply { weight = 1f })
            ranks.start(load, focusNav)
            return
        }
        if (tab == 0) {
            val types = row().apply { setPadding(0, dp(8), 0, dp(6)) }
            if (!inlineHomeFilters()) types.addView(text("片库", 12f, muted), lp(dp(42), -2))
            ContentType.entries.forEach { type ->
                typeButtons[type] =
                    addButton(types, type.label, library.contentType == type) {
                            switchContentType(type)
                        }
                        .apply {
                            isSelected = library.contentType == type
                            nextFocusUpId = nav[tab].id
                        }
            }
            if (inlineHomeFilters()) {
                top.addView(Space(this), lp(0, 1).apply { weight = 1f })
                typeButtons.values.forEach { view ->
                    (view as TextView).apply {
                        textSize = 12f
                        minHeight = dp(34)
                        setPadding(dp(12), dp(7), dp(12), dp(7))
                    }
                }
                types.setPadding(0, 0, 0, 0)
                top.addView(types)
            } else {
                if (tab == 0)
                    types.addView(
                        text("长按确认 · 管理片单", 12f, muted).apply { setPadding(dp(14), 0, 0, 0) }
                    )
                container.addView(types)
            }
            nav.forEach { it.nextFocusDownId = typeButtons.getValue(library.contentType).id }
        }
        if (tab == 1) {
            if (tvSearchOpen || query.isNotBlank()) {
                val panel =
                    TvSearchPanel(
                        this,
                        searchDraft ?: query,
                        library.searches().map { it.query },
                        nav[tab],
                        repository::searchSuggestions,
                        ::runSearch,
                        { anchor -> tvTools.phoneInput(anchor) { query, _ -> query?.let { runSearch(it) } } },
                        ::returnToDiscovery,
                    )
                tvSearchPanel = panel
                searchInput = panel.input
                searchButton = panel.searchButton
                searchSuggestions = panel.suggestions
                panel.resultEntry = { continuousCatalog?.grid?.takeIf { catalog.isNotEmpty() } }
                container.addView(panel, lp(-1, 0).apply { weight = 1f })
                nav.forEach { it.nextFocusDownId = panel.entry().id }
                if (query.isBlank()) {
                    message(panel.results, "输入关键词后选择联想词，或按「搜索」查看剧集")
                    if (focusNav) nav[tab].requestFocus() else panel.entry().requestFocus()
                    return
                }
            } else {
                val searchRow = row()
                val input =
                    EditText(this).apply {
                        id = View.generateViewId()
                        hint = "输入剧名、关键词或拼音"
                        setText(searchDraft ?: query)
                        textSize = 16f
                        setTextColor(white)
                        setHintTextColor(muted)
                        isSingleLine = true
                        maxLines = 1
                        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
                        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    }
                searchRow.addView(
                    input,
                    lp(0, dp(45)).apply {
                        weight = 1f
                        rightMargin = dp(10)
                    },
                )
                fun search() {
                    val next = input.text.toString().trim()
                    if (next.isBlank()) {
                        returnToDiscovery()
                        return
                    }
                    runSearch(next)
                }
                searchInput = input
                searchButton = addButton(searchRow, "搜索") { search() }
                input.nextFocusUpId = nav[tab].id
                searchButton?.nextFocusUpId = input.nextFocusUpId
                lateinit var recent: TextView
                recent = addButton(searchRow, "历史") { showSearchHistory(recent) }
                recent.nextFocusUpId = input.nextFocusUpId
                lateinit var phone: TextView
                phone =
                    addButton(searchRow, "手机输入") {
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(input.windowToken, 0)
                        tvTools.phoneInput(phone) { query, _ -> query?.let { runSearch(it) } }
                    }
                phone.nextFocusUpId = input.nextFocusUpId
                val keyboard =
                    addButton(searchRow, "屏幕键盘") {
                            saveDiscoveryDraft()
                            tvSearchOpen = true
                            showCatalog()
                        }
                        .apply { nextFocusUpId = input.nextFocusUpId }
                searchActions.addAll(listOf(recent, phone, keyboard))
                if (query.isNotBlank())
                    searchActions +=
                        addButton(searchRow, "返回分类") { returnToDiscovery() }
                            .apply { nextFocusUpId = input.nextFocusUpId }

                nav.forEach { it.nextFocusDownId = input.id }
                input.setOnEditorActionListener { _, _, _ ->
                    search()
                    true
                }
                container.addView(searchRow)
                searchSuggestions =
                    SearchSuggestionsView(
                            this,
                            input,
                            listOfNotNull(searchButton) + searchActions,
                            {
                                if (query.isBlank()) discoveryScreen?.entry()
                                else
                                    continuousCatalog?.grid
                                        ?: catalogBody
                                            ?.getFocusables(View.FOCUS_DOWN)
                                            ?.firstOrNull()
                            },
                            repository::searchSuggestions,
                            ::runSearch,
                        )
                        .also { container.addView(it) }
                if (query.isBlank()) {
                    val browser =
                        DiscoveryScreen(
                            this,
                            discoveryBrowser,
                            input,
                            ::loadCover,
                            { openDetail(it, false) },
                            ::quickActions,
                            { selection, cursor, done ->
                                work(
                                    { repository.filteredDiscovery(selection, cursor) },
                                    { done(it, null) },
                                    { done(null, it) },
                                )
                            },
                        )
                    discoveryScreen = browser
                    browser.navigationChanged = { searchSuggestions?.updateNavigation() }
                    container.addView(browser, lp(-1, 0).apply { weight = 1f })
                    browser.start(!focusNav)
                    searchSuggestions?.updateNavigation()
                    if (focusNav) nav[tab].requestFocus()
                    return
                }
                container.addView(
                    text("关键词搜索覆盖全部剧集 · 返回分类可组合筛选", 12f, muted).apply {
                        setPadding(0, dp(6), 0, dp(5))
                    }
                )
            }
        } else if (tab > 2) {
            val heading = row().apply { setPadding(0, dp(12), 0, dp(6)) }
            heading.addView(
                text(
                        when (tab) {
                            3 -> "我的收藏"
                            4 -> "观看历史"
                            else -> "账户预约"
                        },
                        25f,
                    )
                    .apply { setTypeface(null, Typeface.BOLD) },
                lp(0, -2).apply { weight = 1f },
            )
            if (isLocalFavorites())
                favoriteCheck =
                    addButton(heading, "检查更新") { favoriteMonitor.check(force = true) }
                        .apply { nextFocusUpId = nav[tab].id }
            if (isLocalHistory() && library.history().isNotEmpty()) {
                historyManage =
                    addButton(heading, "管理本机记录") {
                            val rows = library.history()
                            tvTools.choose(
                                "选择要管理的本机观看记录",
                                rows.map { it.series.title },
                                historyManage,
                                { index -> manageHistory(rows[index].series, historyManage) },
                                "清空本机记录",
                            ) {
                                tvTools.confirm(
                                    "清空本机观看记录",
                                    "只删除这台电视的观看进度，账户历史和本机收藏均保留。",
                                    historyManage,
                                ) {
                                    library.clearHistory()
                                    catalogFocus = ""
                                    showCatalog()
                                }
                            }
                        }
                        .apply { nextFocusUpId = nav[tab].id }
            }
            if (accountCatalog()) {
                historyManage =
                    addButton(
                            heading,
                            if (!cloudAccount.signedIn) "登录红果"
                            else
                                when {
                                    isAccountFavorites() -> "刷新收藏"
                                    isAccountHistory() -> "刷新历史"
                                    else -> "刷新预约"
                                },
                        ) {
                            if (!cloudAccount.signedIn) cloudAccount.show()
                            else refreshAccountSection()
                        }
                        .apply { nextFocusUpId = nav[tab].id }
                addButton(heading, "账号管理") { cloudAccount.show() }
                    .apply { nextFocusUpId = nav[tab].id }
            }
            container.addView(heading)
            if (tab == 3 || tab == 4) {
                val sources = row().apply { setPadding(0, dp(4), 0, dp(8)) }
                val account =
                    if (tab == 3) librarySources.accountFavorites else librarySources.accountHistory
                listOf(false, true).forEach { remote ->
                    sourceButtons +=
                        addButton(
                                sources,
                                if (tab == 3) if (remote) "账户收藏" else "本机收藏"
                                else if (remote) "账户历史" else "本机历史",
                                account == remote,
                            ) {
                                if (account != remote) switchLibrarySource(remote)
                            }
                            .apply {
                                nextFocusUpId =
                                    historyManage?.id ?: favoriteCheck?.id ?: nav[tab].id
                            }
                }
                sourceButtons.forEach { it.nextFocusDownId = it.id }
                sourceButtons[0].nextFocusLeftId = sourceButtons[0].id
                sourceButtons[0].nextFocusRightId = sourceButtons[1].id
                sourceButtons[1].nextFocusLeftId = sourceButtons[0].id
                sourceButtons[1].nextFocusRightId = sourceButtons[1].id
                container.addView(sources)
                nav.forEach { it.nextFocusDownId = sourceButtons[if (account) 1 else 0].id }
                favoriteCheck?.nextFocusDownId = sourceButtons[if (account) 1 else 0].id
                historyManage?.nextFocusDownId = sourceButtons[if (account) 1 else 0].id
                val note =
                    when {
                        isAccountFavorites() ->
                            "${cloudAccount.accountLabel} · ${cloudAccount.favoriteState().message}\n与手机红果同步；长按剧集可管理账户收藏"
                        isAccountHistory() ->
                            "${cloudAccount.accountLabel} · ${cloudAccount.historyStatus}\n显示当前账号最近 50 部云端记录，续播使用账号进度"
                        isLocalHistory() -> "只保存在这台电视，管理和清空不影响账户历史"
                        else -> "只保存在这台电视，不自动上传或合并到账户收藏"
                    }
                container.addView(text(note, 13f, muted).apply { setPadding(0, 0, 0, dp(6)) })
            }
            if (tab == 5) {
                val state = cloudAccount.reservationState(reservationOnline)
                container.addView(
                    text(
                            "${cloudAccount.accountLabel} · ${state.message}\n每类读取最近 20 部预约，以下数量为当前已加载数",
                            13f,
                            muted,
                        )
                        .apply { setPadding(0, 0, 0, dp(6)) }
                )
                val filters = row()
                for (online in listOf(true, false)) {
                    val category = cloudAccount.reservationState(online)
                    val count = if (category.loaded) category.rows.size.toString() else "—"
                    reservationFilters +=
                        addButton(
                                filters,
                                "${if(online) "已上线" else "待上线"} · $count",
                                reservationOnline == online,
                            ) {
                                reservationOnline = online
                                page = 1
                                catalogFocus = ""
                                showCatalog()
                            }
                            .apply { nextFocusUpId = historyManage!!.id }
                }
                container.addView(filters)
                historyManage?.nextFocusDownId =
                    reservationFilters[if (reservationOnline) 0 else 1].id
                nav.forEach { it.nextFocusDownId = historyManage!!.id }
            }
            if (isLocalFavorites()) {
                favoriteStatus =
                    text(favoriteMonitor.status, 13f, muted).apply { setPadding(0, 0, 0, dp(6)) }
                container.addView(favoriteStatus)
            }
        }
        val body = column()
        catalogBody = body
        (tvSearchPanel?.results ?: container).addView(body, lp(-1, 0).apply { weight = 1f })
        if (localCatalog()) {
            catalog = localItems()
            ranking = null
            page = page.coerceIn(1, maxOf(1, (catalog.size + pageSize - 1) / pageSize))
            hasMore = page * pageSize < catalog.size
        }
        if (loadRemote) {
            if (tab == 0) catalogGrid(body, focusNav, focusType, loading = true)
            else {
                message(body, "正在加载…")
                if (tvSearchPanel != null) tvSearchPanel?.entry()?.requestFocus()
                else if (focusType) typeButtons[library.contentType]?.requestFocus()
                else nav[tab].requestFocus()
            }
            val requestTab = tab
            val requestPage = page
            val requestQuery = query
            val requestType = library.contentType
            work(
                {
                    val result =
                        when (requestTab) {
                            0 -> repository.home(requestPage, requestType)
                            2 -> repository.comicRanking(requestPage)
                            else -> repository.searchAll(requestQuery, requestPage)
                        }
                    result
                },
                { result ->
                    if (requestTab == 0 && requestPage == 1)
                        library.cacheHome(requestType, result.items, result.hasMore)
                    if (
                        requestTab == 0 &&
                            requestPage == 1 &&
                            (homeFromCache || tvTools.showing || updater.showing)
                    ) {
                        homeScreen?.refresh?.text = "热门已更新 · 按确认查看"
                        homeScreen?.refresh?.setOnClickListener {
                            catalog = result.items
                            hasMore = result.hasMore
                            ranking = result.ranking
                            showCatalog()
                        }
                    } else {
                        catalog = result.items
                        hasMore = result.hasMore
                        ranking = result.ranking
                        val keepNav = nav.any { it.hasFocus() }
                        val keepType = typeButtons.values.any { it.hasFocus() }
                        val keepInput = searchInput?.hasFocus() == true
                        val keepSuggestion =
                            tvSearchPanel?.findFocus() ?: searchSuggestions?.findFocus()
                        rankSubtitle?.text =
                            ranking?.updatedText?.ifBlank { "来源：红果漫剧热播榜" } ?: "来源：红果漫剧热播榜"
                        val focusResults = submittedSearch == requestQuery &&
                            (searchInput as? TextView)?.text?.toString()?.trim() == requestQuery && !keepNav &&
                            tvSearchPanel?.entry()?.hasFocus() == true
                        body.removeAllViews()
                        catalogGrid(body, keepNav || focusNav, keepType || focusType)
                        if (submittedSearch == requestQuery) submittedSearch = null
                        if (focusResults && catalog.isNotEmpty()) continuousCatalog?.enter()
                        else if (keepInput) searchInput?.requestFocus()
                        else if (keepSuggestion != null) keepSuggestion.requestFocus()
                    }
                },
                { problem ->
                    if (requestTab == 0 && requestPage == 1) {
                        homeScreen?.refresh?.text =
                            if (catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                        homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                        return@work
                    }
                    catalog = emptyList()
                    hasMore = false
                    ranking = null
                    rankSubtitle?.text = "榜单加载失败"
                    if (problem is SearchSessionExpiredException) {
                        page = 1
                        catalogFocus = ""
                        Toast.makeText(this, "搜索结果已过期，已回到第 1 页刷新", Toast.LENGTH_LONG).show()
                        showCatalog(true)
                    } else {
                        body.removeAllViews()
                        error(body, problem) { showCatalog(true) }
                        searchSuggestions?.updateNavigation()
                        if (resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                    }
                },
            )
        } else catalogGrid(body, focusNav, focusType)
    }

    private fun catalogGrid(
        body: LinearLayout,
        focusNav: Boolean,
        focusType: Boolean = false,
        loading: Boolean = false,
    ) {
        if (tab == 0 && page == 1 && collection == null) {
            renderHome(body, focusNav, focusType, loading)
            return
        }
        resumeCards.clear()
        catalogCards.clear()
        cardImages.clear()
        collectionBack = null
        if (collection != null) {
            val heading = row()
            collectionBack =
                addButton(heading, "‹ 返回首页") {
                        leaveCollection()
                        showCatalog()
                    }
                    .apply {
                        nextFocusUpId = typeButtons[library.contentType]?.id ?: nav[tab].id
                        nextFocusDownId = id
                    }
            heading.addView(
                text(
                    when (collection) {
                        "later" -> "稍后看"
                        "updates" -> "收藏有更新"
                        "hot" -> "热门发现"
                        else -> "接着看"
                    },
                    22f,
                )
            )
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId = collectionBack!!.id }
        }
        catalogPageKey =
            "$tab:${collection.orEmpty()}:$page:$query:${if(accountCatalog()) cloudAccount.historyScope else 0}:$reservationOnline:${if(tab==3) librarySources.accountFavorites else if(tab==4) librarySources.accountHistory else false}"
        if (tab == 1 && collection == null) {
            renderContinuousDiscovery(body, focusNav, focusType)
            return
        }
        val preview = SeriesPreview(this)
        selectionPreview = preview
        body.addView(preview)
        val scroll =
            ScrollView(this).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                clipToPadding = false
            }
        catalogScroll = scroll
        val list = column()
        scroll.addView(list)
        body.addView(scroll, lp(-1, 0).apply { weight = 1f })
        var restoringScroll = true
        val displayed =
            if (localCatalog()) catalog.drop((page - 1) * pageSize).take(pageSize)
            else if (tab == 0) catalog.filterNot { it.id in library.hidden() } else catalog.take(30)
        if (displayed.isEmpty()) {
            val empty =
                when {
                    tab == 1 -> if (query.isBlank()) "该分类暂无剧集，可切换题材或输入关键词搜索" else "没有找到相关剧集，换个关键词试试"
                    isAccountFavorites() ->
                        if (!cloudAccount.signedIn) "登录红果后查看账户收藏，本机收藏仍可使用"
                        else if (cloudAccount.favoriteState().loading) "正在读取账户收藏…"
                        else if (!cloudAccount.favoriteState().loaded) "账户收藏读取失败，请按上方刷新重试"
                        else "暂无已读取的账户收藏，可在手机收藏后刷新"
                    isLocalFavorites() -> "在剧集详情中加入本机收藏，喜欢的剧就会出现在这里"
                    isAccountHistory() ->
                        if (!cloudAccount.signedIn) "登录红果后查看账户历史，本机历史仍可使用"
                        else if (cloudAccount.historyLoading) "正在读取账户观看历史…"
                        else if (!cloudAccount.historyReady) "账户历史读取失败，请按上方刷新重试"
                        else "当前账号暂无云端观看记录"
                    isLocalHistory() -> "在这台电视播放过的剧集会自动保存在这里"
                    tab == 5 ->
                        if (!cloudAccount.signedIn) "登录红果账号后，在这里同步手机预约"
                        else if (cloudAccount.reservationState(reservationOnline).loading)
                            "正在读取预约记录…"
                        else if (!cloudAccount.reservationState(reservationOnline).loaded)
                            "预约读取失败，请按上方刷新预约重试"
                        else if (reservationOnline) "当前没有已上线的预约" else "当前没有待上线的预约"
                    else -> "本页没有更多内容"
                }
            preview.visibility = View.GONE
            message(list, if (loading) "正在加载推荐…" else empty)
            if (!loading && (isLocalHistory() || isLocalFavorites())) {
                addButton(list, "去首页看看") { switchTab(0) }
            }
            if ((tab <= 2 || localCatalog()) && page > 1)
                addButton(list, "上一页") {
                    page--
                    showCatalog(!localCatalog())
                }
            if ((tab <= 2 || localCatalog()) && hasMore)
                addButton(list, "下一页") {
                    page++
                    showCatalog(!localCatalog())
                }
            if (resumeCards.isNotEmpty()) {
                typeButtons.values.forEach { it.nextFocusDownId = resumeCards.first().id }
                resumeCards.forEach { it.nextFocusDownId = it.id }
            }
            if (focusType) typeButtons[library.contentType]?.requestFocus()
            else if (collectionBack != null && !focusNav) collectionBack?.requestFocus()
            else if (sourceButtons.isNotEmpty() && !focusNav)
                sourceButtons[if (isAccountHistory() || isAccountFavorites()) 1 else 0]
                    .requestFocus()
            else if (focusNav || resumeCards.isEmpty()) nav[tab].requestFocus()
            else resumeCards.first().requestFocus()
            return
        }
        val cards = mutableListOf<View>()
        val count = 6
        val gap = dp(14)
        val width =
            ((resources.displayMetrics.widthPixels * .9f - gap * (count - 1)) / count).toInt()
        displayed.chunked(count).forEach { items ->
            val line = row()
            line.gravity = Gravity.TOP
            items.forEachIndexed { columnIndex, item ->
                val card = column()
                card.setPadding(dp(4), dp(4), dp(4), dp(9))
                focusStyle(card)
                card.contentDescription = item.title
                card.tag = item.id
                catalogCards[item.id] = card
                val position = if (tab == 2) ranking?.positions?.get(item.id) else null
                val artwork = FrameLayout(this)
                card.addView(artwork, lp(-1, ((width - dp(8)) / TvStyle.POSTER_ASPECT).toInt()))
                val image =
                    ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
                loadCover(image, item.cover)
                cardImages[item.id] = image to item.cover
                if (tab == 2) {
                    val rankLabel = position?.rank?.let { "第 $it 名" } ?: "名次暂无"
                    artwork.addView(
                        text(rankLabel, 15f).apply {
                            setTypeface(null, Typeface.BOLD)
                            setPadding(dp(8), dp(5), dp(8), dp(5))
                            background =
                                rounded(
                                    if ((position?.rank ?: Int.MAX_VALUE) <= 3) accent
                                    else Color.rgb(28, 31, 39)
                                )
                        },
                        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START),
                    )
                    card.contentDescription =
                        "$rankLabel，${item.title}，${position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
                }
                card.addView(
                    text(item.title, 14f).apply {
                        maxLines = 2
                        minLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(3), dp(7), dp(3), 0)
                    },
                    lp(-1, -2).apply { weight = 1f },
                )
                val cloud = if (isAccountHistory()) cloudAccount.progress(item.id) else null
                val progress =
                    cloud?.let {
                        WatchProgress(
                            it.series,
                            it.episodeId,
                            it.episodeIndex,
                            it.position,
                            it.duration,
                            false,
                            it.updatedAt,
                        )
                    }
                        ?: if (isLocalHistory() || collection == "resume") library.progress(item.id)
                        else null
                val summary =
                    when {
                        tab == 5 -> collectionEpisodeLabel(item) + "\n" + selectionStatus(item)
                        tab == 3 -> collectionEpisodeLabel(item)
                        tab == 4 ->
                            collectionEpisodeLabel(item) +
                                (progress?.let { " · ${formatTime(it.position)}" } ?: "")
                        tab == 2 -> position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"
                        else -> item.badge
                    }
                val badge =
                    text(summary, 12f, if (tab == 2 || isLocalFavorites()) accent else muted)
                        .apply {
                            maxLines = if (tab == 5) 3 else if (tab == 3 || tab == 4) 2 else 1
                            minLines = maxLines
                            minHeight =
                                dp(if (tab == 5) 50 else if (tab == 3 || tab == 4) 34 else 19)
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(dp(3), 0, 0, 0)
                        }
                card.addView(badge, lp(-1, -2))
                if (isLocalFavorites()) favoriteBadges[item.id] = badge
                card.setOnClickListener {
                    if (reservationNotice(item, card)) return@setOnClickListener
                    catalogFocus = item.id
                    openDetail(
                        item,
                        isLocalHistory() ||
                            isAccountHistory() ||
                            collection == "resume" ||
                            collection == "updates",
                        cloudProgress =
                            if (isAccountHistory()) cloudAccount.progress(item.id) else null,
                    )
                }
                card.setOnLongClickListener {
                    catalogFocus = item.id
                    quickActions(item, card)
                    true
                }
                card.setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_MENU) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            catalogFocus = item.id
                            quickActions(item, card)
                        }
                        true
                    } else false
                }
                card.setOnFocusChangeListener { _, focused ->
                    card.background = TvStyle.card(this, focused)
                    if (focused) {
                        catalogFocus = item.id
                        loadCover(image, item.cover)
                        preview.show(collectionPreviewSeries(item), selectionStatus(item))
                        if (!restoringScroll)
                            scroll.post {
                                scroll.smoothScrollTo(
                                    0,
                                    when {
                                        line.top < scroll.scrollY -> line.top
                                        line.bottom > scroll.scrollY + scroll.height ->
                                            (line.bottom - scroll.height).coerceAtLeast(0)
                                        else -> scroll.scrollY
                                    },
                                )
                            }
                    }
                }
                // The row measures its tallest card, then stretches siblings to keep badges
                // aligned.
                line.addView(
                    card,
                    lp(width, -1).apply { if (columnIndex < count - 1) rightMargin = gap },
                )
                cards += card
            }
            list.addView(line, lp(-1, -2).apply { bottomMargin = dp(10) })
        }
        val paging = row()
        paging.gravity = Gravity.CENTER
        var prev: View? = null
        var next: View? = null
        if (tab <= 2 || localCatalog()) {
            prev =
                addButton(paging, "上一页") {
                        if (page > 1) {
                            page--
                            catalogFocus = ""
                            showCatalog(!localCatalog())
                        }
                    }
                    .apply {
                        isEnabled = page > 1
                        isFocusable = page > 1
                        alpha = if (page > 1) 1f else .4f
                    }
            paging.addView(
                text(
                        if (localCatalog())
                            "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页"
                        else if (tab == 2 && ranking != null) "第 $page / ${ranking!!.totalPages} 页"
                        else "第 $page 页",
                        14f,
                        muted,
                    )
                    .apply { gravity = Gravity.CENTER },
                lp(dp(if (tab == 2) 135 else 95), dp(44)),
            )
            next =
                addButton(paging, "下一页") {
                        if (hasMore) {
                            page++
                            catalogFocus = ""
                            showCatalog(!localCatalog())
                        }
                    }
                    .apply {
                        isEnabled = hasMore
                        isFocusable = hasMore
                        alpha = if (hasMore) 1f else .4f
                    }
            if (collection == "hot" && !hasMore && collectionReturn?.hasMore == true) {
                next =
                    addButton(paging, "下一批热门") {
                        collection = null
                        collectionReturn = null
                        page = 2
                        catalogFocus = ""
                        showCatalog(true)
                    }
            }
            list.addView(paging, lp(-1, dp(50)))
        }
        cards.forEachIndexed { index, card ->
            card.nextFocusLeftId = if (index % count == 0) card.id else cards[index - 1].id
            card.nextFocusRightId =
                if (index % count == count - 1 || index == cards.lastIndex) card.id
                else cards[index + 1].id
            card.nextFocusUpId =
                if (index < count)
                    (searchInput?.id
                        ?: sourceButtons
                            .getOrNull(if (isAccountHistory() || isAccountFavorites()) 1 else 0)
                            ?.id
                        ?: collectionBack?.id
                        ?: reservationFilters.getOrNull(if (reservationOnline) 0 else 1)?.id
                        ?: typeButtons[library.contentType]?.id
                        ?: rankRefresh?.id
                        ?: favoriteCheck?.id
                        ?: historyManage?.id
                        ?: resumeCards.getOrNull(minOf(index, 2))?.id
                        ?: nav[tab].id)
                else cards[index - count].id
            card.nextFocusDownId =
                if (index + count < cards.size) cards[index + count].id
                else if (index / count < cards.lastIndex / count) cards.last().id
                else
                    next?.takeIf { it.isFocusable }?.id
                        ?: prev?.takeIf { it.isFocusable }?.id
                        ?: card.id
        }
        nav.forEach {
            it.nextFocusDownId =
                searchInput?.id
                    ?: typeButtons[library.contentType]?.id
                    ?: rankRefresh?.id
                    ?: favoriteCheck?.id
                    ?: historyManage?.id
                    ?: cards.first().id
        }
        rankRefresh?.nextFocusDownId = cards.first().id
        historyManage?.nextFocusDownId =
            reservationFilters.getOrNull(if (reservationOnline) 0 else 1)?.id ?: cards.first().id
        reservationFilters.forEach { it.nextFocusDownId = cards.first().id }
        favoriteCheck?.nextFocusDownId = cards.first().id
        collectionBack?.nextFocusDownId = cards.first().id
        resumeCards.forEachIndexed { index, v ->
            v.nextFocusDownId = cards[minOf(index, cards.lastIndex)].id
        }
        if (resumeCards.isNotEmpty())
            cards.take(count).forEachIndexed { index, v ->
                v.nextFocusUpId = resumeCards[minOf(index, resumeCards.lastIndex)].id
            }
        else
            homeUpdates?.let { updates ->
                updates.nextFocusUpId = typeButtons[library.contentType]?.id ?: nav[0].id
                updates.nextFocusDownId = cards.first().id
                cards.take(count).forEach { it.nextFocusUpId = updates.id }
            }
        typeButtons.values.forEach {
            it.nextFocusDownId =
                searchInput?.id
                    ?: collectionBack?.id
                    ?: resumeCards.firstOrNull()?.id
                    ?: homeUpdates?.id
                    ?: cards.first().id
        }
        if (sourceButtons.isNotEmpty()) {
            val selected = sourceButtons[if (isAccountHistory() || isAccountFavorites()) 1 else 0]
            nav.forEach { it.nextFocusDownId = selected.id }
            sourceButtons.forEach { it.nextFocusDownId = cards.first().id }
            favoriteCheck?.nextFocusDownId = selected.id
            historyManage?.nextFocusDownId = selected.id
        }
        searchInput?.nextFocusDownId = cards.first().id
        searchButton?.nextFocusDownId = cards[minOf(4, cards.lastIndex)].id
        searchActions.forEach { it.nextFocusDownId = cards[minOf(4, cards.lastIndex)].id }
        if (focusType) typeButtons[library.contentType]?.requestFocus()
        else if (focusNav) nav[tab].requestFocus()
        else if (
            resumeCards.isNotEmpty() &&
                (catalogFocus.isEmpty() || catalogFocus.startsWith("resume:"))
        )
            (resumeCards.firstOrNull { it.tag == catalogFocus } ?: resumeCards.first())
                .requestFocus()
        else cards[displayed.indexOfFirst { it.id == catalogFocus }.coerceAtLeast(0)].requestFocus()
        val savedScroll = scrollPositions[catalogPageKey]
        scroll.post {
            if (catalogScroll !== scroll) return@post
            cards.forEach { view ->
                val card = view as LinearLayout
                val image = card.getChildAt(0)
                val textHeight =
                    card.paddingTop +
                        card.paddingBottom +
                        (1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target =
                    minOf(
                        ((width - dp(8)) / TvStyle.POSTER_ASPECT).toInt(),
                        (scroll.height - textHeight - dp(4)).coerceAtLeast(dp(48)),
                    )
                if (image.layoutParams.height != target)
                    image.layoutParams = image.layoutParams.apply { height = target }
            }
            scroll.post settle@{
                if (catalogScroll !== scroll) return@settle
                savedScroll?.let { scroll.scrollTo(0, it) }
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val y =
                        when {
                            line.top < scroll.scrollY -> line.top
                            line.bottom > scroll.scrollY + scroll.height ->
                                if (line.height >= scroll.height) line.top
                                else line.bottom - scroll.height
                            else -> scroll.scrollY
                        }
                    scroll.scrollTo(0, y.coerceAtLeast(0))
                }
                restoringScroll = false
            }
        }
    }

    private fun renderContinuousDiscovery(
        body: LinearLayout,
        focusNav: Boolean,
        focusType: Boolean,
    ) {
        val requestQuery = query
        continuousKey = requestQuery
        val upper = searchInput ?: nav[tab]
        lateinit var browser: ContinuousCatalogView
        browser =
            ContinuousCatalogView(
                this,
                if (tvSearchPanel != null) {
                    if (widthDp() < 800) 2 else if (widthDp() < 1200) 3 else 4
                } else 6,
                upper,
                { item, recycled ->
                    val card = recycled as? CatalogPosterCard ?: CatalogPosterCard(this)
                    card.show(item)
                    loadCover(card.image, item.cover)
                    card
                },
                { item -> catalogFocus = item.id },
                { item ->
                    catalogFocus = item.id
                    openDetail(item, false)
                },
                { item, anchor ->
                    catalogFocus = item.id
                    quickActions(item, anchor)
                },
                {
                    val nextPage = page + 1
                    if (nextPage > 100) {
                        hasMore = false
                        browser.append(emptyList(), false)
                    } else
                        work(
                            { repository.searchAll(requestQuery, nextPage) },
                            { result ->
                                if (continuousCatalog === browser) {
                                    val seen = catalog.mapTo(HashSet()) { it.id }
                                    catalog = catalog + result.items.filter { seen.add(it.id) }
                                    page = nextPage
                                    hasMore = result.hasMore && page < 100
                                    browser.append(result.items, hasMore)
                                }
                            },
                            { problem ->
                                if (continuousCatalog === browser) {
                                    browser.fail(problem)
                                    if (problem is SearchSessionExpiredException)
                                        tvTools.confirm(
                                            "搜索结果已过期",
                                            "当前列表已保留，是否重新搜索？",
                                            browser.next,
                                        ) {
                                            runSearch(requestQuery)
                                        }
                                }
                            },
                        )
                },
            )
        continuousCatalog = browser
        body.addView(browser, lp(-1, 0).apply { weight = 1f })
        val position =
            catalogFocus
                .takeIf { it.isNotBlank() }
                ?.let { id ->
                    continuousPositions[continuousKey]?.takeIf { it.id == id }
                        ?: ContinuousCatalogView.Position(id, 0)
                }
        browser.show(catalog, hasMore && page < 100, position)
        nav.forEach { it.nextFocusDownId = searchInput?.id ?: upper.id }
        searchInput?.nextFocusDownId = browser.grid.id
        searchButton?.nextFocusDownId = browser.grid.id
        searchActions.forEach { it.nextFocusDownId = browser.grid.id }
        searchSuggestions?.updateNavigation()
        tvSearchPanel?.let { panel ->
            panel.updateNavigation()
            browser.setSideEntry(panel.middleEntry() ?: panel.entry())
            nav.forEach { it.nextFocusDownId = panel.entry().id }
        }
        if (focusNav) nav[tab].requestFocus()
        else if (focusType) upper.requestFocus() else browser.enter()
    }

    private fun reservationNotice(series: Series, anchor: View): Boolean {
        if (tab != 5) return false
        val row =
            cloudAccount.reservationState(reservationOnline).rows.firstOrNull {
                it.series.id == series.id
            } ?: return true
        if (row.online) return false
        tvTools.info(series.title, reservationDescription(row) + "\n上线后可在「已上线」中打开观看。", anchor)
        return true
    }

    /** Homepage overlay reuses the account reservation flow without changing detail pages. */
    private fun showHomeRelated(series: Series, anchor: View) {
        fun stillCurrent() = screen == "catalog" && tab == 0 && homeFeedState.current?.id == series.id
        Toast.makeText(this, "正在读取相关作品…", Toast.LENGTH_SHORT).show()
        work(
            { RelatedWorks.ordered(series, repository.related(series.id)) },
            { rows ->
                if (stillCurrent()) {
                    if (rows.isEmpty()) tvTools.info("相关剧集", "暂无相关剧集或可预约作品", anchor)
                    else tvTools.choose(
                        "${series.title} · 相关剧集",
                        rows.map { "${it.series.title} · ${if (it.upcoming) cloudAccount.reservationLabel(it) else "查看剧集"}" },
                        anchor,
                        { index ->
                            val chosen = rows[index]
                            if (!chosen.upcoming) openDetail(chosen.series, false)
                            else if (!cloudAccount.signedIn) cloudAccount.show()
                            else {
                                val accountScope = cloudAccount.historyScope
                                cloudAccount.reserve(chosen) { message ->
                                    if (stillCurrent() && accountScope == cloudAccount.historyScope)
                                        tvTools.info("预约状态", message, anchor)
                                }
                            }
                        },
                    )
                }
            },
            { if (stillCurrent()) tvTools.choose("相关剧集读取失败", listOf("重试"), anchor, { showHomeRelated(series, anchor) }) },
        )
    }

    private fun reservationDescription(row: CloudReservation): String {
        if (row.online) return "已上线 · 可观看"
        if (row.publishText.isNotBlank()) return "待上线 · ${row.publishText}"
        return if (row.publishAt > 0)
            "待上线 · 预计 " +
                java.text
                    .SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(row.publishAt))
        else "待上线 · 上线时间暂未公布"
    }

    private fun selectionStatus(series: Series): String {
        if (tab == 5)
            return cloudAccount
                .reservationState(reservationOnline)
                .rows
                .firstOrNull { it.series.id == series.id }
                ?.let(::reservationDescription)
                .orEmpty()
        if (isAccountHistory())
            return cloudAccount
                .progress(series.id)
                ?.let { "账号进度 · 第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }
                .orEmpty()
        val progress = library.progress(series.id)
        return listOf(
                if (library.favorite(series.id)) "已在本机收藏" else "",
                if (library.queued(series.id)) "稍后看" else "",
                if (library.watched(series.id)) "整剧已看完"
                else
                    progress
                        ?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }
                        .orEmpty(),
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun renderHome(
        body: LinearLayout,
        focusNav: Boolean,
        focusType: Boolean,
        loading: Boolean,
    ) {
        homeUpdateCount = library.updatedFavorites()
        val preview = SeriesPreview(this)
        selectionPreview = preview
        body.addView(preview)
        val top = if (inlineHomeFilters()) nav[0] else typeButtons.getValue(library.contentType)
        val home =
            HomeScreen(
                this,
                top,
                ::loadCover,
                { item, label ->
                    preview.show(
                        item,
                        if (label.startsWith("第 ")) selectionStatus(item)
                        else
                            listOf(label, selectionStatus(item))
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                    )
                },
                { item, resume -> openDetail(item, resume) },
                ::quickActions,
                { catalogFocus = it },
            )
        homeScreen = home
        body.addView(home, lp(-1, 0).apply { weight = 1f })
        val hot = catalog.filterNot { it.id in library.hidden() }
        home.render(
            homeShelves(),
            catalogFocus,
            !focusNav && !focusType,
            if (hasMore) {
                {
                    page = 2
                    catalogFocus = ""
                    showCatalog(true)
                }
            } else null,
            homePosition,
        )
        home.refresh.text =
            if (loading) if (homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId = home.firstId() }
        if (inlineHomeFilters()) nav.forEach { it.nextFocusDownId = home.firstId() }
        if (focusNav) nav[0].requestFocus()
        else if (focusType) typeButtons.getValue(library.contentType).requestFocus()
        if (hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged = true
            body.post {
                android.util.Log.i(
                    "HongguoTV",
                    "Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}",
                )
            }
        }
        if (launchStarted > 0) {
            val started = launchStarted
            launchStarted = 0
            body.post {
                android.util.Log.i(
                    "HongguoTV",
                    "Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}",
                )
            }
        }
    }

    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent = library.history().filterNot { library.watched(it.series.id) }
        val updates = library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0) > 0 }
        val later = library.later()
        val hidden = library.hidden()
        val hot = catalog.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf(
                "resume",
                "接着看",
                recent.take(8).map { p ->
                    HomeScreen.Entry(
                        p.series,
                        "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",
                        true,
                        if (p.duration > 0) (p.position * 100 / p.duration).toInt().coerceIn(0, 100)
                        else null,
                    )
                },
                if (recent.size > 8) {
                    { showCollection("resume") }
                } else null,
            ),
            HomeScreen.Shelf(
                "updates",
                "收藏有更新",
                updates.take(8).map { HomeScreen.Entry(it, library.favoriteLabel(it.id), true) },
                if (updates.size > 8) {
                    { showCollection("updates") }
                } else null,
            ),
            HomeScreen.Shelf(
                "later",
                "稍后看",
                later.take(10).map { HomeScreen.Entry(it, "稍后看 · 长按管理") },
                if (later.size > 10) {
                    { showCollection("later") }
                } else null,
            ),
            HomeScreen.Shelf(
                "hot",
                "热门发现 · ${library.contentType.label}",
                hot.take(10).map { HomeScreen.Entry(it, it.badge.ifBlank { "查看剧集" }) },
                if (hot.size > 10) {
                    { showCollection("hot") }
                } else null,
            ),
        )
    }

    private fun refreshLibraryCards(changedId: String, removed: Boolean = false) {
        if (screen != "catalog") return
        val home = homeScreen
        if (home != null) {
            home.updateShelves(homeShelves(), requestFocus = true)
            home.refreshSelection()
            return
        }
        if (removed && (localCatalog() || tab == 0)) {
            val oldIds = catalogCards.keys.toList()
            val index = oldIds.indexOf(changedId).coerceAtLeast(0)
            if (localCatalog()) {
                catalog = localItems()
                page = page.coerceIn(1, maxOf(1, (catalog.size + pageSize - 1) / pageSize))
                hasMore = page * pageSize < catalog.size
            }
            val remaining =
                if (localCatalog()) catalog.drop((page - 1) * pageSize).take(pageSize)
                else catalog.filterNot { it.id in library.hidden() }
            catalogFocus =
                remaining
                    .getOrNull(index.coerceAtMost((remaining.size - 1).coerceAtLeast(0)))
                    ?.id
                    .orEmpty()
            val y = catalogScroll?.scrollY ?: 0
            scrollPositions[catalogPageKey] = y
            artwork.cancelPage()
            favoriteBadges.clear()
            catalogBody?.let { body ->
                body.removeAllViews()
                catalogGrid(body, false)
            }
        } else {
            catalog
                .firstOrNull { it.id == changedId }
                ?.let { selectionPreview?.show(collectionPreviewSeries(it), selectionStatus(it)) }
            library
                .favorites()
                .firstOrNull { it.id == changedId }
                ?.let { favoriteBadges[changedId]?.text = collectionEpisodeLabel(it) }
        }
    }

    private fun quickActions(series: Series, anchor: View) {
        if (reservationNotice(series, anchor)) return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions +=
            (if (
                library.progress(series.id) != null ||
                    isAccountHistory() && cloudAccount.progress(series.id) != null
            )
                "继续观看"
            else "直接播放") to
                {
                    openDetail(
                        series,
                        true,
                        cloudProgress =
                            if (isAccountHistory()) cloudAccount.progress(series.id) else null,
                    )
                }
        actions += "查看剧集详情" to { openDetail(series) }
        actions +=
            cloudAccount.favoriteActionLabel(series.id) to { accountFavoriteAction(series, anchor) }
        actions +=
            (if (library.favorite(series.id)) "取消本机收藏" else "加入本机收藏") to
                {
                    val added = library.toggle(series)
                    Toast.makeText(this, if (added) "已加入本机收藏" else "已取消本机收藏", Toast.LENGTH_SHORT)
                        .show()
                    refreshLibraryCards(
                        series.id,
                        !added && (isLocalFavorites() || collection == "updates"),
                    )
                    if (added) favoriteMonitor.check()
                }
        actions += "查看完整标题" to { tvTools.info("完整标题", series.title, anchor) }
        if (isLocalHistory()) actions += "管理观看记录" to { manageHistory(series, anchor) }
        tvTools.choose(
            series.title,
            actions.map { it.first },
            anchor,
            { actions[it].second() },
            compact = true,
        )
    }

    private fun accountFavoriteAction(series: Series, anchor: View?) {
        if (!cloudAccount.signedIn) {
            cloudAccount.show()
            return
        }
        if (cloudAccount.favoriteState().loading) {
            Toast.makeText(this, "账户收藏正在处理，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (cloudAccount.favoriteNeedsRefresh(series.id)) {
            cloudAccount.refreshFavorites()
            return
        }
        val add = !cloudAccount.accountFavorite(series.id)
        val scope = cloudAccount.historyScope
        tvTools.confirm(
            if (add) "加入账户收藏" else "取消账户收藏",
            "${if(add) "将《${series.title}》加入" else "将《${series.title}》从"}${cloudAccount.accountLabel}的红果收藏${if(add) "，手机端也会同步显示" else "中移除"}。本机收藏保持独立。",
            anchor,
        ) {
            if (scope != cloudAccount.historyScope || !cloudAccount.signedIn) return@confirm
            cloudAccount.changeFavorite(series, add) { message ->
                tvTools.info("账户收藏", message, anchor)
            }
        }
    }

    private fun refreshFavoriteLabels() {
        if (screen != "catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id, label) ->
            val value =
                library.favorites().firstOrNull { it.id == id }?.let(::collectionEpisodeLabel)
                    ?: library.favoriteLabel(id)
            if (label.text.toString() != value) label.text = value
        }
        favoriteStatus?.text = favoriteMonitor.status
        favoriteCheck?.text = if (favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text = "收藏更新 ${library.updatedFavorites()} 部"
        val home = homeScreen
        if (
            screen == "catalog" &&
                tab == 0 &&
                page == 1 &&
                collection == null &&
                home != null &&
                library.updatedFavorites() != homeUpdateCount
        ) {
            // A stable resume key can be restored without moving the user's selection.
            if (
                !tvTools.showing &&
                    !updater.showing &&
                    currentFocus?.tag?.toString()?.startsWith("resume:") == true
            ) {
                homeUpdateCount = library.updatedFavorites()
                home.updateShelves(homeShelves())
            } else {
                home.refresh.text = "收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }

    private fun accountSettingsLabel() =
        if (cloudAccount.signedIn) "当前已登录红果账户" else "当前未登录，可继续使用本机收藏与历史"

    // 分类与内容并列显示；焦点位置仅在当前应用会话内记忆。
    private fun settings(parent: LinearLayout) {
        parent.addView(Space(this), lp(1, dp(20)))
        val layout = row().apply { gravity = Gravity.TOP }
        parent.addView(layout, lp(-1, 0).apply { weight = 1f })
        val sidebar = column()
        layout.addView(sidebar, lp(dp(170), -1).apply { rightMargin = dp(22) })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = false }
        val content = column().apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = TvVisual.panel(this@MainActivity)
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        layout.addView(scroll, lp(0, -1).apply { weight = 1f })
        val categories = listOf("播放", "外观与操作", "账户", "应用与更新")
        val entries = mutableListOf<View>()
        fun setting(label: String, action: () -> Unit): TextView {
            val view = SettingsValueView(this).apply {
                text = label
                textSize = 16f
                minimumHeight = dp(46)
                focusStyle(this)
                setPadding(dp(20), dp(8), dp(18), dp(8))
                background = TvVisual.row(this@MainActivity, false)
                setOnFocusChangeListener { _, focused ->
                    background = TvVisual.row(this@MainActivity, focused)
                    invalidate()
                }
                setOnClickListener { action() }
            }
            content.addView(view, lp(-1, -2).apply { bottomMargin = dp(4) })
            entries += view
            return view
        }
        fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
            val view = row().apply {
                focusStyle(this)
                setPadding(dp(20), dp(11), dp(14), dp(11))
                minimumHeight = dp(46)
                background = TvVisual.row(this@MainActivity, false)
                setOnFocusChangeListener { _, focused -> background = TvVisual.row(this@MainActivity, focused) }
            }
            view.addView(text(label, 15f), lp(0, -2).apply { weight = 1f })
            val control = SettingsSwitch(this).apply {
                isChecked = checked
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (android.os.Build.VERSION.SDK_INT >= 23) thumbTintList = android.content.res.ColorStateList.valueOf(white)
                if (android.os.Build.VERSION.SDK_INT >= 23) trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, TvStyle.outline))
            }
            view.addView(control)
            view.setOnFocusChangeListener { _, focused ->
                view.background = TvVisual.row(this@MainActivity, focused)
                (view.getChildAt(0) as? TextView)?.setTextColor(if (focused) Color.WHITE else TvVisual.primary)
                control.rowFocused = focused
            }
            fun describe() { view.contentDescription = "$label，${if (control.isChecked) "开启" else "关闭"}，按确定切换" }
            describe()
            view.setOnClickListener { control.isChecked = !control.isChecked; change(control.isChecked); describe() }
            content.addView(view, lp(-1, -2).apply { bottomMargin = dp(8) })
            entries += view
        }
        lateinit var render: (Int) -> Unit
        val buttons = categories.mapIndexed { index, title ->
            button(title, onClick = {
                if (settingsGroup != index) render(index)
                settingsRows.getOrNull(settingsPositions[index].coerceAtMost(settingsRows.lastIndex))?.requestFocus()
            }).apply {
                tag = "settings-category-$index"
                minHeight = dp(52)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), dp(10), dp(12), dp(10))
                sidebar.addView(this, lp(-1, -2).apply { bottomMargin = dp(8) })
                setOnFocusChangeListener { _, focused ->
                    if (focused) render(index)
                    background = TvVisual.row(this@MainActivity, focused, settingsGroup == index, category = true)
                    setTextColor(if (focused || settingsGroup == index) TvVisual.focusedValue else muted)
                }
            }
        }
        settingsSidebar = buttons
        render = { index ->
            settingsGroup = index
            entries.clear()
            settingsAccountStatus = null
            content.removeAllViews()
            scroll.scrollTo(0, 0)
            content.addView(text(categories[index], 20f).apply { setTypeface(null, Typeface.BOLD) })
            content.addView(View(this).apply { setBackgroundColor(Color.argb(18, 255, 255, 255)) }, lp(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(10) })
            when (index) {
                0 -> {
                    lateinit var qualityButton: TextView
                    qualityButton = setting("默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton, false) }
                    lateinit var speed: TextView
                    speed = setting("默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(speed, false) }
                    lateinit var frame: TextView
                    frame = setting("画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame, false) }
                    lateinit var rotationButton: TextView
                    rotationButton = setting("画面旋转  ·  ${library.videoRotation}°") { showRotationPicker(rotationButton) }
                    toggle("常驻播放进度", library.alwaysShowProgress) { library.alwaysShowProgress = it }
                    toggle("自动连播", library.autoNext) { library.autoNext = it }
                    toggle("跨季自动连播（需开启自动连播）", library.seasonAutoplay) { library.seasonAutoplay = it }
                }
                1 -> {
                    val labels = listOf("首页", "找剧", "排行榜", "收藏", "观看历史", "账户预约", "设置")
                    lateinit var startup: TextView
                    startup = setting("启动页面  ·  ${labels[library.startupPage]}") {
                        tvTools.choose("启动页面", labels, startup, { choice ->
                            library.startupPage = choice
                            startup.text = "启动页面  ·  ${labels[choice]}"
                        }, selected = library.startupPage)
                    }
                    toggle("首页自动播放", library.homeAutoplay) { library.homeAutoplay = it }
                    val clocks = listOf("关闭", "操作时显示", "始终显示")
                    lateinit var clock: TextView
                    clock = setting("播放时钟  ·  ${clocks[library.clockMode]}") {
                        tvTools.choose("播放时钟", clocks, clock, { choice ->
                            library.clockMode = choice
                            clock.text = "播放时钟  ·  ${clocks[choice]}"
                        }, selected = library.clockMode)
                    }
                }
                2 -> {
                    content.addView(text(accountSettingsLabel(), 15f, muted).apply {
                        settingsAccountStatus = this
                        setPadding(0, 0, 0, dp(14))
                    })
                    setting("登录与账户管理") { cloudAccount.show() }
                }
                3 -> {
                    lateinit var updates: TextView
                    updates = setting("版本与更新  ·  ${BuildConfig.VERSION_NAME}") { updater.show(updates) }
                    lateinit var about: TextView
                    about = setting("关于红果 TV") {
                        tvTools.info("关于红果 TV", "hongguoTV · 非官方客户端\n基于 N3urda/hongguoTV\n功能同步：tsukiyo-zero/hongguoTV\n版本：${BuildConfig.VERSION_NAME}\n项目：https://github.com/N3urda/hongguoTV\n\n本应用与红果官方无隶属关系。", about)
                    }
                    lateinit var license: TextView
                    license = setting("开源许可") {
                        tvTools.info("开源许可", "本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：N3urda/hongguoTV。", license)
                    }
                }
            }
            settingsRows = entries.toList()
            settingsRows.forEachIndexed { position, view ->
                view.nextFocusLeftId = buttons[index].id
                view.nextFocusRightId = view.id
                view.nextFocusUpId = settingsRows.getOrNull(position - 1)?.id ?: nav[tab].id
                view.nextFocusDownId = settingsRows.getOrNull(position + 1)?.id ?: view.id
                val paint = view.onFocusChangeListener
                view.setOnFocusChangeListener { target, focused ->
                    paint?.onFocusChange(target, focused)
                    if (focused) settingsPositions[index] = position
                }
            }
            buttons.forEachIndexed { category, view ->
                view.nextFocusRightId = settingsRows[settingsPositions[index].coerceAtMost(settingsRows.lastIndex)].id
                view.nextFocusUpId = buttons.getOrNull(category - 1)?.id ?: nav[tab].id
                view.nextFocusDownId = buttons.getOrNull(category + 1)?.id ?: view.id
                view.background = TvVisual.row(this@MainActivity, view.hasFocus(), category == index, category = true)
                view.setTextColor(if (view.hasFocus() || category == index) TvVisual.focusedValue else muted)
            }
            nav.forEach { it.nextFocusDownId = buttons[index].id }
        }
        render(settingsGroup)
        parent.addView(text("↑↓ 选择  ·  → 进入选项  ·  ← 返回分类  ·  返回：分类 / 顶部导航", 12f, Color.rgb(112, 122, 140)).apply { setPadding(0, dp(12), 0, 0) })
        settingsRows.getOrNull(settingsPositions[settingsGroup].coerceAtMost(settingsRows.lastIndex))?.requestFocus()
    }

    private fun saveDiscoveryDraft() {
        (searchInput as? EditText)?.let { searchDraft = it.text.toString() }
    }

    private fun returnToDiscovery() {
        query = ""
        searchDraft = ""
        tvSearchOpen = false
        page = 1
        catalog = emptyList()
        hasMore = false
        catalogFocus = ""
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput?.windowToken, 0)
        showCatalog(load = false)
    }

    // 统一接收键盘、历史等来源的搜索词，保存历史并重置到第一页。
    private fun runSearch(value: String) {
        val clean = value.trim().take(80)
        if (clean.isEmpty()) {
            returnToDiscovery()
            return
        }
        (searchInput as? EditText)?.let {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
        library.rememberSearch(clean, ContentType.SHORT)
        tvSearchOpen = true
        searchDraft = clean
        query = clean
        submittedSearch = clean
        tab = 1
        page = 1
        catalogFocus = ""
        showCatalog(true)
    }

    private fun showSearchHistory(anchor: View) {
        val history = library.searches().distinctBy { it.query }
        if (history.isEmpty()) {
            tvTools.info("搜索历史", "搜索过的剧名会保存在这里，最多保留 20 条。", anchor)
            return
        }
        tvTools.choose(
            "搜索历史",
            history.map { it.query },
            anchor,
            { index -> runSearch(history[index].query) },
            "清空历史",
        ) {
            tvTools.confirm("清空搜索历史", "清除本机保存的搜索词？", anchor) { library.clearSearches() }
        }
    }

    private fun manageHistory(series: Series, anchor: View?) {
        tvTools.choose(
            series.title,
            listOf("继续观看", if (library.watched(series.id)) "恢复未看状态" else "标记整剧已看", "删除这条记录"),
            anchor,
            { index ->
                when (index) {
                    0 -> openDetail(series, true)
                    1 -> {
                        library.setWatched(series.id, !library.watched(series.id))
                        catalogFocus = series.id
                        showCatalog()
                    }
                    2 ->
                        tvTools.confirm("删除本机观看记录", "删除《${series.title}》的本机观看进度？收藏会保留。", anchor) {
                            library.removeHistory(series.id)
                            showCatalog()
                        }
                }
            },
        )
    }

    // 作品详情入口：衔接来源页面、详情数据与后续观看操作。
    private fun openDetail(
        series: Series,
        resume: Boolean = false,
        autoplay: Boolean = true,
        cloudProgress: CloudProgress? = null,
        fromRelated: Boolean = false,
    ) {
        if (!fromRelated) detailTrail.clear()
        relatedSection = null
        val accountScope = cloudAccount.historyScope
        detailCloudScope = accountScope
        detailCloudProgress =
            cloudProgress ?: if (!resume) cloudAccount.progress(series.id) else null
        pendingDetail = series
        detailAutoplay = autoplay
        invalidatePage()
        releaseCatalogViews()
        screen = "detail"
        directPlayback = resume
        detail = null
        synopsisExpanded = false
        val body = base(series.title)
        message(body, "正在加载剧集…")
        work(
            { repository.detail(series.id) },
            { result ->
                detail = result
                library.observeFavorite(
                    series.id,
                    result.episodes.size,
                    System.currentTimeMillis(),
                    acknowledge = true,
                )
                if (
                    cloudProgress != null &&
                        (accountScope != cloudAccount.historyScope || !cloudAccount.signedIn)
                ) {
                    showCatalog()
                    return@work
                }
                val progress = detailProgress(result, preferCloud = cloudProgress != null)
                val target =
                    ResumePlayback.target(
                        result.episodes,
                        progress?.episodeId,
                        progress?.position ?: 0,
                        progress?.completed == true,
                    )
                episodeIndex = target.index
                group = episodeIndex / 20
                if (resume) playEpisode(target.index, target.position, autoplay = detailAutoplay)
                else showDetail(false)
            },
            { problem ->
                error(body, problem) {
                    if (cloudProgress != null && accountScope != cloudAccount.historyScope)
                        showCatalog()
                    else openDetail(series, resume, detailAutoplay, cloudProgress, fromRelated)
                }
            },
        )
    }

    private fun detailProgress(data: Detail, preferCloud: Boolean = false): WatchProgress? =
        ResumePlayback.progress(
            data,
            library.progress(data.series.id),
            detailCloudProgress?.takeIf {
                cloudAccount.signedIn && detailCloudScope == cloudAccount.historyScope
            },
            preferCloud,
        )

    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage()
        releaseCatalogViews()
        screen = "detail"
        val data = detail ?: return showCatalog()
        val body = base("剧集详情")
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = column()
        scroll.addView(content)
        body.addView(scroll, lp(-1, 0).apply { weight = 1f })
        val hero = row()
        hero.gravity = Gravity.TOP
        hero.setPadding(0, dp(12), 0, dp(12))
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        hero.addView(image, lp(dp(123), dp(174)).apply { rightMargin = dp(24) })
        loadCover(image, data.series.cover)
        val info = column()
        hero.addView(info, lp(0, -2).apply { weight = 1f })
        info.addView(
            text(data.series.title, 26f).apply {
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        info.addView(
            text("${data.episodes.size} 集  ·  ${data.series.tags}", 13f, muted).apply {
                maxLines = 1
                setPadding(0, dp(8), 0, dp(8))
            }
        )
        val description = data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis =
            text(description, 14f, muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                maxLines = if (synopsisExpanded) 20 else 2
                ellipsize = TextUtils.TruncateAt.END
            }
        info.addView(synopsis)
        val actions = row()
        actions.setPadding(0, dp(12), 0, 0)
        info.addView(actions)
        val progress = detailProgress(data)
        val resumeIndex =
            progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 } } ?: 0
        val watched = library.watched(data.series.id)
        val continueTarget =
            ResumePlayback.target(
                data.episodes,
                progress?.episodeId,
                progress?.position ?: 0,
                progress?.completed == true,
            )
        val play =
            addButton(
                actions,
                if (watched) "重新观看"
                else if (progress != null) "继续第 ${continueTarget.index+1} 集" else "开始观看",
            ) {
                // 点击时重新读取进度，避免账号切换后仍使用界面创建时捕获的旧账号记录。
                val current = detailProgress(data)
                val target =
                    ResumePlayback.target(
                        data.episodes,
                        current?.episodeId,
                        current?.position ?: 0,
                        current?.completed == true,
                    )
                playEpisode(if (watched) 0 else target.index, if (watched) 0 else target.position)
            }
        lateinit var detailComments: TextView
        detailComments =
            addButton(actions, "评论 · 第 ${episodeIndex+1} 集") { social.showComments(detailComments) }
        val secondary = row().apply { setPadding(0, dp(8), 0, 0) }
        info.addView(secondary)
        accountFavoriteButton =
            addButton(secondary, cloudAccount.favoriteActionLabel(data.series.id)) {
                accountFavoriteAction(data.series, accountFavoriteButton)
            }
        lateinit var favorite: TextView
        favorite =
            addButton(secondary, if (library.favorite(data.series.id)) "已在本机收藏" else "加入本机收藏") {
                favoriteMonitor.stop()
                val saved = library.toggle(data.series)
                if (saved)
                    library.observeFavorite(
                        data.series.id,
                        data.episodes.size,
                        System.currentTimeMillis(),
                        acknowledge = true,
                    )
                favorite.text = if (saved) "已在本机收藏" else "加入本机收藏"
            }
        lateinit var synopsisButton: TextView
        synopsisButton =
            addButton(secondary, if (synopsisExpanded) "收起简介" else "完整简介") {
                val previousScroll = scroll.scrollY
                synopsisExpanded = !synopsisExpanded
                synopsis.maxLines = if (synopsisExpanded) 20 else 2
                synopsisButton.text = if (synopsisExpanded) "收起简介" else "完整简介"
                scroll.post { scroll.scrollTo(0, previousScroll) }
            }
        content.addView(hero)
        val groupRow = row()
        groupRow.setPadding(0, dp(3), 0, dp(5))
        val maxGroup = data.episodes.lastIndex / 20
        group = group.coerceIn(0, maxGroup)
        val previous =
            addButton(groupRow, "‹ 上一组") {
                    if (group > 0) {
                        group--
                        showDetail(true)
                    }
                }
                .apply {
                    isEnabled = group > 0
                    isFocusable = group > 0
                    alpha = if (group > 0) 1f else .4f
                }
        groupRow.addView(
            text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集", 16f).apply {
                gravity = Gravity.CENTER
            },
            lp(dp(190), dp(42)),
        )
        addButton(groupRow, "下一组 ›") {
                if (group < maxGroup) {
                    group++
                    showDetail(true)
                }
            }
            .apply {
                isEnabled = group < maxGroup
                isFocusable = group < maxGroup
                alpha = if (group < maxGroup) 1f else .4f
            }
        lateinit var jump: TextView
        jump =
            addButton(groupRow, "跳转集数") {
                tvTools.episodePicker(data.episodes.size, episodeIndex, jump) { target ->
                    episodeIndex = target
                    group = target / 20
                    showDetail(true)
                }
            }
        content.addView(groupRow)
        content.addView(
            text("橙色为当前集 · 上次为续播位置 · 方向键选择，确定播放", 12f, muted).apply { setPadding(0, 0, 0, dp(6)) }
        )
        val episodes = mutableListOf<View>()
        (group * 20 until minOf((group + 1) * 20, data.episodes.size))
            .toList()
            .chunked(10)
            .forEach { numbers ->
                val line = row()
                numbers.forEach { index ->
                    val item =
                        button(
                            "${index+1}" +
                                (if (index == episodeIndex) "\n当前"
                                else if (index == resumeIndex && progress != null)
                                    if (progress.completed) "\n已看" else "\n上次"
                                else ""),
                            index == episodeIndex,
                        ) {
                            val current = detailProgress(data)
                            playEpisode(
                                index,
                                if (
                                    current?.episodeId == data.episodes[index] && !current.completed
                                )
                                    current.position
                                else 0,
                            )
                        }
                    item.textSize = 12f
                    item.setPadding(dp(2), dp(3), dp(2), dp(3))
                    item.contentDescription = "第 ${index+1} 集"
                    line.addView(
                        item,
                        lp(0, dp(42)).apply {
                            weight = 1f
                            rightMargin = dp(6)
                        },
                    )
                    episodes += item
                }
                repeat(10 - numbers.size) {
                    line.addView(
                        Space(this),
                        lp(0, dp(42)).apply {
                            weight = 1f
                            rightMargin = dp(6)
                        },
                    )
                }
                content.addView(line, lp(-1, dp(49)))
            }
        episodes.forEachIndexed { i, v ->
            v.nextFocusLeftId = if (i % 10 == 0) v.id else episodes[i - 1].id
            v.nextFocusRightId =
                if (i % 10 == 9 || i == episodes.lastIndex) v.id else episodes[i + 1].id
            if (i >= 10) v.nextFocusUpId = episodes[i - 10].id
            v.nextFocusDownId = if (i + 10 < episodes.size) episodes[i + 10].id else v.id
        }
        val primary = listOf(play, detailComments)
        play.setTextColor(accent)
        val secondaryActions = listOf(accountFavoriteButton!!, favorite, synopsisButton)
        secondaryActions.forEach {
            it.textSize = 13f
            it.setTextColor(muted)
        }
        primary.forEachIndexed { i, v ->
            v.nextFocusLeftId = primary.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = primary.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusDownId = secondaryActions[minOf(i, secondaryActions.lastIndex)].id
        }
        secondaryActions.forEachIndexed { i, v ->
            v.nextFocusUpId = primary[minOf(i, primary.lastIndex)].id
            v.nextFocusLeftId = secondaryActions.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = secondaryActions.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusDownId = jump.id
        }
        episodes.take(10).forEach { it.nextFocusUpId = jump.id }
        lateinit var section: RelatedWorksView
        fun loadRelated(force: Boolean = false) {
            section.loading()
            work(
                { repository.related(data.series.id, force) },
                { rows -> section.show(RelatedWorks.ordered(data.series, rows)) },
                { section.failed() },
            )
        }
        section =
            RelatedWorksView(
                this,
                episodes,
                ::loadCover,
                cloudAccount::reservationLabel,
                { related, anchor ->
                    if (related.upcoming) {
                        if (!cloudAccount.signedIn) cloudAccount.show()
                        else
                            tvTools.choose(
                                related.series.title,
                                listOf(cloudAccount.reservationLabel(related)),
                                anchor,
                                { _ ->
                                    val scope = cloudAccount.historyScope
                                    cloudAccount.reserve(related) { message ->
                                        if (
                                            screen == "detail" &&
                                                detail?.series?.id == data.series.id &&
                                                scope == cloudAccount.historyScope
                                        )
                                            tvTools.info("预约状态", message, anchor)
                                    }
                                },
                            )
                    } else {
                        if (detailTrail.size >= 10) detailTrail.removeFirst()
                        detailTrail.addLast(
                            DetailVisit(
                                data,
                                episodeIndex,
                                group,
                                detailCloudProgress,
                                detailCloudScope,
                            )
                        )
                        openDetail(related.series, fromRelated = true)
                    }
                },
                { loadRelated(true) },
            )
        relatedSection = section
        content.addView(section)
        if (cloudAccount.signedIn && cloudAccount.reservationState(false).message == "尚未读取预约记录")
            cloudAccount.refreshReservations()
        loadRelated()
        if (
            cloudAccount.signedIn &&
                !cloudAccount.favoriteState().loaded &&
                !cloudAccount.favoriteState().loading
        )
            cloudAccount.refreshFavorites()
        if (focusEpisode)
            episodes
                .getOrNull((episodeIndex - group * 20).coerceIn(0, episodes.lastIndex))
                ?.requestFocus()
        else play.requestFocus()
    }

    // 正式播放入口：准备指定剧集的播放资源与播放器状态。
    private fun playEpisode(
        index: Int,
        position: Long = 0,
        autoplay: Boolean = true,
        recovering: Boolean = false,
        autoDowngrade: Boolean = false,
        automaticNext: Boolean = false,
        recreateView: Boolean = false,
    ) {
        cancelNextSeason()
        seasonSuppressed = false
        updater.interrupt()
        social.reset()
        val data = detail ?: return
        var playbackStarted = android.os.SystemClock.elapsedRealtime()
        val keepRecoveryStatus = recovering || (screen == "player" && playError && index == episodeIndex)
        val reuse =
            screen == "player" && player != null && playerView != null && (!playError || recovering) && !recreateView
                && ((playerView?.videoSurfaceView is TextureView) == VideoRotation.usesTexture(library.videoRotation))
        library.setWatched(data.series.id, false)
        val selectedIndex = index.coerceIn(0, data.episodes.lastIndex)
        if (
            position == 0L &&
                autoplay &&
                !recovering &&
                !playError &&
                queuedEpisode == selectedIndex &&
                queuedNext != null &&
                player?.hasNextMediaItem() == true
        ) {
            requestedAutoplay = true
            pausedForLifecycle = false
            player?.seekToNextMediaItem()
            player?.play()
            return
        }
        val prepareKey = "${data.episodes[selectedIndex]}:${library.maxQuality}"
        // 切到正在准备的下一集时接管原任务，不能先 take/clear 再取消，否则会重新请求同一集。
        val pending =
            if (position == 0L && !recovering) claimPendingPreparation(prepareKey) else null
        val prefetched =
            if (position == 0L && !recovering && pending == null) preparedNext.take(prepareKey)
            else null
        val claimedHttp =
            if (prefetched != null) prefetchHttp.also { prefetchHttp = null } else null
        android.util.Log.i(
            "HongguoTV",
            "Episode transition target=${selectedIndex+1} cached=${prefetched!=null} warmupAttempts=$prefetchAttempts warmupRunning=${prefetchJob?.isDone==false}",
        )
        saveProgress()
        library.flush()
        invalidatePage()
        relayStopAsync() // 换集即结束上一会话；伴机兜底会另起新会话
        favoriteMonitor.stop()
        releaseCatalogViews()
        if (reuse) {
            cancelRecovery()
            cancelPrefetchState(keepPrepared = pending != null)
            tvTools.close()
            episodePanel?.dismiss()
            episodePanel = null
            playerListener?.let { player?.removeListener(it) }
            playerListener = null
            player?.stop()
            player?.clearMediaItems()
            video?.close()
            video = null
            closeTransport(playbackHttp)
            playbackHttp = null
            main.removeCallbacks(hideHud)
            main.removeCallbacks(seekRunnable)
            pendingSeek = null
        } else releasePlayer(keepPrepared = pending != null)
        artwork.cancelPage(clearMemory = true)
        playbackHttp = claimedHttp
        video = prefetched
        var firstReady = true
        var firstFrame = true
        if (!recovering) {
            recovery.reset()
            allowSlowRecovery = false
            playbackPreparationUntil = android.os.SystemClock.elapsedRealtime() + 20_000
            autoRecoveredPlay = false
        }
        autoRecovery = false
        readySince = 0
        startPosition = position.coerceAtLeast(0)
        requestedAutoplay = autoplay
        if (autoplay) sleepStopped = false
        screen = "player"
        episodeIndex = selectedIndex
        group = episodeIndex / 20
        panel = false
        playError = false
        waitingForFirstFrame = true
        hideAutoplayOverlay = automaticNext && !keepRecoveryStatus
        recoveryDisplayActive = keepRecoveryStatus
        relayAttempted = false
        relaySlowHintShown = false
        stopRelaySpeedWatch()
        playbackReady = false
        episodeDuration = 0
        quality = ""
        attemptedQuality = 0
        currentTrack = null
        currentRendition = null
        currentTiers = emptyList()
        if (!autoDowngrade) autoDowngrades = 0
        pausedForLifecycle = !autoplay
        player?.playWhenReady = requestedAutoplay
        prefetchAttempts = 0
        prefetchAttemptAt = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!reuse) {
            root.removeAllViews()
            root.setBackgroundColor(Color.BLACK)
            val view =
                (if (VideoRotation.usesTexture(library.videoRotation))
                    layoutInflater.inflate(R.layout.video_player, root, false) as PlayerView else PlayerView(this)).apply {
                    useController = false
                    resizeMode = frameResizeMode()
                    isFocusable = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            playerView = view
            root.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoRotation() }
            view.post { if (playerView === view) applyVideoRotation() }
            persistentTime = text("", 12f, white)
            persistentBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(accent) }
            persistentProgress = column().apply {
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setPadding(dp(12), dp(4), dp(12), dp(6))
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                addView(persistentTime); addView(persistentBar, lp(-1, dp(3)))
                visibility = View.GONE
            }
            root.addView(persistentProgress, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            social.attach(root)
            playbackClock = TextClock(this).apply {
                format12Hour = "HH:mm"
                format24Hour = "HH:mm"
                textSize = 18f
                setTextColor(white)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = rounded(Color.argb(150, 11, 15, 21))
                isFocusable = false
            }.also { clock ->
                root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = (resources.displayMetrics.heightPixels * .05f).toInt()
                    rightMargin = dp(widthDp() * .05f)
                })
            }
            hud =
                column().apply {
                    setPadding(
                        dp(widthDp() * .05f),
                        dp(20),
                        dp(widthDp() * .05f),
                        (resources.displayMetrics.heightPixels * .05f).toInt(),
                    )
                    background =
                        GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.TRANSPARENT, Color.argb(240, 11, 15, 21)),
                        )
                }
            playbackTitle =
                text("", 23f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTypeface(null, Typeface.BOLD)
                }
            hud.addView(playbackTitle)
            playbackText = text("", 15f, muted).apply { setPadding(0, dp(10), 0, dp(9)) }
            hud.addView(playbackText)
            progressBar =
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(surface)
                }
            hud.addView(progressBar, lp(-1, dp(4)))
            hud.addView(
                text("确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 操作菜单    菜单 更多", 13f, muted).apply {
                    setPadding(0, dp(12), 0, 0)
                }
            )
            controls =
                column().apply {
                    setPadding(0, dp(12), 0, 0)
                    visibility = View.GONE
                }
            hud.addView(controls)
            root.addView(
                ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    addView(hud)
                },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
            )
        }
        playbackTitle.text = "${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text = preparingPlaybackText(recoveryDisplayActive, lastPlaybackFailure, false)
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        main.removeCallbacks(hideHud)
        hud.visibility = if (hideAutoplayOverlay) View.GONE else View.VISIBLE
        updateClock()
        controls.removeAllViews()
        controls.visibility = if (recoveryDisplayActive) View.VISIBLE else View.GONE
        if (recoveryDisplayActive) {
            addButton(controls, "取消恢复") { stopPreparingPlayback() }
            addButton(controls, "返回选集") { returnToDetail(true) }
        }
        transportPlay = null
        sleepButton = null
        syncMediaSession()
        val requestedId = data.episodes[episodeIndex]
        val maxQuality = library.maxQuality
        val ticket = generation
        var usedPending = false
        work(
            {
                val remaining = if (allowSlowRecovery) 20_000L else
                    playbackPreparationUntil - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) throw java.net.SocketTimeoutException("播放准备超时")
                RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remaining)) {
                    val prepared =
                        prefetched
                            ?: pending?.let {
                                awaitPreparedEpisode(it, remaining).also { ready -> usedPending = ready != null }
                            }
                    prepared?.let { OpenedStream(it, null, emptyList(), it.trackInfo()) }
                        ?: openStream(
                            repository.http,
                            repository,
                            requestedId,
                            maxQuality,
                            CodecCapabilities::supports,
                            metadataAhead = data.episodes.drop(selectedIndex + 1).take(2),
                        ) {
                            it == null || CodecCapabilities.supports(it)
                        }
                        ?: throw java.io.IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
                }
            },
            { opened ->
                if (promotedPreparation === pending) promotedPreparation = null
                if (pending != null) {
                    if (usedPending) playbackHttp = pending.http else closeTransport(pending.http)
                }
                val remote = opened.video
                video = remote
                quality = playedQualityLabel(opened, maxQuality)
                attemptedQuality = opened.rendition?.quality ?: 0
                currentTrack = opened.track
                currentRendition = opened.rendition
                currentTiers = opened.tiers
                val p =
                    ensurePlayer()
                p.setPlaybackSpeed(library.playbackSpeed)
                val listener =
                    object : Player.Listener {
                        private fun active() =
                            player === p &&
                                generation == ticket &&
                                p.currentMediaItem?.mediaId == data.episodes.getOrNull(episodeIndex)

                        override fun onPlaybackStateChanged(state: Int) {
                            if (!active()) return
                            if (state == Player.STATE_READY) {
                                if (!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                                    playerFailure(
                                        PlaybackException(
                                            "资源没有可解码的视频轨道",
                                            null,
                                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                                        )
                                    )
                                    return
                                }
                                playbackReady = true
                                episodeDuration = p.duration.coerceAtLeast(0)
                                if (firstReady) {
                                    firstReady = false
                                    readySince = android.os.SystemClock.elapsedRealtime()
                                    android.util.Log.i(
                                        "HongguoTV",
                                        "Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}",
                                    )
                                }
                                if (hud.visibility == View.VISIBLE || library.alwaysShowProgress) updatePlaybackText()
                            }
                            syncMediaSession()
                            if (state == Player.STATE_ENDED) {
                                saveProgress(true)
                                if (sleepTimer.episodeEnded()) stopForSleep()
                                else if (
                                    foreground &&
                                        requestedAutoplay &&
                                        !pausedForLifecycle &&
                                        library.autoNext &&
                                        episodeIndex < data.episodes.lastIndex
                                )
                                    playEpisode(episodeIndex + 1, automaticNext = true)
                                else {
                                    val continueSeason = requestedAutoplay
                                    requestedAutoplay = false
                                    p.pause()
                                    playbackText.text = "本集已结束"
                                    showPanel()
                                    if (continueSeason && foreground && !pausedForLifecycle && !sleepStopped && library.autoNext &&
                                        episodeIndex == data.episodes.lastIndex) finishSeason(data)
                                }
                            }
                        }

                        override fun onRenderedFirstFrame() {
                            if (active() && firstFrame) {
                                firstFrame = false
                                playbackFirstFrame()
                                android.util.Log.i(
                                    "HongguoTV",
                                    "Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}",
                                )
                            }
                        }

                        override fun onIsPlayingChanged(playing: Boolean) {
                            if (active()) {
                                if (hud.visibility == View.VISIBLE) showHud()
                                syncMediaSession()
                            }
                        }

                        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                            if (active()) syncMediaSession()
                        }

                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int,
                        ) {
                            if (
                                player === p &&
                                    generation == ticket &&
                                    newPosition.mediaItem?.mediaId ==
                                        queuedNext?.let {
                                            data.episodes.getOrNull(queuedEpisode)
                                        } &&
                                    reason in
                                        listOf(
                                            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
                                            Player.DISCONTINUITY_REASON_SEEK,
                                        )
                            ) {
                                if (
                                    advanceQueuedEpisode(
                                        oldPosition.positionMs,
                                        reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
                                    )
                                ) {
                                    playbackStarted = android.os.SystemClock.elapsedRealtime()
                                    firstReady = true
                                    firstFrame = true
                                }
                            }
                            if (active()) syncMediaSession()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (active()) playerFailure(error)
                        }
                    }
                playerListener = listener
                p.addListener(listener)
                val source = episodeSource(remote, requestedId)
                p.setMediaSource(source)
                p.seekTo(position.coerceAtLeast(0))
                p.prepare()
                p.playWhenReady =
                    foreground && requestedAutoplay && !pausedForLifecycle && episodePanel == null
            },
            { problem ->
                cancelPromotedPreparation()
                playerFailure(problem)
            },
        )
        // work() 会先取消上一项工作，因此在它建立新任务之后绑定本次接管资源。
        promotedPreparation = pending
    }

    private fun claimPendingPreparation(key: String): PendingEpisodePreparation? {
        if (prefetchedFor != key || preparedNext.isFresh(key)) return null
        val job = prefetchJob?.takeIf { !it.isDone } ?: return null
        val scope = prefetchScope ?: return null
        val http = prefetchHttp ?: return null
        prefetchJob = null
        prefetchScope = null
        prefetchHttp = null
        prefetchedFor = ""
        return PendingEpisodePreparation(key, job, scope, http)
    }

    private fun awaitPreparedEpisode(pending: PendingEpisodePreparation, remainingMillis: Long): RemoteVideo? {
        try {
            pending.job.get(remainingMillis.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            pending.scope.cancel()
            pending.job.cancel(true)
            closeTransport(pending.http)
            throw java.net.SocketTimeoutException("预加载等待超时")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("播放准备已取消")
        } catch (_: java.util.concurrent.ExecutionException) {
            // 预加载失败只影响本次投机任务；正式播放仍可正常重新获取一次。
        } catch (_: java.util.concurrent.CancellationException) {
            throw java.io.InterruptedIOException("播放准备已取消")
        }
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("播放准备已取消")
        return preparedNext.take(pending.key)
    }

    private fun cancelPromotedPreparation() {
        val pending = promotedPreparation ?: return
        promotedPreparation = null
        pending.scope.cancel()
        pending.job.cancel(true)
        preparedNext.clear()
        closeTransport(pending.http)
    }

    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if (http == null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    private fun cancelPrefetch() {
        cancelPrefetchState(keepPrepared = false)
    }

    private fun cancelPrefetchState(keepPrepared: Boolean) {
        clearQueuedEpisode()
        prefetchScope?.cancel()
        prefetchScope = null
        if (!keepPrepared) preparedNext.clear()
        prefetchJob?.cancel(true)
        prefetchJob = null
        closeTransport(prefetchHttp)
        prefetchHttp = null
        prefetchedFor = ""
    }

    private fun maybePrefetch() {
        val p = player ?: return
        val data = detail ?: return
        if (
            !foreground ||
                !p.isPlaying ||
                !library.autoNext ||
                playError ||
                sleepStopped ||
                sleepTimer.episodesLeft == 1 ||
                queuedNext != null ||
                episodeIndex >= data.episodes.lastIndex
        )
            return
        val id = data.episodes[episodeIndex + 1]
        val metadataAhead = data.episodes.drop(episodeIndex + 2).take(2)
        val quality = library.maxQuality
        val key = "$id:$quality"
        if (preparedNext.isFresh(key) || prefetchJob?.isDone == false) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (
            readySince == 0L ||
                !PrefetchPolicy.canStart(
                    p.currentPosition,
                    p.duration,
                    p.playbackParameters.speed,
                    now - readySince,
                    p.bufferedPosition - p.currentPosition,
                )
        )
            return
        if (
            prefetchAttempts >= 3 ||
                (prefetchAttempts > 0 && now - prefetchAttemptAt < 15_000) ||
                connectivity.isActiveNetworkMetered ||
                !networkAvailable()
        )
            return
        cancelPrefetch()
        prefetchedFor = key
        prefetchAttemptAt = now
        prefetchAttempts++
        val ticket = preparedNext.begin(key)
        val http = newPrefetchTransport()
        prefetchHttp = http
        val scope = RequestScope()
        prefetchScope = scope
        prefetchJob =
            prefetchWorker.submit {
                var remote: RemoteVideo? = null
                try {
                    val prepared =
                        scope.run {
                            val opened =
                                // 播放地址复用当前 Repository 的设备身份和会话；仅视频传输独立取消。
                                openStream(
                                    http,
                                    repository,
                                    id,
                                    quality,
                                    CodecCapabilities::supports,
                                    initialBytes = 32 * 1024,
                                    metadataAhead = metadataAhead,
                                    speculative = true,
                                ) {
                                    it == null || CodecCapabilities.supports(it)
                                } ?: throw java.io.IOException("没有电视可播放的清晰度")
                            opened.video.also { remote = it }
                        }
                    if (preparedNext.complete(ticket, key, prepared)) {
                        android.util.Log.i(
                            "HongguoTV",
                            "Next episode header ready; handing media buffering to playback queue",
                        )
                        main.post { queuePreparedEpisode(key) }
                    }
                } catch (problem: Exception) {
                    remote?.close()
                    android.util.Log.i(
                        "HongguoTV",
                        "Next episode preparation unavailable: ${problem.javaClass.simpleName}",
                    )
                }
            }
    }

    private fun newPrefetchTransport(): okhttp3.OkHttpClient =
        repository.http
            .newBuilder()
            .dispatcher(okhttp3.Dispatcher())
            .connectionPool(okhttp3.ConnectionPool())
            .build()

    private fun episodeSource(remote: RemoteVideo, id: String) =
        ProgressiveMediaSource.Factory { VideoDataSource(remote) }
            .createMediaSource(
                MediaItem.Builder()
                    .setMediaId(id)
                    .setUri("hongguotv://episode/$id")
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build()
            )

    private fun queuePreparedEpisode(key: String) {
        val p = player ?: return
        val data = detail ?: return
        val next = episodeIndex + 1
        if (
            !foreground ||
                screen != "player" ||
                playError ||
                !library.autoNext ||
                sleepStopped ||
                sleepTimer.episodesLeft == 1 ||
                queuedNext != null ||
                next !in data.episodes.indices ||
                key != "${data.episodes[next]}:${library.maxQuality}" ||
                p.mediaItemCount != 1 ||
                p.currentMediaItem?.mediaId != data.episodes[episodeIndex]
        )
            return
        val remote = preparedNext.take(key) ?: return
        queuedNext = remote
        queuedHttp = prefetchHttp
        prefetchHttp = null
        queuedEpisode = next
        p.addMediaSource(episodeSource(remote, data.episodes[next]))
        android.util.Log.i("HongguoTV", "Next episode added to playback queue target=${next+1}")
    }

    private fun clearQueuedEpisode() {
        val p = player
        if (queuedNext != null && p != null && p.currentMediaItemIndex + 1 < p.mediaItemCount)
            p.removeMediaItems(p.currentMediaItemIndex + 1, p.mediaItemCount)
        queuedNext?.close()
        queuedNext = null
        queuedEpisode = -1
        closeTransport(queuedHttp)
        queuedHttp = null
    }

    /** Queue transition only: no stop, clearMediaItems, setMediaSource or prepare. */
    private fun advanceQueuedEpisode(oldPosition: Long, completed: Boolean): Boolean {
        val p = player ?: return false
        val data = detail ?: return false
        val next = queuedNext ?: return false
        if (
            queuedEpisode != episodeIndex + 1 ||
                p.currentMediaItem?.mediaId != data.episodes.getOrNull(queuedEpisode)
        )
            return false
        val old =
            WatchProgress(
                data.series,
                data.episodes[episodeIndex],
                episodeIndex,
                oldPosition.coerceAtLeast(0),
                maxOf(episodeDuration, oldPosition),
                completed,
                System.currentTimeMillis(),
            )
        library.save(old)
        cloudAccount.upload(
            CloudProgress(
                old.series,
                old.episodeId,
                old.episodeIndex,
                old.position,
                old.duration,
                old.updatedAt,
            ),
            data.episodes.size,
            true,
        )
        val previous = video
        val previousHttp = playbackHttp
        social.reset()
        video = next
        playbackHttp = queuedHttp
        episodeIndex = queuedEpisode
        group = episodeIndex / 20
        queuedNext = null
        queuedHttp = null
        queuedEpisode = -1
        if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
        previous?.close()
        closeTransport(previousHttp)
        quality = next.info.quality
        pendingSeek = null
        startPosition = 0
        playbackReady = p.playbackState == Player.STATE_READY
        episodeDuration = p.duration.coerceAtLeast(0)
        readySince = android.os.SystemClock.elapsedRealtime()
        prefetchAttempts = 0
        prefetchAttemptAt = 0
        cancelRecovery()
        recovery.reset()
        playbackTitle.text = "${data.series.title}  ·  第 ${episodeIndex+1} 集"
        controls.removeAllViews()
        controls.visibility = View.GONE
        panel = false
        // 自动队列续播保持画面干净，手动切集仍沿用原有提示。
        hideAutoplayOverlay = completed
        if (completed) hidePlaybackOverlay()
        transportPlay = null
        saveProgress()
        syncMediaSession()
        android.util.Log.i("HongguoTV", "Playback queue transition episode=${episodeIndex+1}")
        if (completed && sleepTimer.episodeEnded()) stopForSleep()
        return true
    }

    private fun networkAvailable(): Boolean = NetworkCompat.available(connectivity)

    private fun currentPosition(): Long =
        pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition

    private fun cancelRecovery() {
        main.removeCallbacks(retryRunnable)
        main.removeCallbacks(recoveryCountdown)
        retryPending = false
        autoRecovery = false
    }

    private fun showRecoveryStatus(remainingMillis: Long) {
        playbackText.text =
            recoveryStatusText(
                failure = lastPlaybackFailure,
                autoplay = retryAutoplay,
                slow = recovery.slow,
                attempt = recovery.attempts + 1,
                total = if (allowSlowRecovery) recovery.total else RecoveryBudget.FAST_ATTEMPTS,
                remainingMillis = remainingMillis,
                positionText = formatTime(retryPosition),
            )
    }

    private fun scheduleRecovery() {
        if (!foreground || !playError || !autoRecovery || retryPending) return
        if (!recovery.remaining || (!allowSlowRecovery &&
            (recovery.slow || android.os.SystemClock.elapsedRealtime() >= playbackPreparationUntil))) {
            autoRecovery = false
            playbackText.text = (if (recovery.remaining)
                "$lastPlaybackFailure\n快速恢复暂未成功，可立即重试、换集或选择继续等待。"
                else recoveryExhaustedText(lastPlaybackFailure))
            recoveryAction?.apply {
                text = if (recovery.remaining) "继续等待恢复" else "恢复次数已用尽"
                isEnabled = recovery.remaining
            }
            return
        }
        if (!networkAvailable()) {
            playbackText.text =
                "$lastPlaybackFailure\n网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"
            return
        }
        val plannedDelay = recovery.delayMillis ?: return
        val delay = if (allowSlowRecovery) plannedDelay else minOf(plannedDelay,
            (playbackPreparationUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        recoveryScheduledAt = android.os.SystemClock.uptimeMillis()
        recoveryDelayMillis = delay
        showRecoveryStatus(delay)
        retryPending = true
        main.postDelayed(retryRunnable, delay)
        main.postDelayed(recoveryCountdown, 1000)
    }

    /** 按需展示的脱敏诊断详情（issue #38）：机型/系统/版本/失败阶段/恢复计数，便于截图定位。 */
    private fun playbackDiagnostics(): String =
        try {
            "\n" +
                diagnosticLine(
                    model = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    appVersion = BuildConfig.VERSION_NAME,
                    failure = lastPlaybackFailure,
                    recoveryAttempts = recovery.attempts,
                    total = recovery.total,
                    detail = lastPlaybackDetail,
                )
        } catch (_: Exception) {
            ""
        }

    /**
     * Detects growing audio/video drift (video frame processing offset stays signed) and re-seeks
     * to the current position once per cooldown window: a seek flushes the audio processors and
     * clock basis, which users confirmed restores sync; a pause does not.
     */
    private fun playbackDriftListener(p: ExoPlayer): AnalyticsListener {
        val guard = PlaybackSyncGuard()
        return object : AnalyticsListener {
            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int,
            ) {
                val window = guard.offer(totalProcessingOffsetUs, frameCount, p.isPlaying) ?: return
                android.util.Log.i("HongguoTV", "Video frame offset ${window.averageUs/1000}ms")
                if (window.resync)
                    main.post {
                        if (
                            player === p &&
                                playbackReady &&
                                p.isPlaying &&
                                pendingSeek == null &&
                                !playError
                        ) {
                            android.util.Log.w(
                                "HongguoTV",
                                "Resync after audio/video drift ${window.averageUs/1000}ms at ${formatTime(p.currentPosition)}",
                            )
                            p.seekTo(p.currentPosition)
                        }
                    }
            }
        }
    }

    private fun playedQualityLabel(opened: OpenedStream, maxQuality: Int): String {
        val rendition = opened.rendition ?: return opened.video.info.quality
        val label = if (rendition.quality > 0) "${rendition.quality}P" else "自动"
        if (rendition.quality > maxQuality) return label + "（兼容资源）"
        val ideal = opened.tiers.filter { it.quality in 1..maxQuality }.maxOfOrNull { it.quality }
        return if (ideal != null && rendition.quality < ideal) label + "（自动降清）" else label
    }

    /** 懒建播放器（正式路径与伴机路径共用）；已存在则原样返回。 */
    private fun ensurePlayer(): ExoPlayer =
        player
            ?: run {
                val load =
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 1000, 2000)
                        .setTargetBufferBytes(12 * 1024 * 1024)
                        .build()
                val renderers =
                    playbackRenderers(this)
                ExoPlayer.Builder(this, renderers).setLoadControl(load).build().also {
                    player = it
                    playerView?.player = it
                    it.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                    it.setHandleAudioBecomingNoisy(true)
                    it.addAnalyticsListener(playbackDriftListener(it))
                }
            }

    /** 解析 bytevc2 档并让伴机起转码会话；失败抛 RelayException。 */
    private fun relayOpen(episodeId: String): RelayPlayback {
        val host = relayStore.host
        if (host.isBlank()) throw RelayException("伴机地址未配置，请在播放设置里填写")
        val client = RelayClient(relayHttp, host, relayStore.token)
        val health = client.health() ?: throw RelayException("伴机不可达（$host），请确认其电脑已启动伴机")
        if (!health.optBoolean("ffmpegOk", false))
            throw RelayException("伴机缺少 VVC 解码器，请在其电脑上安装 ffmpeg 7+")
        health.optJSONObject("session")?.takeIf { it.optString("state") == "RUNNING" }
            ?.let { throw RelayException("伴机正被其他播放占用") }
        val pick =
            repository.rawVideoModel(episodeId)
                .filter { it.codec in setOf("bytevc2", "bvc2") }
                .maxByOrNull { it.quality }
                ?: throw RelayException("本集没有 bytevc2 档位，伴机无用武之地")
        val keyB64 =
            pick.key?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        val (sessionId, hlsUrl) =
            client.createSession(
                pick.url,
                keyB64,
                if (pick.quality > 0) "${pick.quality}P" else "自动",
            ) ?: throw RelayException("伴机会话创建失败（可能正被占用）")
        val label =
            buildString {
                append(if (pick.quality > 0) "${pick.quality}P" else "自动")
                if ((pick.width ?: 0) > 0 && (pick.height ?: 0) > 0)
                    append(" · ${pick.width}×${pick.height}")
            }
        return RelayPlayback(sessionId, hlsUrl, label)
    }

    /** 把当前播放器切到伴机 HLS（复用既有进度/界面机制）；须在主线程调用。 */
    private fun startRelaySource(
        p: ExoPlayer,
        hlsUrl: String,
        id: String,
        position: Long,
        qualityLabel: String,
    ) {
        p.stop()
        p.clearMediaItems()
        playbackReady = false
        playError = false
        panel = false
        quality = "伴机转码 $qualityLabel"
        progressBar.visibility = View.VISIBLE
        playbackText.text = "正在由伴机转码…"
        controls.removeAllViews()
        controls.visibility = View.GONE
        val source =
            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(
                androidx.media3.datasource.DefaultDataSource.Factory(this),
            )
                .createMediaSource(
                    MediaItem.Builder()
                        .setMediaId(id)
                        .setUri(hlsUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build(),
                )
        p.setMediaSource(source)
        p.seekTo(position.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = foreground && requestedAutoplay && !pausedForLifecycle
    }

    /** 伴机兜底入口（playerFailure 面板前调用）：起会话成功则切 HLS，失败回落错误面板。 */
    private fun attemptRelayPlayback(position: Long, autoplay: Boolean) {
        val data = detail
        if (data == null) {
            playerFailure(RelayException("伴机不可用"))
            return
        }
        playbackText.text = "正在由伴机转码…"
        progressBar.visibility = View.VISIBLE
        val requestedId = data.episodes[episodeIndex]
        val ticket = generation
        work(
            { relayOpen(requestedId) },
            { result ->
                relaySessionId = result.sessionId
                // 正式路径监听器可能仍挂着（硬解失败路径），换上伴机专用监听避免双重处理。
                playerListener?.let { player?.removeListener(it) }
                playerListener = null
                val p = ensurePlayer()
                val relayListener =
                    object : Player.Listener {
                        private fun active() =
                            player === p &&
                                generation == ticket &&
                                p.currentMediaItem?.mediaId == requestedId

                        override fun onRenderedFirstFrame() {
                            if (active() && waitingForFirstFrame) playbackFirstFrame()
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (!active()) return
                            if (state == Player.STATE_READY) {
                                playbackReady = true
                                episodeDuration = p.duration.coerceAtLeast(0)
                                if (hud.visibility == View.VISIBLE || library.alwaysShowProgress) updatePlaybackText()
                            }
                            syncMediaSession()
                            if (state == Player.STATE_ENDED) {
                                saveProgress(true)
                                if (sleepTimer.episodeEnded()) stopForSleep()
                                else if (
                                    foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext &&
                                        !sleepStopped &&
                                        episodeIndex + 1 < data.episodes.size
                                ) {
                                    playEpisode(episodeIndex + 1, automaticNext = true)
                                } else if (foreground && requestedAutoplay && !pausedForLifecycle &&
                                    !sleepStopped && library.autoNext && episodeIndex == data.episodes.lastIndex) {
                                    finishSeason(data)
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (active()) playerFailure(error)
                        }
                    }
                playerListener = relayListener
                p.addListener(relayListener)
                startRelaySource(p, result.hlsUrl, requestedId, position, result.qualityLabel)
                startRelaySpeedWatch(ticket)
                android.util.Log.i(
                    "HongguoTV",
                    "Relay playback started episode=${episodeIndex + 1} position=$position quality=${result.qualityLabel}",
                )
            },
            { problem ->
                // 伴机失败：回落既有错误面板（relayAttempted 已置位，不会循环重试伴机）。
                playerFailure(problem)
            },
        )
    }

    /** 结束伴机会话（尽力而为，网络失败静默——伴机侧有空闲超时兜底）。 */
    private fun relayStopAsync() {
        val sessionId = relaySessionId ?: return
        relaySessionId = null
        val host = relayStore.host
        if (host.isBlank()) return
        Thread {
            runCatching { RelayClient(relayHttp, host, relayStore.token).stop(sessionId) }
        }.apply { isDaemon = true }.start()
    }

    /** 轻量性能监控：转码速度持续 <1x 实时提示一次（伴机 /health 的 speed 字段）。 */
    private fun startRelaySpeedWatch(ticket: Int) {
        stopRelaySpeedWatch()
        val poll =
            object : Runnable {
                override fun run() {
                    if (generation != ticket || relaySessionId == null) return
                    val host = relayStore.host
                    Thread {
                            val speed =
                                runCatching {
                                        RelayClient(relayHttp, host, relayStore.token)
                                            .health()
                                            ?.optJSONObject("session")
                                            ?.optDouble("speed")
                                            ?: Double.NaN
                                    }
                                    .getOrDefault(Double.NaN)
                            main.post {
                                if (generation != ticket || relaySessionId == null) return@post
                                if (!speed.isNaN() && speed < 1.0) {
                                    relaySlowCount++
                                    if (relaySlowCount >= 2 && !relaySlowHintShown) {
                                        relaySlowHintShown = true
                                        playbackText.text =
                                            "伴机性能不足（转码 ${"%.1f".format(speed)}x 实时），建议降低清晰度或稍后重试"
                                    }
                                } else relaySlowCount = 0
                                main.postDelayed(this, 10_000)
                            }
                        }.apply { isDaemon = true }.start()
                }
            }
        relaySpeedWatch = poll
        main.postDelayed(poll, 8_000)
    }

    private fun stopRelaySpeedWatch() {
        relaySpeedWatch?.let { main.removeCallbacks(it) }
        relaySpeedWatch = null
        relaySlowCount = 0
    }

    /** 伴机设置：开关 + 局域网地址 + 连接测试。 */
    private fun showRelaySettings(anchor: TextView) {
        if (relayDialog != null) return
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), 0)
            }
        val toggle =
            Switch(this).apply {
                isChecked = relayStore.enabled
                text = "启用伴机代解码（仅 bytevc2 兜底）"
            }
        val hostInput =
            EditText(this).apply {
                hint = "伴机地址，如 192.168.1.100:46521"
                setText(relayStore.host)
                setSingleLine(true)
            }
        val tokenInput =
            EditText(this).apply {
                hint = "伴机令牌（伴机电脑启动日志可查）"
                setText(relayStore.token)
                setSingleLine(true)
            }
        container.addView(toggle)
        container.addView(hostInput)
        container.addView(tokenInput)
        val searchStatus =
            TextView(this).apply {
                setTextColor(muted)
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            }
        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val searchButton = Button(this).apply { text = "搜索伴机" }
        val qrButton = Button(this).apply { text = "扫码输入" }
        searchButton.setOnClickListener {
            it.isEnabled = false
            searchStatus.text = "正在搜索局域网伴机（约 5 秒）…"
            val started =
                RelayDiscovery.discover(this@MainActivity, 5_000) { hosts ->
                    searchButton.isEnabled = true
                    when {
                        hosts.isEmpty() ->
                            searchStatus.text = "未发现伴机：请确认其电脑已启动伴机，或改用扫码/手动输入"
                        hosts.size == 1 -> {
                            hostInput.setText(hosts.first())
                            searchStatus.text = "已找到伴机 ${hosts.first()}"
                        }
                        else -> {
                            searchStatus.text = "发现 ${hosts.size} 台伴机，请选择"
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("发现多台伴机")
                                .setItems(hosts.toTypedArray()) { _, picked ->
                                    hostInput.setText(hosts[picked])
                                    searchStatus.text = "已选用 ${hosts[picked]}"
                                }
                                .show()
                        }
                    }
                }
            if (!started) {
                searchButton.isEnabled = true
                searchStatus.text = "此设备不支持自动发现，请改用扫码或手动输入"
            }
        }
        qrButton.setOnClickListener {
            tvTools.phoneInput(
                anchor,
                inputTitle = "手机输入伴机地址",
                pageTitle = "在电视上输入",
                pagePrompt = "输入伴机的局域网地址与令牌（示例 192.168.1.100:46521）。",
                inputPlaceholder = "伴机地址",
                extraFieldLabel = "伴机令牌",
                extraFieldPlaceholder = "8 位，伴机电脑启动日志可查",
            ) { host, token ->
                // 手机上留空的字段保持电视上原值（地址已配置时只需补令牌，反之亦然）。
                val normalized = RelayClient.normalizeHost(host ?: hostInput.text.toString())
                val trimmedToken = token?.trim().takeUnless { it.isNullOrEmpty() } ?: tokenInput.text.toString()
                when {
                    normalized == null ->
                        Toast.makeText(this@MainActivity, "无法确定伴机地址，请检查手机输入或地址栏", Toast.LENGTH_SHORT).show()
                    trimmedToken.isBlank() ->
                        Toast.makeText(this@MainActivity, "令牌不能为空（伴机电脑启动日志可查）", Toast.LENGTH_SHORT).show()
                    else -> {
                        hostInput.setText(normalized)
                        tokenInput.setText(trimmedToken)
                        searchStatus.text = "已填入 $normalized"
                    }
                }
            }
        }
        toolsRow.addView(searchButton, LinearLayout.LayoutParams(0, -2, 1f))
        toolsRow.addView(qrButton, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(toolsRow, LinearLayout.LayoutParams(-1, -2))
        container.addView(searchStatus)
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("伴机代解码")
                .setView(container)
                .setPositiveButton("保存", null)
                .setNeutralButton("测试连接", null)
                .setNegativeButton("取消", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = RelayClient.normalizeHost(hostInput.text.toString())
                if (toggle.isChecked && (normalized == null || tokenInput.text.toString().isBlank())) {
                    val reason = if (normalized == null) "地址格式不对（示例 192.168.1.100:46521）" else "启用伴机需填写令牌（见伴机电脑启动日志）"
                    Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                relayStore.enabled = toggle.isChecked
                if (normalized != null) relayStore.host = normalized
                relayStore.token = tokenInput.text.toString().trim()
                relayDialog?.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val normalized = RelayClient.normalizeHost(hostInput.text.toString())
                if (normalized == null) {
                    Toast.makeText(this, "地址格式不对（示例 192.168.1.100:46521）", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(this, "正在连接 $normalized …", Toast.LENGTH_SHORT).show()
                val token = tokenInput.text.toString().trim()
                Thread {
                    val health = RelayClient(relayHttp, normalized, token).health()
                    val message =
                        when {
                            health == null -> "伴机不可达，请确认其电脑已启动伴机"
                            !health.optBoolean("ffmpegOk", false) -> "已连接，但伴机缺少 VVC 解码器（需 ffmpeg 7+）"
                            else -> "连接成功：果映伴机 ${health.optString("version")}"
                        }
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        dialog.setOnDismissListener {
            relayDialog = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        relayDialog = dialog
        dialog.show()
    }

    private fun playerFailure(problem: Throwable) {
        // 已稳定出画面后的新故障开启新的快速恢复窗口，不能沿用几分钟前的启动截止时间。
        if (!waitingForFirstFrame && !playError) {
            playbackPreparationUntil = android.os.SystemClock.elapsedRealtime() + 20_000
            allowSlowRecovery = false
            recovery.reset()
        }
        lastPlaybackFailure = playbackFailureSummary(problem)
        lastPlaybackDetail = diagnosticDetail(problem)
        retryPosition = currentPosition().coerceAtLeast(0)
        retryAutoplay = requestedAutoplay && !sleepStopped
        saveProgress()
        cancelRecovery()
        cancelPrefetch()
        // 确定性失败（本集无任何可解码档，bytevc2 锁定集）：重试与降档都只会得到同一结果，
        // 不再进入自动恢复预算，不给降档按钮；手动重试保留（供给可能随服务端转码翻转）。
        val noPlayableQuality =
            generateSequence(problem) { it.cause }.take(8).any { it is NoPlayableQualityException }
        // The device claimed support it doesn't have (MediaCodecList over-reporting): learn the
        // runtime verdict and retry one tier lower automatically instead of surfacing the panel.
        if (
            !noPlayableQuality &&
                problem is PlaybackException &&
                problem.errorCode in
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException
                            .ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ) {
            val track = currentTrack
            val rendition = currentRendition
            when {
                track != null ->
                    CodecCapabilities.learnFailure(track.fourcc, track.width, track.height)
                rendition != null ->
                    CodecCapabilities.learnFailure(
                        rendition.codec,
                        rendition.width,
                        rendition.height,
                    )
            }
            if (shouldAutoDowngrade(autoDowngrades, 2, currentTiers, attemptedQuality)) {
                autoDowngrades++
                android.util.Log.w(
                    "HongguoTV",
                    "Auto downgrading after decoder failure (attempt $autoDowngrades, was ${attemptedQuality}P)",
                )
                playEpisode(episodeIndex, retryPosition, retryAutoplay, autoDowngrade = true)
                return
            }
        }
        // 伴机兜底：bytevc2 锁定集（阶梯耗尽）或硬解失败时，面板展示前先试一次局域网伴机
        // 转码；成功则把当前播放器切到伴机 HLS，失败原样落入下方错误面板，行为向后兼容。
        val relayEligible =
            noPlayableQuality ||
                (problem is PlaybackException && problem.errorCode in 3000..4999)
        if (!relayAttempted && relayStore.enabled && relayEligible) {
            relayAttempted = true
            attemptRelayPlayback(retryPosition, retryAutoplay)
            return
        }
        playError = true
        player?.pause()
        progressBar.visibility = View.GONE
        hud.visibility = View.VISIBLE
        controls.removeAllViews()
        controls.visibility = View.VISIBLE
        panel = true
        val decoding = problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable =
            if (problem is PlaybackException)
                problem.errorCode in 2000..2999 ||
                    problem.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
            else problem is java.io.IOException && !noPlayableQuality
        // 按钮直接指向阶梯里真实存在的更低档：没有就明说，不再让用户循环点一个必然重放
        // 同一流（如本集仅剩电视解不了的 1080P）的按钮。
        val lowerTier =
            if (noPlayableQuality) null else nextLowerQuality(attemptedQuality, currentTiers)
        playbackText.text =
            when {
                noPlayableQuality -> lastPlaybackFailure
                decoding && lowerTier != null -> "电视无法解码当前视频，可尝试更低清晰度。"
                decoding -> "电视无法解码当前视频，本集没有更低清晰度可用。"
                else -> "$lastPlaybackFailure · 可手动重试"
            }
        val actions = row()
        controls.addView(actions)
        val retryButton = addButton(actions, "立即重试") { playEpisode(episodeIndex, retryPosition, retryAutoplay) }
        if (recovery.attempts == 0) retryButton.requestFocus()
        lowerTier?.let { lower ->
            addButton(actions, "尝试 ${lower}P") {
                library.maxQuality = lower
                playEpisode(episodeIndex, retryPosition, retryAutoplay)
            }
        }
        addButton(actions, "返回选集") { returnToDetail(true) }
        addButton(actions, "查看错误详情") { tvTools.info("播放诊断", playbackDiagnostics().trim(), currentFocus) }
        if (retryable && !sleepStopped) {
            recoveryAction = addButton(controls, "停止自动重试") {
                if (autoRecovery) {
                    cancelRecovery()
                    playbackText.text = "$lastPlaybackFailure\n自动恢复已停止，观看位置已保留。"
                    recoveryAction?.text = "继续等待恢复"
                } else {
                    allowSlowRecovery = true
                    autoRecovery = true
                    recoveryAction?.text = "停止自动重试"
                    scheduleRecovery()
                }
            }
            autoRecovery = true
            scheduleRecovery()
        }
        android.util.Log.w(
            "HongguoTV",
            "Playback failure: ${problem.javaClass.simpleName}; reason=$lastPlaybackFailure" +
                (if (problem is PlaybackException) " code=${problem.errorCodeName}" else ""),
        )
    }

    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        if (waitingForFirstFrame && !playError && !hideAutoplayOverlay) {
            hud.visibility = View.VISIBLE
            return
        }
        panel = false
        controls.visibility = View.GONE
        controls.clearFocus()
        hud.visibility = View.GONE
        updatePersistentProgress()
        updateClock()
    }

    // 只有真正输出首帧才清除恢复原因，READY 不代表屏幕已经出画面。
    private fun playbackFirstFrame() {
        waitingForFirstFrame = false
        recoveryDisplayActive = false
        allowSlowRecovery = false
        controls.removeAllViews()
        controls.visibility = View.GONE
        panel = false
        if (autoRecoveredPlay) {
            autoRecoveredPlay = false
            Toast.makeText(this, "已恢复播放", Toast.LENGTH_SHORT).show()
        }
        if (hideAutoplayOverlay) {
            hideAutoplayOverlay = false
            hidePlaybackOverlay()
        } else showHud()
    }

    private fun stopPreparingPlayback() {
        invalidatePage()
        cancelRecovery()
        player?.stop()
        video?.close()
        video = null
        waitingForFirstFrame = false
        recoveryDisplayActive = false
        playError = true
        progressBar.visibility = View.GONE
        playbackText.text = "$lastPlaybackFailure\n恢复已取消，观看位置已保留。"
        controls.removeAllViews()
        addButton(controls, "立即重试") { playEpisode(episodeIndex, retryPosition, retryAutoplay) }
        addButton(controls, "返回选集") { returnToDetail(true) }
    }

    private fun updateClock() {
        playbackClock?.visibility = if (screen == "player" && library.clockMode != 0 &&
            (library.clockMode == 2 || hud.visibility == View.VISIBLE)) View.VISIBLE else View.GONE
    }

    private fun showHud() {
        if (screen != "player") return
        hideAutoplayOverlay = false
        hud.visibility = View.VISIBLE
        updateClock()
        updatePlaybackText()
        main.removeCallbacks(hideHud)
        if (player?.isPlaying == true) main.postDelayed(hideHud, if (panel) 8000 else 4500)
    }

    private fun updatePlaybackText() {
        updatePersistentProgress()
        transportPlay?.text = if (player?.playWhenReady == true) "暂停" else "播放"
        sleepButton?.text = "定时 ${sleepTimer.label()}"
        if (screen != "player" || playError) return
        if (waitingForFirstFrame && !sleepStopped) {
            playbackText.text = if (!requestedAutoplay && playbackReady)
                (if (recoveryDisplayActive) "$lastPlaybackFailure\n" else "") + "已准备好 · 保持暂停，按播放开始"
                else preparingPlaybackText(recoveryDisplayActive, lastPlaybackFailure, video != null)
            return
        }
        if (sleepStopped) {
            playbackText.text = "定时停止已生效 · 观看位置已保存 · 按播放可继续"
            return
        }
        if (!playbackReady) {
            playbackText.text =
                (if (!requestedAutoplay) "准备后保持暂停 · " else "") +
                    if (video == null) "正在获取播放地址…" else "正在缓冲…"
            return
        }
        val p = player ?: return
        val duration = p.duration.coerceAtLeast(0)
        val position = pendingSeek ?: p.currentPosition
        val state =
            when {
                p.playbackState == Player.STATE_BUFFERING -> "缓冲中"
                p.playbackState == Player.STATE_ENDED -> "本集已结束"
                !p.playWhenReady -> "已暂停"
                else -> "正在播放"
            }
        playbackText.text =
            "$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}" +
                (if (sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress =
            if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
    }

    private fun requestPlayback(play: Boolean) {
        requestedAutoplay = play
        pausedForLifecycle = !play
        if (play) sleepStopped = false
        if (!play) {
            player?.pause()
            retryAutoplay = false
            saveProgress()
        } else if (playError) {
            playEpisode(episodeIndex, retryPosition, true)
            return
        } else if (episodePanel == null) {
            if (player?.playbackState == Player.STATE_ENDED) player?.seekTo(0)
            player?.play()
        }
        if (!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud()
        updatePlaybackText()
        syncMediaSession()
        if (panel && episodePanel == null) showPanel()
    }

    private fun togglePlayback() =
        requestPlayback(
            !(if (playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay)
        )

    private fun seekTo(position: Long) {
        val p = player ?: return
        if (!playbackReady || p.duration <= 0) return
        main.removeCallbacks(seekRunnable)
        pendingSeek = null
        p.seekTo(position.coerceIn(0, (p.duration - 500).coerceAtLeast(0)))
        updatePlaybackText()
        syncMediaSession()
        showHud()
    }

    private fun skipEpisode(direction: Int) {
        if (!foreground || screen != "player") return
        val count = detail?.episodes?.size ?: return
        val target = episodeIndex + direction
        if (target in 0 until count) playEpisode(target)
    }

    private fun syncMediaSession() {
        if (!foreground || screen != "player") {
            mediaSession.deactivate()
            return
        }
        val data = detail ?: return
        val p = player
        val state =
            when {
                playError -> android.media.session.PlaybackState.STATE_ERROR
                sleepStopped || !requestedAutoplay || (p != null && !p.playWhenReady) ->
                    android.media.session.PlaybackState.STATE_PAUSED
                p == null || !playbackReady || p.playbackState == Player.STATE_BUFFERING ->
                    android.media.session.PlaybackState.STATE_BUFFERING
                p.playbackState == Player.STATE_ENDED ->
                    android.media.session.PlaybackState.STATE_STOPPED
                p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
                else -> android.media.session.PlaybackState.STATE_PAUSED
            }
        mediaSession.update(
            data.series.title,
            episodeIndex,
            p?.duration?.coerceAtLeast(0) ?: 0,
            currentPosition(),
            library.playbackSpeed,
            state,
            episodeIndex > 0,
            episodeIndex < data.episodes.lastIndex,
        )
    }

    private fun stopForSleep() {
        cancelNextSeason()
        sleepStopped = true
        requestedAutoplay = false
        retryAutoplay = false
        pausedForLifecycle = true
        cancelRecovery()
        cancelPrefetch()
        player?.pause()
        saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text = "定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud()
        if (!playError && episodePanel == null) showPanel()
        syncMediaSession()
        Toast.makeText(this, "定时停止已生效", Toast.LENGTH_LONG).show()
    }

    private fun showSleepPicker(anchor: TextView) {
        val labels =
            listOf("关闭定时停止", "15 分钟后", "30 分钟后", "60 分钟后", "90 分钟后", "本集播完", "再播完 3 集", "再播完 5 集")
        tvTools.choose(
            "定时停止 · ${sleepTimer.label()}",
            labels,
            anchor,
            { choice ->
                when (choice) {
                    0 -> sleepTimer.cancel()
                    in 1..4 -> sleepTimer.afterMinutes(listOf(15, 30, 60, 90)[choice - 1])
                    else -> sleepTimer.afterEpisodes(listOf(1, 3, 5)[choice - 5])
                }
                if (sleepTimer.episodesLeft == 1) cancelPrefetch()
                if (anchor.tag != "playback-settings") anchor.text = "定时 ${sleepTimer.label()}"
                updatePlaybackText()
                showHud()
            },
        )
    }

    private fun applyVideoRotation() {
        val view=playerView ?: return
        if(root.width<=0 || root.height<=0) return
        val canvas=VideoRotation.canvas(root.width,root.height,library.videoRotation)
        val params=view.layoutParams as FrameLayout.LayoutParams
        if(params.width!=canvas.width || params.height!=canvas.height) {
            params.width=canvas.width; params.height=canvas.height; params.gravity=Gravity.CENTER; view.layoutParams=params
        }
        view.rotation=library.videoRotation.toFloat()
    }
    private fun showRotationPicker(anchor: TextView) {
        tvTools.choose("画面旋转 · 仅旋转视频",VideoRotation.options.map { "${it}°" },anchor,{ index ->
            val previous=library.videoRotation; val next=VideoRotation.options[index]
            if(previous==next) return@choose
            val rebuild=screen=="player" && playerView!=null && VideoRotation.requiresSurfaceRebuild(previous,next)
            val selectedEpisode=episodeIndex; val position=currentPosition()
            val autoplay=if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay
            val ticket=generation
            library.videoRotation=next
            if(rebuild) {
                // Let the picker finish dismissal before replacing its anchor and the video view.
                // Rebuild only for a SurfaceView/TextureView transition, never on a layout callback.
                main.post {
                    if(screen=="player" && generation==ticket)
                        playEpisode(selectedEpisode,position,autoplay,recreateView=true)
                }
            } else applyVideoRotation()
            if(anchor.tag!="playback-settings") anchor.text="画面旋转  ·  ${library.videoRotation}°"
        })
    }
    private fun updatePersistentProgress() {
        if (persistentProgress == null || !library.isLoaded) return
        val visible=PlaybackProgressDisplay.visible(library.alwaysShowProgress,screen=="player",hud.visibility==View.VISIBLE,playError)
        persistentProgress?.visibility=if(visible) View.VISIBLE else View.GONE
        if(!visible) return
        val duration=player?.duration?.coerceAtLeast(0) ?: 0L
        val position=currentPosition().coerceAtLeast(0)
        persistentTime?.text=if(playbackReady) "${formatTime(position)} / ${if(duration>0) formatTime(duration) else "--:--"}" else "正在准备…"
        persistentBar?.progress=PlaybackProgressDisplay.value(position,duration)
    }
    private fun frameResizeMode() =
        when (library.frameMode) {
            VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

    private fun showFramePicker(anchor: TextView, inPlayer: Boolean) {
        tvTools.choose(
            "全局画面模式",
            listOf("完整画面 · 保留比例与全部内容", "等比铺满 · 会裁掉部分画面与字幕", "拉伸铺满 · 画面比例会改变"),
            anchor,
            { index ->
                library.frameMode = VideoFrameMode.entries[index]
                playerView?.resizeMode = frameResizeMode()
                if (anchor.tag != "playback-settings")
                    anchor.text = (if (inPlayer) "画面 " else "画面模式  ·  ") + library.frameMode.label
            },
            selected = library.frameMode.ordinal,
        )
    }

    private fun seek(direction: Int, repeat: Int) {
        val p = player ?: return
        if (!playbackReady) return
        val duration = p.duration
        if (duration <= 0) return
        val step = if (repeat > 4) 30000 else 10000
        pendingSeek =
            ((pendingSeek ?: p.currentPosition) + direction * step).coerceIn(
                0,
                (duration - 500).coerceAtLeast(0),
            )
        main.removeCallbacks(seekRunnable)
        main.postDelayed(seekRunnable, 250)
        updatePlaybackText()
        showHud()
    }

    private fun showPanel() {
        seasonBanner?.visibility = View.GONE
        if (playError) return
        panel = true
        showHud()
        controls.visibility = View.VISIBLE
        controls.removeAllViews()
        if (nextSeasonWork != null) addButton(controls, "取消本次跨季连播") {
            seasonSuppressed = true
            cancelNextSeason()
            showPanel()
        }

        val transport = row()
        controls.addView(transport)
        val play =
            addButton(transport, if (player?.playWhenReady == true) "暂停" else "播放") {
                    togglePlayback()
                }
                .also { transportPlay = it }
        addButton(transport, "上一集") { if (episodeIndex > 0) playEpisode(episodeIndex - 1) }
            .apply {
                isEnabled = episodeIndex > 0
                isFocusable = episodeIndex > 0
                alpha = if (episodeIndex > 0) 1f else .4f
            }
        addButton(transport, "下一集") {
                if (episodeIndex < (detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex + 1)
            }
            .apply {
                val enabled = episodeIndex < (detail?.episodes?.lastIndex ?: 0)
                isEnabled = enabled
                isFocusable = enabled
                alpha = if (enabled) 1f else .4f
            }
        val options = row().apply { setPadding(0, dp(6), 0, 0) }
        controls.addView(options)
        lateinit var episodes: TextView
        episodes = addButton(options, "选集") { showEpisodePanel(episodes) }
        lateinit var commentsButton: TextView
        commentsButton = addButton(options, "评论") { social.showComments(commentsButton) }
        lateinit var danmakuButton: TextView
        danmakuButton = addButton(options, "弹幕设置") { social.settings(danmakuButton) }
        lateinit var more: TextView
        more =
            addButton(options, "播放设置") {
                    tvTools.choose(
                        "播放设置",
                        listOf(
                            "倍速 ${PlaybackSpeed.label(library.playbackSpeed)}",
                            "清晰度 ${library.maxQuality}P",
                            "画面 ${library.frameMode.label}",
                            "定时 ${sleepTimer.label()}",
                            "伴机代解码 ${if (relayStore.enabled) "开" else "关"}",
                            "从头播放",
                            "重置设备能力记录",
                            "画面旋转 ${library.videoRotation}°",
                            "常驻播放进度 ${if (library.alwaysShowProgress) "开启" else "关闭"}",
                        ),
                        more,
                        { choice ->
                            main.post {
                                if (screen == "player" && more.isAttachedToWindow)
                                    when (choice) {
                                        0 -> showSpeedPicker(more)
                                        1 -> showQualityPicker(more, true)
                                        2 -> showFramePicker(more, true)
                                        3 -> showSleepPicker(more)
                                        4 -> showRelaySettings(more)
                                        5 -> {
                                            seekTo(0)
                                            hidePanel()
                                        }
                                        7 -> showRotationPicker(more)
                                        8 -> { library.alwaysShowProgress = !library.alwaysShowProgress; updatePersistentProgress() }
                                        6 -> {
                                            val count = CodecCapabilities.learnedCount()
                                            CodecCapabilities.resetLearned()
                                            android.util.Log.i(
                                                "HongguoTV",
                                                "Decoder capability lessons reset ($count entries)",
                                            )
                                            playbackText.text =
                                                if (count > 0) "已清除 $count 条设备能力记录，重新按解码器实际能力选流"
                                                else "暂无设备能力记录"
                                        }
                                    }
                            }
                        },
                    )
                }
                .apply { tag = "playback-settings" }
        play.requestFocus()
        showHud()
    }

    private fun showSpeedPicker(anchor: TextView, inPlayer: Boolean = true) {
        val speeds = PlaybackSpeed.options
        tvTools.choose("全局默认倍速", speeds.map(PlaybackSpeed::label), anchor, { index ->
            library.playbackSpeed = speeds[index]
            if (inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
            if (anchor.tag != "playback-settings")
                anchor.text = (if (inPlayer) "倍速 " else "默认倍速  ·  ") + PlaybackSpeed.label(library.playbackSpeed)
            if (inPlayer) { updatePlaybackText(); showHud() }
        }, selected = speeds.indexOf(library.playbackSpeed))
    }

    private fun showQualityPicker(anchor: TextView, inPlayer: Boolean) {
        val options = PlaybackQuality.options
        tvTools.choose("全局默认清晰度", options.map { "${it}P" }, anchor, { index ->
            val target = options[index]
            val changed = library.maxQuality != target
            library.maxQuality = target
            if (anchor.tag != "playback-settings")
                anchor.text = (if (inPlayer) "清晰度 " else "默认清晰度  ·  ") + "${target}P"
            if (inPlayer && changed)
                playEpisode(episodeIndex, currentPosition(), player?.playWhenReady ?: requestedAutoplay)
        }, selected = options.indexOf(library.maxQuality))
    }

    private fun showEpisodePanel(anchor: View) {
        val data = detail ?: return
        if (episodePanel != null) return
        val ticket = generation
        val overlayWasVisible = hud.visibility == View.VISIBLE
        requestedAutoplay = player?.playWhenReady ?: requestedAutoplay
        player?.pause()
        saveProgress()
        var resumeOnClose = true
        val next =
            EpisodePanel(
                this,
                data.episodes.size,
                episodeIndex,
                { current, view, located ->
                    tvTools.episodePicker(data.episodes.size, current, view, located)
                },
                { selected ->
                    if (selected == episodeIndex) episodePanel?.dismiss()
                    else {
                        resumeOnClose = false
                        episodePanel?.dismiss()
                        playEpisode(selected)
                    }
                },
                {
                    episodePanel = null
                    if (resumeOnClose && foreground && generation == ticket && screen == "player") {
                        if (requestedAutoplay && !sleepStopped && !pausedForLifecycle)
                            player?.play()
                        if (anchor.isAttachedToWindow) anchor.requestFocus()
                        if (overlayWasVisible) showHud() else hidePlaybackOverlay()
                    }
                },
                lastWatched =
                    library.progress(data.series.id)?.let {
                        data.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 }
                    },
                lastCompleted = library.progress(data.series.id)?.completed == true,
            )
        episodePanel = next
        next.show()
    }

    private fun hidePanel() {
        if (playError) return
        panel = false
        controls.visibility = View.GONE
        controls.clearFocus()
        showHud()
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) / 1000)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    // 正式播放器与首页均写入同一本机片库，并复用账户上传节流。
    private fun saveProgress(completed: Boolean = false) {
        val p = player ?: return
        val data = detail ?: return
        if (!playbackReady || episodeIndex !in data.episodes.indices) return
        val progress =
            WatchProgress(
                data.series,
                data.episodes[episodeIndex],
                episodeIndex,
                p.currentPosition.coerceAtLeast(0),
                p.duration.coerceAtLeast(0),
                completed || p.playbackState == Player.STATE_ENDED,
                System.currentTimeMillis(),
            )
        library.save(progress)
        cloudAccount.upload(
            CloudProgress(
                progress.series,
                progress.episodeId,
                progress.episodeIndex,
                progress.position,
                progress.duration,
                progress.updatedAt,
            ),
            data.episodes.size,
            completed || !p.isPlaying || !foreground,
        )
    }

    private fun cancelNextSeason() {
        seasonGeneration++
        seasonPending = false
        seasonBanner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        seasonBanner = null
        nextSeasonWork = null
    }

    private fun maybePrepareSeason() {
        val current = detail ?: return
        val p = player ?: return
        if (episodeIndex == current.episodes.lastIndex && p.duration > 0 &&
            p.currentPosition >= p.duration / 2 && p.isPlaying && !playError) offerNextSeason(current)
    }

    private fun updateSeasonBanner() {
        seasonBanner?.visibility = if (screen == "player" && !panel &&
            episodePanel == null && !tvTools.showing && !playError && !sleepStopped) View.VISIBLE else View.GONE
    }

    private fun offerNextSeason(current: Detail) {
        if (!library.seasonAutoplay || !library.autoNext || seasonSuppressed || seasonPending || !foreground) return
        seasonPending = true
        val token = ++seasonGeneration
        work({ RelatedWorks.nextSeason(current.series, repository.related(current.series.id)) }, { next ->
            if (token != seasonGeneration || !foreground || detail?.series?.id != current.series.id) return@work
            nextSeasonWork = next
            if (next != null) {
                val banner = text("本季结束后播放《${next.series.title}》 · 取消请按 ↑ 操作菜单", 13f).apply {
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    background = TvStyle.shape(this@MainActivity, Color.argb(175, 12, 18, 28))
                }
                seasonBanner = banner
                root.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    setMargins(dp(24), 0, dp(24), dp(20))
                })
                updateSeasonBanner()
            }
        }, { if (token == seasonGeneration) nextSeasonWork = null })
    }

    private fun finishSeason(current: Detail) {
        val next = nextSeasonWork ?: return
        if (!foreground || seasonSuppressed || !library.seasonAutoplay || !library.autoNext ||
            sleepStopped || pausedForLifecycle || detail?.series?.id != current.series.id) return
        val token = seasonGeneration
        playbackText.text = "正在加载下一季…"
        work({ repository.detail(next.series.id) }, { result ->
            if (token == seasonGeneration && foreground && !seasonSuppressed) {
                detailCloudProgress = null
                detail = result
                episodeIndex = 0
                group = 0
                playEpisode(0, 0, true)
            }
        }, { if (token == seasonGeneration) {
            cancelNextSeason()
            playbackText.text = "下一季暂时无法加载，请稍后手动选择"
            showPanel()
        } })
    }

    private fun releasePlayer(keepPrepared: Boolean = false) {
        cancelNextSeason()
        social.reset()
        cancelRecovery()
        cancelPrefetchState(keepPrepared)
        relayStopAsync()
        mediaSession.deactivate()
        tvTools.close()
        val wasForeground = foreground
        foreground = false
        val oldPanel = episodePanel
        episodePanel = null
        oldPanel?.dismiss()
        foreground = wasForeground
        main.removeCallbacks(hideHud)
        main.removeCallbacks(seekRunnable)
        pendingSeek = null
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        video?.close()
        video = null
        playerView?.player = null
        playerView = null
        persistentProgress = null
        persistentTime = null
        persistentBar = null
        player?.release()
        player = null
        playbackReady = false
        transportPlay = null
        sleepButton = null
        closeTransport(playbackHttp)
        playbackHttp = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun returnToDetail(forceDetail: Boolean = false) {
        sleepTimer.cancel()
        sleepStopped = false
        saveProgress()
        library.flush()
        invalidatePage()
        releasePlayer()
        if (directPlayback && !forceDetail) showCatalog()
        else {
            directPlayback = false
            showDetail(true)
        }
    }

    private fun goBack() {
        if (!library.isLoaded) {
            finish()
            return
        }
        if (screen == "catalog" && tab == 6) {
            if (settingsRows.any { it.hasFocus() }) { settingsSidebar.getOrNull(settingsGroup)?.requestFocus(); return }
            if (settingsSidebar.any { it.hasFocus() }) { nav[6].requestFocus(); return }
        }
        if (screen == "catalog" && tab == 0 && homeFeed?.back() == true) return
        if (screen == "catalog" && tab == 1 && tvSearchPanel != null) {
            returnToDiscovery()
            return
        }
        if (screen == "catalog" && tab == 1 && query.isBlank() && !nav[1].hasFocus()) {
            discoveryScreen?.backToTop()
            nav[1].requestFocus()
            return
        }
        when (screen) {
            "player" ->
                if (waitingForFirstFrame) returnToDetail()
                else if (hud.visibility == View.VISIBLE && !playError) hidePlaybackOverlay()
                else returnToDetail()
            "detail" -> {
                val previous = detailTrail.pollLast()
                if (previous == null) showCatalog()
                else {
                    detail = previous.detail
                    detailCloudProgress = previous.cloudProgress
                    detailCloudScope = previous.cloudScope
                    episodeIndex = previous.episode
                    group = previous.group
                    showDetail(false)
                }
            }
            else ->
                if (tab == 1 && query.isNotBlank()) returnToDiscovery()
                else if (collection != null) {
                    leaveCollection()
                    showCatalog()
                } else if (nav.any { it.hasFocus() }) {
                    if (tab != 0) {
                        switchTab(0)
                        nav[0].requestFocus()
                    } else finish()
                } else nav.getOrNull(tab)?.requestFocus()
        }
    }

    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed() = goBack()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screen == "player") {
            if (panel && !playError && event.action == KeyEvent.ACTION_DOWN) showHud()
            val key = event.keyCode
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) goBack()
                return true
            }
            if (
                key in
                    listOf(
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_STOP,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                    )
            ) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                    when (key) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                        KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                        KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1, 0)
                        KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1, 0)
                        else -> togglePlayback()
                    }
                return true
            }
            if (!panel) {
                if (
                    key in
                        listOf(
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_MENU,
                        )
                ) {
                    if (event.action == KeyEvent.ACTION_DOWN)
                        when (key) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1, event.repeatCount)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1, event.repeatCount)
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) togglePlayback()
                            KeyEvent.KEYCODE_DPAD_DOWN ->
                                if (event.repeatCount == 0) showEpisodePanel(hud)
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) showPanel()
                            else -> showHud()
                        }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!library.isLoaded) return
        when (screen) {
            "player" -> {
                val position = currentPosition()
                val autoplay = player?.playWhenReady == true && !pausedForLifecycle
                playEpisode(episodeIndex, position, autoplay)
            }
            "detail" -> if (detail != null) showDetail(false)
            else -> showCatalog()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        social.foreground(true)
        main.removeCallbacks(tick)
        main.post(tick)
        updater.resume()
        if (library.isLoaded) {
            if (initialCatalogPending) showCatalog(load = true)
            else if (restoreStoppedPage) {
                restoreStoppedPage = false
                when (screen) {
                    "player" -> playEpisode(episodeIndex, startPosition, autoplay = false)
                    "detail" ->
                        if (detail != null) showDetail(false)
                        else
                            pendingDetail?.let {
                                // 后台期间详情请求可能被取消；重试时保留显式云端续播来源。
                                val cloud = detailCloudProgress
                                if (
                                    directPlayback &&
                                        cloud != null &&
                                        (!cloudAccount.signedIn ||
                                            detailCloudScope != cloudAccount.historyScope)
                                ) {
                                    showCatalog()
                                } else {
                                    openDetail(
                                        it,
                                        directPlayback,
                                        autoplay = false,
                                        cloudProgress = cloud.takeIf { directPlayback },
                                    )
                                }
                            } ?: showCatalog()
                    else ->
                        showCatalog(
                            load =
                                !localCatalog() &&
                                    catalog.isEmpty() &&
                                    (tab == 0 || tab == 2 || tab == 1)
                        )
                }
            } else {
                val waiting = deferredWork
                deferredWork = null
                waiting?.apply?.invoke()
            }
            homeFeed?.resume()
            if (screen == "catalog" && tab != 0) favoriteMonitor.check()
            if (screen == "player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }

    override fun onPause() {
        stallGuard.suspendObservation()
        cancelNextSeason()
        searchSuggestions?.cancel()
        social.foreground(false)
        updater.pause()
        homeFeed?.pause()
        foreground = false
        main.removeCallbacks(tick)
        cancelRecovery()
        cancelPrefetch()
        mediaSession.deactivate()
        sleepTimer.cancel()
        favoriteMonitor.stop()
        detailAutoplay = false
        requestedAutoplay = false
        pausedForLifecycle = true
        player?.pause()
        saveProgress()
        library.flush()
        super.onPause()
    }

    // 应用离开前台时暂停播放及后台活动，防止页面不可见时继续消耗资源。
    override fun onStop() {
        homeFeed?.close()
        homeFeed = null
        restoreStoppedPage = true
        generation++
        cancelWork()
        tvTools.close()
        if (screen == "player") {
            startPosition = currentPosition()
            saveProgress()
            releasePlayer()
        }
        artwork.cancelPage(clearMemory = true)
        library.flush()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::artwork.isInitialized) artwork.trimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }

    // 页面销毁时释放播放器、请求和工作线程，避免资源继续持有旧 Activity。
    override fun onDestroy() {
        generation++
        cancelWork()
        if (networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        social.destroy()
        favoriteMonitor.destroy()
        tvTools.destroy()
        updater.destroy()
        saveProgress()
        cloudAccount.destroy()
        releasePlayer()
        library.close()
        artwork.close()
        releaseCatalogViews()
        mediaSession.release()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        rankWorker.shutdownNow()
        prefetchWorker.shutdownNow()
        networkCleanup.shutdown()
        Thread(
                {
                    repository.http.dispatcher.cancelAll()
                    repository.http.connectionPool.evictAll()
                    repository.http.dispatcher.executorService.shutdown()
                },
                "hongguotv-network-cleanup",
            )
            .start()
        super.onDestroy()
    }
}
