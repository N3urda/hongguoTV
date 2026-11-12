package com.hongguotv.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.*
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 23, 26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeFeedScreenTest {
    @Test fun pendingNextCardKeepsCurrentVideoAndCoalescesRapidRightPresses() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val state = DramaFeedState().apply { append(CatalogPage(listOf(Series("1", "第一部")), false), 1) }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val backend = object : Backend() {
            override fun recommendations(page: Int, cursor: RecommendationCursor): CatalogPage {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return CatalogPage((2..9).map { Series("$it", "作品$it") }, true)
            }
        }
        val player = Playback(activity.get(), prefetchEnabled = false)
        val screen = HomeFeedScreen(activity.get(), state, listOf(TextView(activity.get())), {}, { _, _ -> },
            { "收藏" }, backend, player)
        try {
            activity.get().setContentView(screen); screen.start(true, false); pump(screen)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stage = get(screen, "stage") as View
            repeat(3) { stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            assertEquals("1", state.current?.id)
            assertEquals(View.VISIBLE, (get(screen, "nextCard") as View).visibility)
            assertEquals("1", player.loaded)
            release.countDown()
            (get(screen, "catalogJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper(); pump(screen)
            assertEquals("2", state.current?.id)
            assertEquals("3", state.next?.id)
        } finally { release.countDown(); screen.close(); activity.pause().stop().destroy() }
    }

    @Test fun movingLeftCancelsThePendingRightAdvance() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val state = DramaFeedState().apply {
            append(CatalogPage(listOf(Series("1", "一"), Series("2", "二")), false), 1)
            move(1)
        }
        val release = java.util.concurrent.CountDownLatch(1)
        val backend = object : Backend() {
            override fun recommendations(page: Int, cursor: RecommendationCursor): CatalogPage {
                check(release.await(5, TimeUnit.SECONDS))
                return CatalogPage((3..10).map { Series("$it", "作品$it") }, true)
            }
        }
        val screen = HomeFeedScreen(activity.get(), state, listOf(TextView(activity.get())), {}, { _, _ -> },
            { "收藏" }, backend, Playback(activity.get(), prefetchEnabled = false))
        try {
            activity.get().setContentView(screen); screen.start(true, false); pump(screen)
            val stage = get(screen, "stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            pump(screen)
            assertEquals("1", state.current?.id)
            release.countDown()
            (get(screen, "catalogJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper(); pump(screen)
            assertEquals("1", state.current?.id)
            assertEquals(10, state.items.size)
        } finally { release.countDown(); screen.close(); activity.pause().stop().destroy() }
    }

    @Test fun metadataContinuesWhenVideoPreparationFailsAndTailBatchEnds() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val state = DramaFeedState().apply { append(CatalogPage(listOf(Series("1", "第一部")), false), 1) }
        val backend = object : Backend() {
            override fun recommendations(page: Int, cursor: RecommendationCursor) =
                CatalogPage((2..9).map { Series("$it", "作品$it") }, false)
            override fun prepare(detail: Detail): TrialClip = throw java.io.IOException("视频暂不可用")
        }
        val screen = HomeFeedScreen(activity.get(), state, listOf(TextView(activity.get())), {}, { _, _ -> },
            { "收藏" }, backend, Playback(activity.get(), prefetchEnabled = false))
        try {
            activity.get().setContentView(screen); screen.start(true, false); pump(screen)
            (get(screen, "catalogJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertEquals("1", state.current?.id)
            assertEquals("2", state.next?.id)
            assertEquals(9, state.items.size)
            assertEquals(View.VISIBLE, (get(screen, "nextCard") as View).visibility)
        } finally { screen.close(); activity.pause().stop().destroy() }
    }

    @Test fun warmedNextEpisodeIsUsedAtBoundaryAndReleasedOnClose() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val backend = Backend(4)
        val player = Playback(activity)
        val screen = HomeFeedScreen(activity, DramaFeedState(), listOf(TextView(activity)), {},
            { _, _ -> }, { "收藏" }, backend, player)
        try {
            activity.setContentView(screen); screen.start(true, false); pump(screen)
            player.totalDuration = 60000; player.saved = 40000
            player.status?.invoke(TrialStatus.READY)
            ShadowLooper.idleMainLooper(2250, TimeUnit.MILLISECONDS); finishPrefetch(screen)
            val warmed = get(screen, "nextEpisode") as TrialClip
            assertEquals(2, warmed.trialEpisodeIndex)
            val prepares = backend.prepares
            val clears = player.clears
            val previous = get(screen, "clip") as TrialClip
            player.status?.invoke(TrialStatus.ENDED); pump(screen)
            assertSame(warmed, get(screen, "clip"))
            assertEquals(prepares, backend.prepares)
            assertNull(get(screen, "nextEpisode"))
            assertEquals(clears, player.clears)
            assertTrue(get(previous.video, "closed") as Boolean)
            assertNull(get(screen, "heldEpisode"))
        } finally { screen.close(); controller.pause().stop().destroy() }
    }

    @Test fun waitingForNextEpisodeKeepsOldSourceUntilReplacement() = pendingEpisode(false)

    @Test fun leavingWhileNextEpisodeLoadsClosesOldAndLateSources() = pendingEpisode(true)

    private fun pendingEpisode(leave: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var late: TrialClip? = null
        val backend = object : Backend(1) {
            override fun episode(series: Series, index: Int): TrialClip {
                if (index == 1) {
                    entered.countDown()
                    // Model a response already arriving when cancellation happens.
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) {}
                    }
                }
                return super.episode(series, index).also { if (index == 1) late = it }
            }
        }
        val player = Playback(activity, prefetchEnabled = false)
        val screen = HomeFeedScreen(activity, DramaFeedState(), listOf(TextView(activity)), {},
            { _, _ -> }, { "收藏" }, backend, player)
        try {
            activity.setContentView(screen); screen.start(true, false); pump(screen)
            val previous = get(screen, "clip") as TrialClip
            val clears = player.clears
            player.status?.invoke(TrialStatus.ENDED)
            ShadowLooper.idleMainLooper()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(clears, player.clears)
            assertSame(previous, get(screen, "heldEpisode"))
            assertFalse(get(previous.video, "closed") as Boolean)
            if (leave) screen.close()
            release.countDown()
            if (leave) {
                (get(screen, "worker") as java.util.concurrent.ExecutorService).awaitTermination(5, TimeUnit.SECONDS)
                ShadowLooper.idleMainLooper()
                assertTrue(get(late!!.video, "closed") as Boolean)
                assertNull(get(screen, "clip"))
            } else {
                pump(screen)
                assertEquals(2, (get(screen, "clip") as TrialClip).trialEpisodeIndex)
                assertEquals(clears, player.clears)
            }
            assertTrue(get(previous.video, "closed") as Boolean)
            assertNull(get(screen, "heldEpisode"))
        } finally { release.countDown(); screen.close(); controller.pause().stop().destroy() }
    }

    @Test fun finalCheckpointCallbackCannotReenterClose() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val player = Playback(activity)
        var saves = 0
        lateinit var screen: HomeFeedScreen
        screen = HomeFeedScreen(activity, DramaFeedState(), listOf(TextView(activity)), {},
            { _, _ -> }, { "收藏" }, Backend(), player,
            saveProgress = { _, _, _ -> saves++; screen.close() })
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.status?.invoke(TrialStatus.READY)
            player.saved = 12000
            screen.close()
            assertEquals(1, saves)
            screen.close()
            assertEquals(1, saves)
        } finally { screen.close(); controller.pause().stop().destroy() }
    }

    @Test fun horizontalFeedFocusAndActionsUseTheSelectedSeries() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val player = Playback(activity)
        var opened = ""
        var relatedId = ""
        val nav = TextView(activity).apply { id = View.generateViewId(); isFocusable = true }
        val screen = HomeFeedScreen(activity, state, listOf(nav), { opened = it.id },
            { _, _ -> }, { "账户收藏" }, Backend(), player,
            related = { series, _ -> relatedId = series.id })
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            val stage = get(screen, "stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            assertEquals("1", state.current?.id)
            assertEquals(View.INVISIBLE, (get(screen, "previousCard") as View).visibility)
            assertEquals(View.VISIBLE, (get(screen, "nextCard") as View).visibility)
            assertTrue(texts(screen).first { it.text == "播放全集" }.hasFocus())
            assertTrue(screen.back())
            assertTrue(stage.hasFocus())
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            pump(screen)
            texts(screen).first { it.text == "相关剧集 / 预约" }.performClick()
            assertEquals(View.VISIBLE, (get(screen, "previousCard") as View).visibility)
            assertEquals("2", relatedId)
            assertFalse(player.playing)
            stage.performClick()
            assertEquals("", opened)
            assertTrue(player.playing)
            stage.performClick()
            assertFalse(player.playing)
            stage.performClick()
            assertTrue(player.playing)
            assertEquals(3, (get(screen, "actions") as ViewGroup).childCount)
            texts(screen).first { it.text == "播放全集" }.performClick()
            assertEquals("2", opened)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test fun watchingPersistsOnlyReadyPlaybackAndRestoresExactEpisode() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val player = Playback(activity)
        val saved = mutableListOf<WatchProgress>()
        val prior = WatchProgress(Series("1", "作品1"), "102", 1, 12000, 60000, false, 1)
        val screen = HomeFeedScreen(activity, DramaFeedState(), listOf(TextView(activity)), {},
            { _, _ -> }, { "收藏" }, Backend(), player,
            restoreProgress = { if (it == "1") prior else null },
            saveProgress = { progress, _, _ -> saved += progress })
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertEquals("102", (get(screen, "clip") as TrialClip).episodeId)
            assertEquals(12000L, player.saved)
            screen.pause()
            assertTrue("Preparing a clip must not create history", saved.isEmpty())
            screen.resume()
            player.status?.invoke(TrialStatus.READY)
            player.saved = 18000
            texts(screen).first { it.text == "播放全集" }.performClick()
            assertEquals("102", saved.last().episodeId)
            assertEquals(1, saved.last().episodeIndex)
            assertEquals(18000L, saved.last().position)
            assertFalse(saved.last().completed)
        } finally { screen.close(); controller.pause().stop().destroy() }
    }

    @Test fun disabledAutoNextStopsAtEpisodeBoundaryAndRecordsCompletion() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val player = Playback(activity)
        val saved = mutableListOf<WatchProgress>()
        val state = DramaFeedState()
        val screen = HomeFeedScreen(activity, state, listOf(TextView(activity)), {},
            { _, _ -> }, { "收藏" }, Backend(), player, autoNext = false,
            saveProgress = { progress, _, _ -> saved += progress })
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.status?.invoke(TrialStatus.READY)
            player.saved = 60000
            player.status?.invoke(TrialStatus.ENDED)
            pump(screen)
            assertTrue(state.ended)
            assertEquals(0, state.episodeIndex)
            assertTrue(saved.last().completed)
            screen.pause()
            screen.resume()
            pump(screen)
            assertEquals(0, state.episodeIndex)
            assertTrue(texts(screen).any { it.text.contains("已关闭自动下一集") })
        } finally { screen.close(); controller.pause().stop().destroy() }
    }

    @Test fun knownEmptyRelatedWorksDisableTheActionButNetworkFailureDoesNot() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val backend = object : Backend() {
            override fun related(series: Series): List<RelatedWork> =
                if (series.id == "1") emptyList() else throw java.io.IOException("offline")
        }
        val screen = HomeFeedScreen(activity, DramaFeedState(), listOf(TextView(activity)), {},
            { _, _ -> }, { "收藏" }, backend, Playback(activity))
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            (get(screen, "relatedJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertFalse((get(screen, "relatedButton") as View).isEnabled)
            (get(screen, "stage") as View).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            pump(screen)
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            (get(screen, "relatedJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertTrue((get(screen, "relatedButton") as View).isEnabled)
        } finally { screen.close(); controller.pause().stop().destroy() }
    }

    private open class Backend(private val count: Int = 2) : FeedBackend {
        private val prepareCount = java.util.concurrent.atomic.AtomicInteger()
        val prepares
            get() = prepareCount.get()

        val preparedIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val displayedIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
        var closed = false
        var recommendationReads = 0

        override fun recommendations(page: Int, cursor: RecommendationCursor): CatalogPage {
            recommendationReads++
            return catalog(DramaCategory.COMIC, page)
        }

        override fun catalog(category: DramaCategory, page: Int) =
            CatalogPage((1..count).map { Series("$it", "作品$it") }, false)

        override fun detail(series: Series) =
            Detail(series, listOf("${series.id}01", "${series.id}02"))

        override fun prepare(detail: Detail): TrialClip {
            prepareCount.incrementAndGet()
            preparedIds += detail.series.id
            return TrialClip(
                detail,
                RemoteVideo(OkHttpClient(), StreamInfo("https://example.com/video", null, "test")),
            )
        }

        override fun close() {
            closed = true
        }

        override fun noteDisplayed(series: Series, cursor: RecommendationCursor, category: DramaCategory?) {
            displayedIds += series.id
        }
    }

    private class Playback(activity: Activity, private val prefetchEnabled: Boolean = true) : TrialPlayback {
        override fun canPrefetch() = prefetchEnabled
        override val view = View(activity)
        override var status: ((TrialStatus) -> Unit)? = null
        val queued = mutableMapOf<String, TrialClip>()
        var selections = 0
        var clears = 0
        var loads = 0
        var loaded = ""
        var saved = 0L
        var playing = false
        var released = false

        override fun load(clip: TrialClip, position: Long, autoplay: Boolean) {
            loads++
            queued.clear()
            queued[clip.detail.series.id] = clip
            loaded = clip.detail.series.id
            saved = position
            playing = autoplay
        }

        override fun queue(clip: TrialClip, index: Int) {
            queued[clip.detail.series.id] = clip
        }

        override fun remove(seriesId: String) {
            queued.remove(seriesId)
        }

        override fun select(clip: TrialClip, position: Long, autoplay: Boolean): Boolean {
            if (clip.detail.series.id !in queued) return false
            selections++
            loaded = clip.detail.series.id
            saved = position
            playing = autoplay
            return true
        }

        var totalDuration = 0L
        override fun duration() = totalDuration
        override fun position() = saved

        override fun pause() {
            playing = false
        }

        override fun resume() {
            playing = true
        }

        override fun restart() {
            saved = 0
            playing = true
        }

        override fun clear() {
            clears++
            queued.clear()
            loaded = ""
            playing = false
        }

        override fun release() {
            released = true
        }
    }

    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun pump(screen: HomeFeedScreen) {
        repeat(4) {
            if (get(screen, "pendingTrial") != null) ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
            (get(screen, "job") as? Future<*>)?.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun finishPrefetch(screen: HomeFeedScreen) {
        repeat(8) {
            val pending = get(screen, "prefetchJob") as? Future<*> ?: return
            pending.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
        }
        fail("首页预缓存任务未完成")
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    private inner class TrialRecoveryFixture(
        autoPreview: Boolean = true,
        failure: (String, Int) -> Throwable?,
    ) : java.io.Closeable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val activity = controller.get()
        private val counts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val state = DramaFeedState()
        val player = Playback(activity)
        private val backend = object : Backend() {
            override fun trial(series: Series): TrialClip {
                val count = counts.computeIfAbsent(series.id) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                failure(series.id, count)?.let { throw it }
                return super.trial(series)
            }
        }
        val screen = HomeFeedScreen(activity, state, listOf(TextView(activity)), {}, { _, _ -> }, { "收藏" }, backend, player, autoPreview)

        init {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
        }

        fun reads(id: String = "1") = counts[id]?.get() ?: 0
        fun advance(seconds: Long) {
            ShadowLooper.idleMainLooper(seconds, TimeUnit.SECONDS)
            pump(screen)
        }
        fun hasText(part: String) = texts(screen).any { it.text.contains(part) }
        fun clickStage() {
            (get(screen, "stage") as View).performClick()
            pump(screen)
        }
        override fun close() {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun rapidSwitchesOnlyPrepareTheFinalUncachedWorkAndLeavingCancelsTheDelay() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend(5)
        // This test isolates foreground debounce; prefetch has its own tests below.
        // Otherwise a concurrent preload of work 4 can race with the close assertion.
        val player = Playback(activity, prefetchEnabled = false)
        val screen = HomeFeedScreen(activity, state, listOf(TextView(activity)), {}, { _, _ -> }, { "收藏" }, backend, player)
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            val stage = get(screen, "stage") as View
            repeat(2) { stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            assertEquals("3", state.current?.id)
            ShadowLooper.idleMainLooper(299, TimeUnit.MILLISECONDS)
            assertEquals(listOf("1"), backend.preparedIds.toList())
            pump(screen)
            assertEquals(listOf("1", "3"), backend.preparedIds.toList())
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            screen.close()
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            assertEquals(listOf("1", "3"), backend.preparedIds.toList())
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun emptyTrialAutomaticallyRecoversTwiceWithoutManualClicks() {
        TrialRecoveryFixture { _, count -> if (count <= 2) EmptyPlaybackResponseException() else null }.use { f ->
            assertEquals(1, f.reads())
            assertTrue(f.hasText("2 秒后自动恢复 1/2"))
            assertEquals("", f.player.loaded)
            f.advance(2)
            assertEquals(2, f.reads())
            assertTrue(f.hasText("5 秒后自动恢复 2/2"))
            f.advance(5)
            assertEquals(3, f.reads())
            assertEquals("1", f.player.loaded)
            assertTrue(f.player.playing)
            f.advance(20)
            assertEquals(3, f.reads())
        }
    }

    @Test
    fun persistentEmptyTrialStopsAfterTwoRecoveriesAndManualRetryStartsNewBudget() {
        TrialRecoveryFixture { _, _ -> EmptyPlaybackResponseException("G:b=200/0B/h2") }.use { f ->
            f.advance(2)
            f.advance(5)
            assertEquals(3, f.reads())
            assertTrue(f.hasText("播放恢复未成功"))
            assertFalse(f.hasText("G:b=200/0B/h2"))
            f.advance(60)
            assertEquals(3, f.reads())
            f.clickStage()
            assertEquals(4, f.reads())
            assertTrue(f.hasText("自动恢复 1/2"))
        }
    }

    @Test
    fun switchingWorksCancelsPendingTrialRecovery() {
        TrialRecoveryFixture { id, _ -> if (id == "1") EmptyPlaybackResponseException() else null }.use { f ->
            f.screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            pump(f.screen)
            assertEquals("2", f.player.loaded)
            f.advance(20)
            assertEquals(1, f.reads("1"))
            assertEquals(1, f.reads("2"))
        }
    }

    @Test
    fun pausingAndClosingCancelTrialRecoveryAndResumingGetsNewBudget() {
        TrialRecoveryFixture { _, _ -> EmptyPlaybackResponseException() }.use { f ->
            f.screen.pause()
            f.advance(20)
            assertEquals(1, f.reads())
            f.screen.resume()
            pump(f.screen)
            assertEquals(2, f.reads())
            assertTrue(f.hasText("自动恢复 1/2"))
            f.screen.close()
            f.advance(20)
            assertEquals(2, f.reads())
        }
    }

    @Test
    fun recoveredTrialHonorsDisabledAutoplay() {
        TrialRecoveryFixture(autoPreview = false) { _, count -> if (count == 1) EmptyPlaybackResponseException() else null }.use { f ->
            f.advance(2)
            assertEquals("1", f.player.loaded)
            assertFalse(f.player.playing)
            assertTrue((get(f.screen, "stage") as View).contentDescription.contains("继续播放"))
        }
    }

    @Test
    fun immediateManualRetryCancelsOldCountdown() {
        TrialRecoveryFixture { _, count -> if (count == 1) EmptyPlaybackResponseException() else null }.use { f ->
            f.clickStage()
            assertEquals("1", f.player.loaded)
            f.advance(20)
            assertEquals(2, f.reads())
        }
    }

    @Test
    fun nonEmptyResponseErrorsDoNotTriggerTrialRecovery() {
        TrialRecoveryFixture { _, _ -> ContentHttpException(403) }.use { f ->
            f.advance(20)
            assertEquals(1, f.reads())
            assertFalse(f.hasText("自动恢复"))
            assertTrue((get(f.screen, "stage") as View).contentDescription.contains("重试播放"))
        }
    }

    @Test
    fun disabledAutoplayStaysPausedAcrossSwitchesButAllowsManualPlay() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefetchEntered = java.util.concurrent.CountDownLatch(1)
        val finishPreload = java.util.concurrent.CountDownLatch(1)
        val backend = object : Backend() {
            override fun preload(series: Series): TrialClip {
                if (series.id == "2") {
                    prefetchEntered.countDown()
                    check(finishPreload.await(5, TimeUnit.SECONDS))
                }
                return super.preload(series)
            }
        }
        val state = DramaFeedState()
        val playback = Playback(activity)
        val screen = HomeFeedScreen(activity, state, listOf(TextView(activity)), {}, { _, _ -> }, { "收藏" }, backend, playback, autoPreview = false)
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertFalse(playback.playing)
            (get(screen, "stage") as View).performClick()
            assertTrue(playback.playing)
            screen.pause()
            screen.resume()
            assertTrue(playback.playing)
            // 用闸门固定 CI 中的时序：切换作品时，第二部作品仍在后台预加载。
            ShadowLooper.idleMainLooper(2250, TimeUnit.MILLISECONDS)
            assertTrue(prefetchEntered.await(5, TimeUnit.SECONDS))
            screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertEquals("2", state.current?.id)
            finishPreload.countDown()
            // pump 只等待前台 job；晋升的预加载由 prefetchJob 交付，必须单独等待。
            finishPrefetch(screen)
            pump(screen)
            assertEquals("2", playback.loaded)
            assertFalse(playback.playing)
            screen.pause()
            screen.resume()
            assertFalse(playback.playing)
        } finally {
            finishPreload.countDown()
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun refreshAfterExhaustionStartsFreshInsteadOfReusingStuckCursor() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        state.recommendationCursor.restore("""{"session":"stuck","offset":500,"seen":["1"]}""")
        var reset = false
        val backend = object : Backend() {
            override fun resetRecommendations(category: DramaCategory?) {
                assertNull(category)
                reset = true
            }
            override fun recommendations(page: Int, cursor: RecommendationCursor): CatalogPage {
                if (org.json.JSONObject(cursor.snapshot()).optString("session").isNotBlank())
                    throw NoNewRecommendationsException()
                val seen = org.json.JSONObject(cursor.snapshot()).getJSONArray("seen")
                assertEquals("1", seen.getString(0))
                return CatalogPage(listOf(Series("2", "新作品")), false)
            }
        }
        val player = Playback(activity)
        val screen = HomeFeedScreen(activity, state, listOf(TextView(activity)), {}, { _, _ -> }, { "收藏" }, backend, player)
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertTrue(texts(screen).any { it.text.contains("推荐接口未返回新作品") })
            texts(screen).first { it.text == "换一批" }.performClick()
            pump(screen)
            assertTrue(reset)
            assertEquals("2", player.loaded)
            assertEquals(0, org.json.JSONObject(state.recommendationCursor.snapshot()).optInt("offset"))
        } finally { screen.close(); controller.pause().stop().destroy() }
    }

    @Test
    fun transportCleanupRunsOffUiThreadAndOnlyOnce() {
        val finished = java.util.concurrent.CountDownLatch(1)
        val shutdowns = java.util.concurrent.atomic.AtomicInteger()
        var cleanupThread: Thread? = null
        val executor =
            object :
                java.util.concurrent.ThreadPoolExecutor(
                    0,
                    1,
                    1,
                    TimeUnit.SECONDS,
                    java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                ) {
                override fun shutdown() {
                    cleanupThread = Thread.currentThread()
                    shutdowns.incrementAndGet()
                    super.shutdown()
                    finished.countDown()
                }
            }
        val http = OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher(executor)).build()
        val backend = PublicFeedBackend(720, http)
        val ui = Thread.currentThread()
        backend.close()
        backend.close()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNotSame(ui, cleanupThread)
        assertEquals(1, shutdowns.get())
    }

    @Test
    fun remoteSwitchesWorksAndCompletionDoesNotAdvance() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend()
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertEquals("1", player.loaded)
            assertTrue(player.playing)
            // 可以提前读取推荐元数据，但此时只准备当前作品的视频。
            assertEquals(1, backend.prepares)
            val stage = get(screen, "stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            pump(screen)
            assertEquals("2", player.loaded)
            player.status?.invoke(TrialStatus.ENDED)
            assertEquals("2", state.current?.id)
            assertTrue(state.ended)
            player.status?.invoke(TrialStatus.ENDED)
            pump(screen)
            assertEquals("2", state.current?.id)
            assertEquals(1, state.episodeIndex)
            assertEquals("202", (get(screen, "clip") as TrialClip).episodeId)
            assertTrue(player.playing)
            assertFalse(state.ended)
            player.status?.invoke(TrialStatus.ENDED)
            pump(screen)
            assertTrue(state.ended)
            assertEquals(1, state.episodeIndex)
            assertFalse(screen.back())
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            pump(screen)
            assertEquals("1", state.current?.id)
            assertEquals(0, state.episodeIndex)
            assertEquals("101", (get(screen, "clip") as TrialClip).episodeId)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
        assertTrue(player.released)
        assertTrue(backend.closed)
    }

    @Test
    fun progressivelyPrebuffersThreeWorksAndReusesCachedNextAndPrevious() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend(6)
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertEquals(1, backend.prepares)
            player.status?.invoke(TrialStatus.READY)
            ShadowLooper.idleMainLooper(1999, TimeUnit.MILLISECONDS)
            assertEquals(1, backend.prepares)
            ShadowLooper.idleMainLooper(251, TimeUnit.MILLISECONDS)
            finishPrefetch(screen)
            assertEquals(2, backend.prepares)
            assertEquals(setOf("2"), (get(screen, "cachedTrials") as Map<*, *>).keys)
            ShadowLooper.idleMainLooper(6, TimeUnit.SECONDS)
            finishPrefetch(screen)
            assertEquals(setOf("2", "3"), (get(screen, "cachedTrials") as Map<*, *>).keys)
            ShadowLooper.idleMainLooper(8, TimeUnit.SECONDS)
            finishPrefetch(screen)
            assertEquals(4, backend.prepares)
            assertEquals(setOf("2", "3", "4"), (get(screen, "cachedTrials") as Map<*, *>).keys)
            val clears = player.clears
            val loads = player.loads
            val stage = get(screen, "stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            pump(screen)
            assertEquals("2", player.loaded)
            assertEquals(4, backend.prepares)
            assertEquals(clears, player.clears)
            assertEquals(loads, player.loads)
            assertEquals(1, player.selections)
            assertTrue("预加载直切也必须记录当前展示作品", "2" in backend.displayedIds)
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            pump(screen)
            assertEquals("1", player.loaded)
            assertEquals(4, backend.prepares)
            assertEquals(clears, player.clears)
            assertEquals(loads, player.loads)
            assertEquals(2, player.selections)
            screen.close()
            assertTrue((get(screen, "cachedTrials") as Map<*, *>).isEmpty())
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun switchingToInFlightPreloadDoesNotCancelOrResolveItTwice() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val backend =
            object : Backend(5) {
                override fun preload(series: Series): TrialClip {
                    if (series.id == "2") {
                        entered.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                    }
                    return super.preload(series)
                }
            }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val player = Playback(activity)
        val state = DramaFeedState()
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.status?.invoke(TrialStatus.READY)
            ShadowLooper.idleMainLooper(2250, TimeUnit.MILLISECONDS)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val pending = get(screen, "prefetchJob") as Future<*>
            (get(screen, "stage") as View).dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            )
            assertEquals("2", state.current?.id)
            assertFalse(pending.isCancelled)
            assertEquals(1, backend.prepares)
            finish.countDown()
            finishPrefetch(screen)
            pump(screen)
            assertEquals("2", player.loaded)
            assertEquals(1, backend.preparedIds.count { it == "2" })
            assertTrue(player.playing)
        } finally {
            finish.countDown()
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun promotedPreloadFailureFallsBackToForegroundRequest() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val backend =
            object : Backend(3) {
                override fun preload(series: Series): TrialClip {
                    if (series.id == "2") {
                        entered.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                        throw java.io.IOException("预加载失败")
                    }
                    return super.preload(series)
                }
            }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                DramaFeedState(),
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.status?.invoke(TrialStatus.READY)
            ShadowLooper.idleMainLooper(2250, TimeUnit.MILLISECONDS)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            (get(screen, "stage") as View).dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            )
            finish.countDown()
            finishPrefetch(screen)
            pump(screen)
            assertEquals("2", player.loaded)
            assertTrue(player.playing)
            assertEquals(1, backend.preparedIds.count { it == "2" })
        } finally {
            finish.countDown()
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun recommendationRefreshRemainsSeparateFromCategoryBrowsing() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend(10)
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            assertTrue(state.recommendation)
            assertEquals(1, backend.recommendationReads)
            texts(screen).first { it.text == "真人剧" }.performClick()
            pump(screen)
            assertFalse(state.recommendation)
            assertEquals(1, backend.recommendationReads)
            texts(screen).first { it.text == "推荐" }.performClick()
            pump(screen)
            assertTrue(state.recommendation)
            assertEquals(1, backend.recommendationReads)
            player.saved = 1200
            texts(screen).first { it.text == "换一批" }.performClick()
            pump(screen)
            assertEquals(2, backend.recommendationReads)
            assertEquals(0L, player.saved)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun openingFullSeriesAndReturningRestoresTrialPosition() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val backend = Backend()
        val player = Playback(activity)
        var watched = ""
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                { watched = it.id },
                { _, _ -> },
                { "账户收藏" },
                backend,
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.saved = 12345
            texts(screen).first { it.text == "播放全集" }.performClick()
            assertEquals("1", watched)
            assertEquals(12345L, state.position)
            assertFalse(player.playing)
            screen.resume()
            assertTrue(player.playing)
            screen.close()
            val nextPlayer = Playback(activity)
            val next =
                HomeFeedScreen(
                    activity,
                    state,
                    listOf(TextView(activity)),
                    {},
                    { _, _ -> },
                    { "账户收藏" },
                    Backend(),
                    nextPlayer,
                )
            try {
                activity.setContentView(next)
                next.start(true, false)
                pump(next)
                assertEquals(12345L, nextPlayer.saved)
            } finally {
                next.close()
            }
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun lifecyclePauseAndTypeSwitchStopPreviousTrial() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = DramaFeedState()
        val player = Playback(activity)
        val screen =
            HomeFeedScreen(
                activity,
                state,
                listOf(TextView(activity)),
                {},
                { _, _ -> },
                { "账户收藏" },
                Backend(),
                player,
            )
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            player.saved = 456
            screen.pause()
            assertFalse(player.playing)
            assertEquals(456L, state.position)
            screen.resume()
            assertTrue(player.playing)
            val comic = texts(screen).first { it.text == "漫剧" }
            val real = texts(screen).first { it.text == "真人剧" }
            val recommended = texts(screen).first { it.text == "推荐" }
            assertTrue(recommended.isSelected)
            assertFalse(comic.isSelected)
            assertFalse(real.isSelected)
            // resume 会启动其他作品的后台准备；仅统计当前播放器重载，
            // 避免后台任务的完成时机影响重复选择分类的断言。
            val loadsBefore = player.loads
            recommended.performClick()
            pump(screen)
            assertEquals(loadsBefore, player.loads)
            finishPrefetch(screen)
            assertEquals("1", player.loaded)
            assertEquals(456L, player.saved)
            real.performClick()
            pump(screen)
            assertTrue(real.isSelected)
            assertFalse(comic.isSelected)
            assertEquals(DramaCategory.REAL, state.category)
            assertEquals(0L, player.saved)
            comic.performClick()
            pump(screen)
            assertEquals(DramaCategory.COMIC, state.category)
            assertTrue(comic.isSelected)
            assertFalse(real.isSelected)
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }
}
