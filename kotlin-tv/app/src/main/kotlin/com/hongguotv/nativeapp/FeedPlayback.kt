// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 首页试播的数据与播放器适配：将内容接口和 Media3 播放实现接入试播组件。

import android.content.Context
import android.view.View
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.io.Closeable
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

internal data class TrialClip(
    val detail: Detail,
    val video: RemoteVideo,
    val episodeCount: Int = detail.episodes.size,
    val track: VideoTracks.Track? = null,
    // 实际试播集的集内序号（1 起）：bytevc2 锁定集后移换集后 >1，界面据此改文案。
    val trialEpisodeIndex: Int = 1,
) : Closeable {
    val episodeId
        get() = detail.episodes.first()

    override fun close() = video.close()
}

internal interface FeedBackend : Closeable {
    fun catalog(category: DramaCategory, page: Int): CatalogPage

    fun categoryRecommendations(
        category: DramaCategory,
        page: Int,
        cursor: RecommendationCursor,
    ): CatalogPage = catalog(category, page)

    fun recommendations(page: Int, cursor: RecommendationCursor): CatalogPage =
        catalog(DramaCategory.COMIC, page)

    /** 仅供无新推荐后的主动恢复；category=null 表示推荐来源。 */
    fun resetRecommendations(category: DramaCategory? = null) = Unit

    fun detail(series: Series): Detail

    fun prepare(detail: Detail): TrialClip

    fun prefetch(detail: Detail): TrialClip = prepare(detail)

    fun trial(series: Series): TrialClip = prepare(detail(series))

    fun preload(series: Series): TrialClip = prefetch(detail(series))

    /** 首页展示过一部作品时通知后端，供下次冷启动跳过重复内容。 */
    fun noteDisplayed(
        series: Series,
        cursor: RecommendationCursor,
        category: DramaCategory? = null,
    ) = Unit
}

