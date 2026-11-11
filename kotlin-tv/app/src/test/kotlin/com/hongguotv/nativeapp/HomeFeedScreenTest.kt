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
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeFeedScreenTest {
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

    private class Playback(activity: Activity) : TrialPlayback {
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
        fun click(label: String) {
            texts(screen).first { it.text == label }.performClick()
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
        val player = Playback(activity)
        val screen = HomeFeedScreen(activity, state, listOf(TextView(activity)), {}, { _, _ -> }, { "收藏" }, backend, player)
        try {
            activity.setContentView(screen)
            screen.start(true, false)
            pump(screen)
            val stage = get(screen, "stage") as View
            repeat(2) { stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)) }
            assertEquals("3", state.current?.id)
            ShadowLooper.idleMainLooper(299, TimeUnit.MILLISECONDS)
            assertEquals(listOf("1"), backend.preparedIds.toList())
            pump(screen)
            assertEquals(listOf("1", "3"), backend.preparedIds.toList())
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
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
            assertTrue(f.hasText("试播恢复未成功"))
            assertTrue(f.hasText("G:b=200/0B/h2"))
            f.advance(60)
            assertEquals(3, f.reads())
            f.click("重试试播")
            assertEquals(4, f.reads())
            assertTrue(f.hasText("自动恢复 1/2"))
        }
    }

    @Test
    fun switchingWorksCancelsPendingTrialRecovery() {
        TrialRecoveryFixture { id, _ -> if (id == "1") EmptyPlaybackResponseException() else null }.use { f ->
            f.screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
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
            assertTrue(f.hasText("继续试播"))
        }
    }

    @Test
    fun immediateManualRetryCancelsOldCountdown() {
        TrialRecoveryFixture { _, count -> if (count == 1) EmptyPlaybackResponseException() else null }.use { f ->
            f.click("立即重试试播")
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
            assertTrue(f.hasText("重试试播"))
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
            texts(screen).first { it.text == "继续试播" }.performClick()
            assertTrue(playback.playing)
            screen.pause()
            screen.resume()
            assertTrue(playback.playing)
            // 用闸门固定 CI 中的时序：切换作品时，第二部作品仍在后台预加载。
            assertTrue(prefetchEntered.await(5, TimeUnit.SECONDS))
            screen.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
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
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            pump(screen)
            assertEquals("2", player.loaded)
            player.status?.invoke(TrialStatus.ENDED)
            assertEquals("2", state.current?.id)
            assertTrue(state.ended)
            assertTrue(screen.back())
            assertFalse(screen.back())
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
            finishPrefetch(screen)
            assertEquals(2, backend.prepares)
            assertEquals(setOf("2"), (get(screen, "cachedTrials") as Map<*, *>).keys)
            ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS)
            finishPrefetch(screen)
            assertEquals(setOf("2", "3"), (get(screen, "cachedTrials") as Map<*, *>).keys)
            ShadowLooper.idleMainLooper(4, TimeUnit.SECONDS)
            finishPrefetch(screen)
            assertEquals(4, backend.prepares)
            assertEquals(setOf("2", "3", "4"), (get(screen, "cachedTrials") as Map<*, *>).keys)
            val clears = player.clears
            val loads = player.loads
            val stage = get(screen, "stage") as View
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            pump(screen)
            assertEquals("2", player.loaded)
            assertEquals(4, backend.prepares)
            assertEquals(clears, player.clears)
            assertEquals(loads, player.loads)
            assertEquals(1, player.selections)
            assertTrue("预加载直切也必须记录当前展示作品", "2" in backend.displayedIds)
            stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
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
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val pending = get(screen, "prefetchJob") as Future<*>
            (get(screen, "stage") as View).dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
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
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            (get(screen, "stage") as View).dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
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
            texts(screen).first { it.text == "观看全集" }.performClick()
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
