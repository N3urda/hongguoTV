// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 首页试播组件：组装分类栏、视频区和操作栏，并管理试播请求及遥控器焦点。

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.*
import java.io.Closeable
import java.util.concurrent.Executors

/** 独立首页试播，不写入本机观看记录，也不上传账户进度。 */
internal class HomeFeedScreen(
    private val activity: Activity,
    private val state: DramaFeedState,
    private val nav: List<View>,
    private val watch: (Series) -> Unit,
    private val favorite: (Series, View) -> Unit,
    private val favoriteLabel: (String) -> String,
    private val backend: FeedBackend,
    private val playback: TrialPlayback,
    private val autoPreview: Boolean = true,
) : LinearLayout(activity), Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private var generation = 0
    private var scope: RequestScope? = null
    private var job: java.util.concurrent.Future<*>? = null
    private var prefetchScope: RequestScope? = null
    private var prefetchJob: java.util.concurrent.Future<*>? = null
    private var prefetchTarget: String? = null

    private data class CachedTrial(
        val clip: TrialClip,
        val savedAt: Long = android.os.SystemClock.elapsedRealtime(),
    )

    private val cachedTrials = linkedMapOf<String, CachedTrial>()
    private val attemptedPrefetch = mutableSetOf<String>()
    private var prefetchGeneration = 0
    private var attemptedBatch = 0
    private var prefetchSeries: String? = null
    private var prefetchReadyAt = 0L
    private var clip: TrialClip? = null
    private var closed = false
    private var active = false
    private var manuallyStarted: String? = null
    private var loading = false
    private var failed = false
    private var trialRecoveryAttempts = 0
    private var trialPreparationUntil = 0L
    private var trialRecovery: Runnable? = null
    private val trialRecoveryDelays = listOf(2000L, 5000L)
    private val exhaustedCursors = mutableSetOf<RecommendationCursor>()
    private var lastPlaybackError: androidx.media3.common.PlaybackException? = null
    private var healTarget: String? = null
    private var healAttempts = 0
    private val stage = FrameLayout(activity)
    private val title = label("发现下一部好剧", 25f)
    private val meta = label("第一集试播", 13f, TvStyle.accent)
    private val intro = label("喜欢这部作品，就选择观看全集。", 15f, TvStyle.muted)
    private val status = label("正在准备热门作品…", 15f)
    private val tag = label("热门发现", 12f, TvStyle.accent)
    private val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val buttons = mutableListOf<TextView>()
    private val pauseButton: TextView
    private val watchButton: TextView
    private lateinit var favoriteButton: TextView
    private val recommendButton: TextView
    private val comicButton: TextView
    private val realButton: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int = TvStyle.text) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private fun lp(w: Int = -1, h: Int = -2) = LayoutParams(w, h)

    private fun button(value: String, action: () -> Unit) =
        label(value, 14f).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = TvStyle.card(activity, false)
            setOnFocusChangeListener { _, _ -> paintButton(this) }
            setOnClickListener { action() }
        }

    private fun paintButton(button: TextView) {
        button.background = TvStyle.card(activity, button.hasFocus(), button.isSelected)
        button.setTextColor(if (button.isSelected) TvStyle.accent else TvStyle.text)
        button.setTypeface(null, if (button.isSelected) Typeface.BOLD else Typeface.NORMAL)
    }

    // 组装首页布局和点击事件；分类栏、内容区、操作栏按纵向排列。
    init {
        orientation = VERTICAL
        stage.id = View.generateViewId()
        val types = LinearLayout(activity)
        recommendButton =
            button("推荐") {
                if (!state.recommendation) {
                    clearCurrent()
                    clearCache()
                    state.recommend()
                    describe()
                    if (state.current != null) loadCurrent() else loadPage(false)
                }
            }
        comicButton = button("漫剧") { changeType(DramaCategory.COMIC) }
        realButton = button("真人剧") { changeType(DramaCategory.REAL) }
        val refresh =
            button("换一批") {
                clearCurrent()
                clearCache()
                val restart = exhaustedCursors.remove(state.recommendationCursor)
                state.refresh(restartCurrentSource = restart)
                describe()
                loadPage(false, resetSource = restart)
            }
        val typeChoices = listOf(recommendButton, comicButton, realButton, refresh)
        typeChoices.forEachIndexed { i, b ->
            types.addView(
                b,
                lp(0, dp(40)).apply {
                    weight = 1f
                    rightMargin = dp(8)
                },
            )
            b.nextFocusUpId = nav.first().id
            b.nextFocusDownId = stage.id
            b.nextFocusLeftId = typeChoices.getOrNull(i - 1)?.id ?: b.id
            b.nextFocusRightId = typeChoices.getOrNull(i + 1)?.id ?: b.id
        }
        addView(types, lp(-1, dp(48)))
        val content = LinearLayout(activity)
        stage.isFocusable = true
        stage.isFocusableInTouchMode = true
        stage.isClickable = true
        stage.setPadding(dp(3), dp(3), dp(3), dp(3))
        stage.background = TvStyle.shape(activity, Color.BLACK)
        stage.addView(playback.view, FrameLayout.LayoutParams(-1, -1))
        tag.setPadding(dp(12), dp(8), dp(12), dp(8))
        tag.background = TvStyle.shape(activity, Color.argb(210, 11, 15, 21))
        stage.addView(
            tag,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(10), dp(10), dp(10), 0)
            },
        )
        status.gravity = Gravity.CENTER
        status.setPadding(dp(14), dp(12), dp(14), dp(12))
        status.background = TvStyle.shape(activity, Color.argb(215, 11, 15, 21))
        stage.addView(
            status,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(dp(10), 0, dp(10), dp(10))
            },
        )
        content.addView(
            stage,
            lp(0, -1).apply {
                weight = 2.2f
                rightMargin = dp(22)
            },
        )
        val info =
            LinearLayout(activity).apply {
                orientation = VERTICAL
                setPadding(0, dp(14), 0, dp(10))
            }
        title.typeface = Typeface.DEFAULT_BOLD
        title.maxLines = 3
        title.ellipsize = TextUtils.TruncateAt.END
        info.addView(title)
        meta.maxLines = 2
        meta.setPadding(0, dp(12), 0, dp(12))
        info.addView(meta)
        intro.maxLines = 6
        intro.ellipsize = TextUtils.TruncateAt.END
        info.addView(intro)
        info.addView(Space(activity), lp(1, 0).apply { weight = 1f })
        info.addView(
            label("↑ ↓  换一部\n确定  打开操作\n返回  顶部导航", 13f, TvStyle.muted).apply {
                setLineSpacing(dp(6).toFloat(), 1f)
            }
        )
        content.addView(info, lp(0, -1).apply { weight = 1f })
        addView(content, lp(-1, 0).apply { weight = 1f })
        pauseButton = button("暂停试播") { toggle() }
        watchButton =
            button("观看全集") {
                state.current?.let {
                    remember()
                    pause()
                    watch(it)
                }
            }
        favoriteButton =
            button("账户收藏") {
                state.current?.let {
                    state.userPaused = true
                    playback.pause()
                    remember()
                    paintActions()
                    favorite(it, favoriteButton)
                }
            }
        buttons += listOf(pauseButton, watchButton, favoriteButton)
        buttons.forEachIndexed { index, b ->
            actions.addView(
                b,
                lp(0, dp(44)).apply {
                    weight = 1f
                    if (index < buttons.lastIndex) rightMargin = dp(10)
                },
            )
            b.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: b.id
            b.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: b.id
            b.nextFocusUpId = stage.id
            b.nextFocusDownId = b.id
        }
        actions.setPadding(0, dp(10), 0, 0)
        actions.visibility = View.GONE
        addView(actions)
        stage.nextFocusLeftId = stage.id
        stage.nextFocusRightId = stage.id
        stage.nextFocusUpId = stage.id
        stage.nextFocusDownId = stage.id
        nav.forEach {
            it.nextFocusDownId =
                (if (state.recommendation) recommendButton
                    else if (state.category == DramaCategory.COMIC) comicButton else realButton)
                    .id
        }
        stage.setOnFocusChangeListener { _, focused ->
            stage.background = TvStyle.shape(activity, Color.BLACK, if (focused) TvVisual.focusedValue else TvStyle.outline)
        }
        stage.setOnClickListener { showActions() }
        stage.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                        move(if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) showActions()
                    true
                }
                else -> false
            }
        }
        playback.onError = { error -> lastPlaybackError = error }
        playback.status = { value ->
            if (!closed && clip != null)
                when (value) {
                    TrialStatus.BUFFERING -> {
                        status.visibility = View.VISIBLE
                        status.text = "正在加载第一集…"
                    }
                    TrialStatus.READY -> {
                        status.visibility = if (state.userPaused) View.VISIBLE else View.GONE
                        failed = false
                        status.text = if (state.userPaused) "试播已暂停" else "第一集试播 · 喜欢就看全集"
                        paintActions()
                        prefetchUpcoming()
                    }
                    TrialStatus.ENDED -> {
                        status.visibility = View.VISIBLE
                        remember()
                        state.ended = true
                        state.userPaused = true
                        status.text = "本集试播结束 · 看全集或按 ↓ 换一部"
                        paintActions()
                        if (active && stage.hasFocus()) showActions()
                    }
                    TrialStatus.ERROR -> {
                        playback.pause()
                        if (!selfHealTrial()) {
                            failed = true
                            status.visibility = View.VISIBLE
                            status.text = "试播暂不可用 · 可重试、换剧或查看全集"
                        }
                        paintActions()
                    }
                }
        }
        describe()
        paintActions()
    }

    // 设置进入首页时的初始焦点，并在应用位于前台时启动试播流程。
    fun start(foreground: Boolean, focusNav: Boolean) {
        if (focusNav) nav[0].requestFocus() else stage.requestFocus()
        if (foreground) resume()
    }

    private val prefetchPoll =
        object : Runnable {
            override fun run() {
                if (!active || closed) return
                prefetchUpcoming()
                main.postDelayed(this, 250)
            }
        }

    fun resume() {
        if (closed || active) return
        active = true
        main.removeCallbacks(prefetchPoll)
        main.postDelayed(prefetchPoll, 250)
        if (clip != null) {
            if (!state.userPaused && !state.ended) playback.resume()
            paintActions()
            prefetchUpcoming()
        } else if (state.current != null) loadCurrent() else loadPage(false)
    }

    fun pause() {
        if (closed) return
        remember()
        active = false
        main.removeCallbacks(prefetchPoll)
        playback.pause()
        cancelRequest()
        cancelPrefetch()
        paintActions()
    }

    fun back(): Boolean {
        if (actions.visibility != View.VISIBLE) return false
        actions.visibility = View.GONE
        stage.requestFocus()
        return true
    }

    fun refreshAccountLabel() {
        if (!closed) paintActions()
    }

    // 遥控器确定键或点击视频后显示操作栏，并将焦点移到可用操作。
    private fun showActions() {
        actions.visibility = View.VISIBLE
        paintActions()
        (if (watchButton.isEnabled) watchButton else pauseButton).requestFocus()
    }

    private fun paintActions() {
        pauseButton.text =
            when {
                trialRecovery != null -> "立即重试试播"
                loading -> "正在准备试播…"
                failed || state.current == null -> "重试试播"
                state.ended -> "重新试播"
                state.userPaused -> "继续试播"
                else -> "暂停试播"
            }
        watchButton.isEnabled = state.current != null
        favoriteButton.isEnabled = state.current != null
        favoriteButton.text = state.current?.let { favoriteLabel(it.id) } ?: "账户收藏"
        recommendButton.isSelected = state.recommendation
        comicButton.isSelected = !state.recommendation && state.category == DramaCategory.COMIC
        realButton.isSelected = !state.recommendation && state.category == DramaCategory.REAL
        paintButton(recommendButton)
        paintButton(comicButton)
        paintButton(realButton)
        stage.keepScreenOn = active && clip != null && !state.userPaused && !state.ended && !failed
    }

    private fun describe() {
        val series = state.current
        title.text = series?.title ?: "发现下一部好剧"
        meta.text =
            if (series == null) "第一集试播"
            else
                listOf("第一集试播", series.badge, series.tags)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
        intro.text =
            series?.description?.ifBlank { "先试试看，喜欢这部作品就选择观看全集。" } ?: "选择漫剧或真人剧，上下换一部，找到想追的故事。"
        tag.text = if (state.recommendation) "推荐 · 第一集试播" else "${state.category.label}推荐 · 第一集试播"
        paintActions()
    }

    private fun remember() {
        if (clip != null) state.position = playback.position()
    }

    private var pendingTrial: Runnable? = null

    private fun cancelRequest() {
        pendingTrial?.let(main::removeCallbacks)
        pendingTrial = null
        trialRecovery?.let(main::removeCallbacks)
        trialRecovery = null
        generation++
        scope?.cancel()
        scope = null
        job?.cancel(true)
        job = null
        loading = false
    }

    private fun cancelPrefetch() {
        prefetchGeneration++
        prefetchScope?.cancel()
        prefetchScope = null
        prefetchJob?.cancel(true)
        prefetchJob = null
        prefetchTarget = null
        attemptedPrefetch.clear()
        attemptedBatch = 0
        prefetchSeries = null
        prefetchReadyAt = 0
    }

    private fun closeCached(cached: CachedTrial) {
        playback.remove(cached.clip.detail.series.id)
        cached.clip.close()
    }

    private fun clearCache() {
        cachedTrials.values.forEach(::closeCached)
        cachedTrials.clear()
    }

    private fun clearCurrent(
        retain: Boolean = false,
        keepPlayer: Boolean = false,
        keepPrefetch: Boolean = false,
    ) {
        remember()
        cancelRequest()
        if (!keepPrefetch) cancelPrefetch()
        if (!keepPlayer) playback.clear()
        clip?.let {
            if (retain && !failed) {
                cachedTrials.put(it.detail.series.id, CachedTrial(it))?.let(::closeCached)
            } else it.close()
        }
        clip = null
    }

    private fun takeCached(id: String): TrialClip? {
        val cached = cachedTrials.remove(id) ?: return null
        if (android.os.SystemClock.elapsedRealtime() - cached.savedAt > 90_000) {
            closeCached(cached)
            return null
        }
        return cached.clip
    }

    // 试播自愈：解码类失败写入设备能力经验表并自动换低档重载（每剧至多 3 轮，且仅在学到
    // 新失败键时继续，同一键反复重载没有意义）。重载复用 loadCurrent（缓存/预取优先），与
    // 手动重试成本相同。bytevc2 锁定集学完 1080P 键后，重载即落入确定性报错，不无限循环。
    private fun selfHealTrial(): Boolean {
        val series = state.current ?: return false
        val track = clip?.track
        if (healTarget != series.id) {
            healTarget = series.id
            healAttempts = 0
        }
        if (
            !shouldSelfHealTrial(
                lastPlaybackError?.errorCode,
                track != null,
                healAttempts,
            )
        )
            return false
        val knownBefore = CodecCapabilities.learnedCount()
        CodecCapabilities.learnFailure(track!!.fourcc, track.width, track.height)
        if (CodecCapabilities.learnedCount() == knownBefore) return false
        healAttempts += 1
        loadCurrent("设备不支持该清晰度，正在自动切换…")
        return true
    }

    // 只对当前作品的播放信息空响应恢复两次，不重试目录、确定性错误或后台预加载。
    // 每次仍沿用 Repository 的有界请求；切换、暂停和关闭通过 cancelRequest 取消倒计时。
    private fun recoverEmptyTrial(problem: Throwable): Boolean {
        if (generateSequence(problem) { it.cause }.take(8).none { it is EmptyPlaybackResponseException }) return false
        val delay = trialRecoveryDelays.getOrNull(trialRecoveryAttempts) ?: return false
        if (trialPreparationUntil - android.os.SystemClock.elapsedRealtime() <= delay) return false
        val target = state.current?.id ?: return false
        val token = generation
        val attempt = ++trialRecoveryAttempts
        val deadline = android.os.SystemClock.elapsedRealtime() + delay
        loading = true
        failed = false
        val retry = object : Runnable {
            override fun run() {
                if (closed || !active || token != generation || state.current?.id != target) return
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    trialRecovery = null
                    loadCurrent("正在恢复试播 · 第 $attempt/${trialRecoveryDelays.size} 次", recovering = true)
                } else {
                    status.visibility = View.VISIBLE
                    status.text = "播放信息暂时为空 · ${(remaining + 999) / 1000} 秒后自动恢复 $attempt/${trialRecoveryDelays.size}"
                    main.postDelayed(this, minOf(1000L, remaining))
                }
            }
        }
        trialRecovery = retry
        retry.run()
        paintActions()
        return true
    }

    // 后台发起请求，主线程更新界面；页面关闭、切换或暂停后丢弃过期结果。
    private fun <T> request(
        action: () -> T,
        discard: (T) -> Unit = {},
        recoverTrial: Boolean = false,
        done: (T) -> Unit,
    ) {
        cancelRequest()
        loading = true
        val token = generation
        val requestedCursor = state.recommendationCursor
        val requestScope = RequestScope()
        scope = requestScope
        job =
            worker.submit {
                try {
                    val result = requestScope.run {
                        if (!recoverTrial) action() else {
                            val remaining = trialPreparationUntil - android.os.SystemClock.elapsedRealtime()
                            if (remaining <= 0) throw java.net.SocketTimeoutException("试播准备超时")
                            RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remaining), action)
                        }
                    }
                    main.post {
                        if (closed || token != generation || !active) discard(result)
                        else {
                            loading = false
                            done(result)
                        }
                    }
                } catch (error: Exception) {
                    main.post {
                        if (closed || token != generation || !active) return@post
                        loading = false
                        if (recoverTrial && recoverEmptyTrial(error)) return@post
                        failed = true
                        status.visibility = View.VISIBLE
                        val message =
                            error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(90)
                        if (error is NoNewRecommendationsException) {
                            exhaustedCursors += requestedCursor
                            status.text = "推荐接口未返回新作品 · 最近已去重 ${error.rememberedCount} 部\n可稍后换一批，或切换漫剧、真人剧继续发现"
                        } else if (recoverTrial && trialRecoveryAttempts > 0) {
                            val detail = diagnosticDetail(error)?.take(180)
                            status.text = "试播恢复未成功 · ${playbackFailureSummary(error)}\n可重试试播或按 ↑ ↓ 换一部" +
                                (detail?.let { "\n$it" } ?: "")
                        } else status.text = "暂时无法加载 · ${message.ifBlank { "请重试或换一部" }}"
                        paintActions()
                    }
                }
            }
    }

    // 按当前推荐模式请求下一页；结果写入状态后，再加载当前作品的试播资源。
    private fun loadPage(advance: Boolean, resetSource: Boolean = false) {
        if (!active || closed || loading) return
        if (state.page >= 100 || (!state.hasMore && state.current != null)) {
            status.visibility = View.VISIBLE
            status.text = "本批作品已看完，可切换类型继续发现"
            return
        }
        status.visibility = View.VISIBLE
        status.text = "正在读取热门作品…"
        val page = state.page + 1
        val category = state.category
        val recommendation = state.recommendation
        val cursor = state.recommendationCursor
        request({
            if (resetSource) backend.resetRecommendations(if (recommendation) null else category)
            if (recommendation) backend.recommendations(page, cursor)
            else backend.categoryRecommendations(category, page, cursor)
        }) { result ->
            state.append(result, page)
            if (advance) state.move(1)
            if (state.current == null) {
                status.text = "暂时没有可试播的作品，可切换类型或重试"
                failed = true
                paintActions()
            } else loadCurrent()
        }
    }

    // 优先复用缓存或正在进行的预加载，避免为同一部作品重复请求。
    private fun loadCurrent(statusHint: String? = null, recovering: Boolean = false, settle: Boolean = false) {
        val series = state.current ?: return
        if (!active || closed) return
        if (!recovering) {
            trialRecoveryAttempts = 0
            trialPreparationUntil = android.os.SystemClock.elapsedRealtime() + 15_000
        }
        // 记下展示过的作品：冷启动时用它跳过重复内容，而不是每次都从同一部开始。
        noteDisplayed(series)
        clearCurrent(keepPrefetch = prefetchTarget == series.id)
        failed = false
        describe()
        status.visibility = View.VISIBLE
        status.text = statusHint ?: "正在准备第一集试播…"
        trimCache()
        val cached = takeCached(series.id)
        if (cached != null) attach(cached)
        else if (prefetchTarget == series.id && prefetchJob != null) {
            // 将正在进行的预加载转为当前试播，不取消后再重复请求地址。
            loading = true
            android.util.Log.i("HongguoTV", "Home promoted in-flight preload")
        } else if (settle) {
            // 快速连刷只更新卡片，停留三百毫秒后才发起未缓存作品的播放准备。
            loading = true
            val token = generation
            val pending = Runnable {
                pendingTrial = null
                if (!closed && active && token == generation && state.current?.id == series.id)
                    request({ backend.trial(series) }, { it.close() }, recoverTrial = true, done = ::attach)
            }
            pendingTrial = pending
            main.postDelayed(pending, 300)
        } else request({ backend.trial(series) }, { it.close() }, recoverTrial = true, done = ::attach)
    }

    private fun attach(next: TrialClip) {
        if (!autoPreview && manuallyStarted != next.detail.series.id) state.userPaused = true
        clip = next
        val series = next.detail.series
        val tags = series.tags.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        meta.text =
            if (next.trialEpisodeIndex > 1) {
                "第一集暂不支持电视播放 · 试播第 ${next.trialEpisodeIndex} 集 · 共 ${next.episodeCount} 集$tags"
            } else {
                "第一集试播 · 共 ${next.episodeCount} 集$tags"
            }
        if (series.description.isNotBlank()) intro.text = series.description
        val autoplay = active && !state.userPaused && !state.ended
        playback.setCurrentIndex(state.offset + state.index)
        if (!playback.select(next, state.position, autoplay)) {
            playback.load(next, state.position, autoplay)
            cachedTrials.values.forEach { queueCached(it.clip) }
        }
        paintActions()
    }

    private fun noteDisplayed(series: Series) =
        backend.noteDisplayed(
            series,
            state.recommendationCursor,
            if (state.recommendation) null else state.category,
        )

    private fun queueCached(clip: TrialClip) {
        val index = state.items.indexOfFirst { it.id == clip.detail.series.id }
        if (index >= 0) playback.queue(clip, state.offset + index)
    }

    private fun trimCache() {
        val allowed =
            state.items
                .subList(
                    (state.index - 1).coerceAtLeast(0),
                    minOf(state.index + 4, state.items.size),
                )
                .map { it.id }
                .toSet()
        val now = android.os.SystemClock.elapsedRealtime()
        val stale =
            cachedTrials
                .filter { it.key !in allowed || now - it.value.savedAt > 90_000 }
                .keys
                .toList()
        stale.forEach { cachedTrials.remove(it)?.let(::closeCached) }
    }

    /** 保留前一部与后三部缓存；当前就绪后先准备下一部，稳定三秒、六秒后再扩展。 */
    private fun prefetchUpcoming() {
        if (
            closed ||
                !active ||
                clip == null ||
                failed ||
                loading ||
                prefetchJob != null
        )
            return
        if (!playback.canPrefetch()) {
            prefetchReadyAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        trimCache()
        val now = android.os.SystemClock.elapsedRealtime()
        if (prefetchSeries != state.current?.id) {
            prefetchSeries = state.current?.id
            prefetchReadyAt = now
        }
        val depth = when {
            now - prefetchReadyAt >= 6000 -> 3
            now - prefetchReadyAt >= 3000 -> 2
            else -> 1
        }
        val upcoming = state.items.drop(state.index + 1).take(3)
        val next = upcoming.take(depth).firstOrNull { it.id !in cachedTrials && it.id !in attemptedPrefetch }
        if (next == null) {
            if (
                upcoming.size < depth &&
                    state.hasMore &&
                    state.page < 100 &&
                    attemptedBatch != state.page + 1
            )
                prefetchBatch()
            return
        }
        attemptedPrefetch += next.id
        prefetchTarget = next.id
        val token = prefetchGeneration
        val requestScope = RequestScope()
        prefetchScope = requestScope
        prefetchJob =
            worker.submit {
                val prepared =
                    runCatching { requestScope.run {
                        RequestScope.withDeadline(System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                            backend.preload(next)
                        }
                    } }.getOrNull()
                main.post {
                    if (closed || !active || token != prefetchGeneration) prepared?.close()
                    else {
                        prefetchJob = null
                        prefetchScope = null
                        prefetchTarget = null
                        if (state.current?.id == next.id && clip == null) {
                            loading = false
                            if (prepared != null) attach(prepared) else loadCurrent(recovering = true)
                        } else if (prepared != null) {
                            cachedTrials.put(next.id, CachedTrial(prepared))?.let(::closeCached)
                            queueCached(prepared)
                        }
                        prefetchUpcoming()
                    }
                }
            }
    }

    private fun prefetchBatch() {
        val page = state.page + 1
        attemptedBatch = page
        val category = state.category
        val recommendation = state.recommendation
        val cursor = state.recommendationCursor
        val token = prefetchGeneration
        val requestScope = RequestScope()
        prefetchScope = requestScope
        prefetchJob =
            worker.submit {
                val result =
                    runCatching {
                            requestScope.run {
                                if (recommendation) backend.recommendations(page, cursor)
                                else backend.categoryRecommendations(category, page, cursor)
                            }
                        }
                        .getOrNull()
                main.post {
                    if (closed || !active || token != prefetchGeneration) return@post
                    prefetchJob = null
                    prefetchScope = null
                    if (result != null && page == state.page + 1) {
                        state.append(result, page)
                        prefetchUpcoming()
                    }
                }
            }
    }

    private fun move(delta: Int) {
        if (closed || !active) return
        if (delta < 0 && state.index == 0) {
            status.visibility = View.VISIBLE
            status.text = "已到本次浏览的最早作品"
            return
        }
        if (delta > 0 && state.next == null) {
            if (loading) return
            if (!state.hasMore) {
                status.visibility = View.VISIBLE
                status.text = "本批作品已看完，可切换类型继续发现"
                return
            }
            clearCurrent()
            loadPage(true)
            return
        }
        val target = state.items.getOrNull(state.index + delta) ?: return
        val ready = takeCached(target.id)
        clearCurrent(
            retain = true,
            keepPlayer = ready != null,
            keepPrefetch = target.id == prefetchTarget,
        )
        if (state.move(delta)) {
            if (ready != null) {
                noteDisplayed(target)
                failed = false
                describe()
                status.visibility = View.VISIBLE
                status.text = "正在切换作品…"
                attach(ready)
                trimCache()
            } else loadCurrent(settle = true)
        }
    }

    private fun toggle() {
        if (loading && trialRecovery == null) return
        // 倒计时期间允许主动立即重试；取消旧定时任务，避免随后再次重复读取。
        if (trialRecovery != null) cancelRequest()
        manuallyStarted = state.current?.id
        if (failed || state.current == null || clip == null) {
            if (state.current == null) state.refresh()
            state.userPaused = false
            state.ended = false
            if (state.current == null) loadPage(false) else loadCurrent()
            return
        } else if (state.ended) {
            state.position = 0
            state.ended = false
            state.userPaused = false
            playback.restart()
        } else {
            state.userPaused = !state.userPaused
            if (state.userPaused) {
                remember()
                playback.pause()
            } else if (active) playback.resume()
        }
        status.visibility = if (state.userPaused) View.VISIBLE else View.GONE
        status.text = if (state.userPaused) "试播已暂停" else "第一集试播 · 喜欢就看全集"
        paintActions()
    }

    private fun changeType(category: DramaCategory) {
        if (!state.recommendation && state.category == category) return
        clearCurrent()
        state.select(category)
        clearCache()
        actions.visibility = View.GONE
        stage.requestFocus()
        describe()
        if (state.current != null) loadCurrent() else loadPage(false)
    }

    override fun close() {
        if (closed) return
        main.removeCallbacks(prefetchPoll)
        clearCurrent()
        clearCache()
        closed = true
        active = false
        stage.keepScreenOn = false
        playback.release()
        backend.close()
        worker.shutdownNow()
    }
}