internal class PublicFeedBackend(
    private val quality: Int,
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectionPool(feedPool)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build(),
    account: AccountCredentials? = null,
    private val startupCache: FeedStartupCache? = null,
    private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared,
) : FeedBackend {
    companion object {
        // Reuse idle public TLS connections across homepage visits. Dispatchers remain per backend,
        // so cancelling an old page cannot cancel its replacement page's requests.
        private val feedPool = okhttp3.ConnectionPool(3, 2, TimeUnit.MINUTES)
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val repository = ContentRepository(http, playbackSession = playbackSession)
    private val recommendationAccount = account
    private val cacheIdentity =
        account?.let {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(it.cookie.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        } ?: "guest"
    private val trialHints = linkedMapOf<String, TrialHint>()
    private val startupPages = mutableMapOf<String, CatalogPage>()

    private fun rememberHints(page: CatalogPage): CatalogPage {
        synchronized(trialHints) {
            trialHints.putAll(page.trialHints)
            while (trialHints.size > 72) trialHints.remove(trialHints.keys.first())
        }
        return page
    }

    private fun <T> timed(stage: String, action: () -> T): T {
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            return action()
        } finally {
            android.util.Log.i(
                "HongguoTV",
                "Home $stage elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}",
            )
        }
    }

    private fun page(
        source: String,
        page: Int,
        cursor: RecommendationCursor,
        fetch: () -> CatalogPage,
    ): CatalogPage {
        if (page == 1) {
            // 冷启动先接上次的分页游标：服务端在没有 session_id 时永远返回同一批冷启动内容，
            // 只有把会话和 offset 带过去才能「下次进来接着刷」。
            if (!cursor.hasProgress) {
                startupCache?.loadCursor(cacheIdentity, source)?.let { saved ->
                    val ok = cursor.restore(saved)
                    android.util.Log.i("HongguoTV", "Home cursor-restore ok=$ok snapshotChars=${saved.length}")
                }
            }
            val resumed = cursor.hasProgress
            android.util.Log.i("HongguoTV", "Home cursor resumed=$resumed")
            if (!resumed)
                startupCache?.load(cacheIdentity, source)?.let { cached ->
                    if (cursor.restoreStarter(cached)) {
                        synchronized(trialHints) { startupPages[source] = cached }
                        android.util.Log.i("HongguoTV", "Home starter-cache hit")
                        return rememberHints(cached)
                    }
                }
        }
        val result = rememberHints(fetch())
        if (page == 1) {
            synchronized(trialHints) { startupPages[source] = result }
            startupCache?.save(cacheIdentity, source, result)
        }
        startupCache?.saveCursor(cacheIdentity, source, cursor.snapshot())
        android.util.Log.i("HongguoTV", "Home cursor-save bytes=${cursor.snapshot().length}")
        return result
    }

    override fun resetRecommendations(category: DramaCategory?) {
        if (closed.get()) return
        val source = category?.route ?: "recommend"
        synchronized(trialHints) { startupPages.remove(source) }
        startupCache?.clearSource(cacheIdentity, source)
    }

    override fun recommendations(page: Int, cursor: RecommendationCursor) =
        timed("recommendations") {
            page("recommend", page, cursor) {
                RecommendationFeed(http, recommendationAccount, cursor, playbackSession = playbackSession).page(page)
            }
        }

    override fun categoryRecommendations(
        category: DramaCategory,
        page: Int,
        cursor: RecommendationCursor,
    ) =
        timed("category") {
            page(category.route, page, cursor) {
                RecommendationFeed(http, recommendationAccount, cursor, category).page(page)
            }
        }

    override fun catalog(category: DramaCategory, page: Int) =
        repository.discover(category, page = page)

    override fun noteDisplayed(
        series: Series,
        cursor: RecommendationCursor,
        category: DramaCategory?,
    ) {
        if (closed.get()) return
        cursor.markSeen(series.id)
        // 同步落盘：这一条是「下次进来别再看到它」的唯一依据，异步写会和退出时的 close()
        // 抢跑，导致快照丢失。快照受 64 KiB 与最近 540 条去重记录限制。
        startupCache?.saveCursor(cacheIdentity, category?.route ?: "recommend", cursor.snapshot())
    }

    override fun detail(series: Series) = timed("detail") { repository.detail(series.id) }

    private fun open(id: String, speculative: Boolean = false): OpenedStream =
        openStream(
            http,
            repository,
            id,
            quality,
            CodecCapabilities::supports,
            initialBytes = 32 * 1024,
            speculative = speculative,
        ) {
            it == null || CodecCapabilities.supports(it)
        } ?: throw java.io.IOException("该作品暂时没有可试播的剧集")

    /** 试播集打开结果：流 + 实际集 id + 界面展示序号（1 起）。 */
    private data class OpenedTrial(
        val opened: OpenedStream,
        val episodeId: String,
        val displayIndex: Int,
    )

    // 第一集优先；bytevc2 锁定集（该集无电视可解码档）后移，最多探到第三集并回报实际序号。
    // 全部不可解时给确定性文案，不再报「暂时没有可试播的剧集」。
    private fun openTrialEpisode(series: Series, hint: TrialHint, speculative: Boolean): OpenedTrial {
        try {
            return OpenedTrial(timed("play-address") { open(hint.episodeId, speculative) }, hint.episodeId, 1)
        } catch (problem: NoPlayableQualityException) {
            val full = detail(series)
            val found =
                firstPlayableEpisode(full.episodes, 1) { open(it, speculative) }
                    ?: throw java.io.IOException("该剧暂无电视可解码的清晰度")
            return OpenedTrial(found.first, full.episodes[found.second], found.second + 1)
        }
    }

    override fun prepare(detail: Detail): TrialClip {
        if (detail.episodes.isEmpty()) throw java.io.IOException("该作品暂时没有可试播的剧集")
        val found =
            firstPlayableEpisode(detail.episodes, 0) { open(it) }
                ?: throw java.io.IOException("该剧暂无电视可解码的清晰度")
        return TrialClip(
            detail,
            found.first.video,
            track = found.first.track,
            trialEpisodeIndex = found.second + 1,
        )
    }

    override fun trial(series: Series): TrialClip = trial(series, speculative = false)

    private fun trial(series: Series, speculative: Boolean): TrialClip {
        val hint =
            synchronized(trialHints) { trialHints[series.id] }
                ?: run {
                    val detail = detail(series)
                    val first =
                        detail.episodes.firstOrNull() ?: throw java.io.IOException("该作品暂时没有可试播的剧集")
                    val verified = TrialHint(first, detail.episodes.size)
                    val updates =
                        synchronized(trialHints) {
                            trialHints[series.id] = verified
                            startupPages
                                .filterValues { page -> page.items.any { it.id == series.id } }
                                .map { (key, page) ->
                                    val updated =
                                        page.copy(
                                            trialHints = page.trialHints + (series.id to verified)
                                        )
                                    startupPages[key] = updated
                                    key to updated
                                }
                        }
                    updates.forEach { (source, page) ->
                        startupCache?.save(cacheIdentity, source, page)
                    }
                    verified
                }
        val openedTrial = openTrialEpisode(series, hint, speculative)
        return TrialClip(
            Detail(series, listOf(openedTrial.episodeId)),
            openedTrial.opened.video,
            hint.episodeCount,
            openedTrial.opened.track,
            openedTrial.displayIndex,
        )
    }

    // 后台只轻量准备一次，不消耗前台恢复额度；成功后立即交付视频索引。
    override fun preload(series: Series): TrialClip = trial(series, speculative = true)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Idle TLS socket closure may write close_notify. Never do it on Android's UI thread.
        Thread(
                {
                    http.dispatcher.cancelAll()
                    if (http.connectionPool !== feedPool) http.connectionPool.evictAll()
                    http.dispatcher.executorService.shutdown()
                },
                "hongguotv-feed-cleanup",
            )
            .start()
    }
}

internal enum class TrialStatus {
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

internal interface TrialPlayback {
    val view: View
    var status: ((TrialStatus) -> Unit)?

    fun load(clip: TrialClip, position: Long, autoplay: Boolean)

    fun setCurrentIndex(index: Int) {}

    fun queue(clip: TrialClip, index: Int) {}

    fun remove(seriesId: String) {}

    fun select(clip: TrialClip, position: Long, autoplay: Boolean): Boolean = false

    fun canPrefetch(): Boolean = true

    fun pause()

    fun resume()

    fun restart()

    fun position(): Long

    fun clear()

    fun release()

    /** 播放器级错误（含错误码），供上层做解码经验学习；status(ERROR) 仍会触发。默认 no-op。 */
    var onError: ((PlaybackException) -> Unit)?
        get() = null
        set(_) {}
}

/** 试播自愈的迭代上限：竖屏首播通常 1080P→720P→480P 两轮收敛，3 轮留出余量。 */
internal const val TRIAL_SELF_HEAL_LIMIT = 3

/**
 * 试播自愈判定：解码类失败 + 有可学习的轨道信息 + 该剧本次会话自愈次数未达上限。网络类错误
 * 与缺失轨道（无法定位学习键）不自愈。
 */
internal fun shouldSelfHealTrial(
    errorCode: Int?,
    hasTrack: Boolean,
    attempts: Int,
): Boolean =
    errorCode != null &&
        errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED &&
        hasTrack &&
        attempts < TRIAL_SELF_HEAL_LIMIT

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Media3TrialPlayback(context: Context) : TrialPlayback {
    private var currentIndex = 0
    private var preloading = false
    private val preloadBuilder =
        DefaultPreloadManager.Builder(
                context,
                TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { index ->
                    // Keep parsed tracks and existing samples during hand-off; NOT_PRELOADED clears
                    // them.
                    if (!preloading || index == currentIndex)
                        DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                    else
                        when (index - currentIndex) {
                            1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1500L)
                            -1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1000L)
                            2,
                            3 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                            else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
                        }
                },
            )
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2500, 10000, 500, 1000)
                    .setTargetBufferBytes(6 * 1024 * 1024)
                    .setPlayerTargetBufferBytes("preload", 4 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build()
            )
    private val preloadManager = preloadBuilder.build()
    private val player = preloadBuilder.buildExoPlayer()
    private val items = linkedMapOf<String, MediaItem>()
    private val surface =
        PlayerView(context).apply {
            useController = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            this.player = this@Media3TrialPlayback.player
        }

    override fun canPrefetch(): Boolean {
        val allowed = player.isPlaying && player.totalBufferedDuration >= 1500
        if (allowed != preloading) {
            preloading = allowed
            preloadManager.invalidate()
        }
        return allowed
    }

    override val view: View
        get() = surface

    override var status: ((TrialStatus) -> Unit)? = null
    override var onError: ((PlaybackException) -> Unit)? = null
    private var activeId: String? = null
    private var startedAt = 0L

    init {
        player.pauseAtEndOfMediaItems = true
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(
            object : Player.Listener {
                private fun active() =
                    activeId != null && player.currentMediaItem?.mediaId == activeId

                override fun onPlaybackStateChanged(value: Int) {
                    if (!active()) return
                    when (value) {
                        Player.STATE_BUFFERING -> status?.invoke(TrialStatus.BUFFERING)
                        Player.STATE_READY ->
                            status?.invoke(
                                if (player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                                    TrialStatus.READY
                                else TrialStatus.ERROR
                            )
                        Player.STATE_ENDED -> status?.invoke(TrialStatus.ENDED)
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (
                        active() &&
                            !playWhenReady &&
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                    )
                        status?.invoke(TrialStatus.ENDED)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (active()) {
                        // 先于 status(ERROR)：上层处理错误状态时需要已拿到错误码。
                        onError?.invoke(error)
                        status?.invoke(TrialStatus.ERROR)
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (active())
                        android.util.Log.i(
                            "HongguoTV",
                            "Home first-frame elapsedMs=${android.os.SystemClock.elapsedRealtime()-startedAt}",
                        )
                }
            }
        )
    }

    private fun key(clip: TrialClip) = clip.detail.series.id + ":" + clip.episodeId

    private fun source(clip: TrialClip) =
        ProgressiveMediaSource.Factory { VideoDataSource(clip.video) }
            .createMediaSource(
                MediaItem.Builder()
                    .setMediaId(key(clip))
                    .setUri("hongguotv://trial/${clip.episodeId}")
                    .setMimeType(MimeTypes.VIDEO_MP4)
                    .build()
            )

    override fun setCurrentIndex(index: Int) {
        currentIndex = index
        preloading = false
        preloadManager.setCurrentPlayingIndex(index)
        preloadManager.invalidate()
    }

    private fun register(clip: TrialClip, index: Int): MediaItem =
        items.getOrPut(key(clip)) {
            val source = source(clip)
            preloadManager.add(source, index)
            source.mediaItem
        }

    private fun play(clip: TrialClip, position: Long, autoplay: Boolean) {
        val item = register(clip, currentIndex)
        activeId = key(clip)
        startedAt = android.os.SystemClock.elapsedRealtime()
        player.setMediaSource(
            checkNotNull(preloadManager.getMediaSource(item)),
            position.coerceAtLeast(0),
        )
        player.prepare()
        player.playWhenReady = autoplay
    }

    override fun load(clip: TrialClip, position: Long, autoplay: Boolean) =
        play(clip, position, autoplay)

    override fun queue(clip: TrialClip, index: Int) {
        register(clip, index)
        preloadManager.invalidate()
    }

    override fun remove(seriesId: String) {
        val id = items.keys.firstOrNull { it.startsWith("$seriesId:") } ?: return
        if (id != activeId) items.remove(id)?.let { preloadManager.remove(it) }
    }

    override fun select(clip: TrialClip, position: Long, autoplay: Boolean): Boolean {
        if (key(clip) !in items) return false
        play(clip, position, autoplay)
        return true
    }

    override fun pause() {
        player.pause()
        preloading = false
        preloadManager.invalidate()
    }

    override fun resume() = player.play()

    override fun restart() {
        player.seekTo(0)
        player.play()
    }

    override fun position() = player.currentPosition.coerceAtLeast(0)

    override fun clear() {
        activeId = null
        preloading = false
        player.stop()
        player.clearMediaItems()
        preloadManager.reset()
        items.clear()
    }

    override fun release() {
        status = null
        surface.player = null
        player.release()
        preloadManager.release()
        items.clear()
    }
}
