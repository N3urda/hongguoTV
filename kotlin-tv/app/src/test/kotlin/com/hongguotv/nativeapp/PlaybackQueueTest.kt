package com.hongguotv.nativeapp

import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackQueueTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun invoke(target: Any, name: String, vararg args: Any): Any? {
        val method = target.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        return if (name == "playEpisode") method.invoke(target, *args, false) else method.invoke(target, *args)
    }

    private fun blockDuplicateMetadataRequests(activity: MainActivity): AtomicInteger {
        val requests = AtomicInteger()
        val http =
            OkHttpClient.Builder()
                .addInterceptor {
                    requests.incrementAndGet()
                    throw java.io.IOException("测试禁止重复获取播放信息")
                }
                .build()
        set(
            activity,
            "repository",
            ContentRepository(http, playbackSession = testPlaybackDeviceSession()),
        )
        return requests
    }

    private class QueuePlayer {
        val items = mutableListOf(MediaItem.Builder().setMediaId("101").build())
        val calls = mutableListOf<String>()
        var index = 0
        val player =
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { _, method, args ->
                calls += method.name
                when (method.name) {
                    "getCurrentMediaItem" -> items.getOrNull(index)
                    "getMediaItemCount" -> items.size
                    "getCurrentMediaItemIndex" -> index
                    "getCurrentPosition" -> 0L
                    "getDuration" -> 90000L
                    "getPlaybackState" -> Player.STATE_READY
                    "getPlaybackParameters" -> PlaybackParameters(1f)
                    "getPlayWhenReady",
                    "isPlaying" -> true
                    "hasNextMediaItem" -> index + 1 < items.size
                    "addMediaSource" -> {
                        items += (args!![0] as MediaSource).mediaItem
                        null
                    }
                    "removeMediaItems" -> {
                        val start = args!![0] as Int
                        val end = args[1] as Int
                        items.subList(start, end).clear()
                        if (start < index) index -= (end - start)
                        null
                    }
                    "seekToNextMediaItem" -> {
                        index++
                        null
                    }
                    "clearMediaItems" -> {
                        items.clear()
                        index = 0
                        null
                    }
                    "setMediaSource" -> {
                        items.clear()
                        items += (args!![0] as MediaSource).mediaItem
                        index = 0
                        null
                    }
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            else -> null
                        }
                }
            } as ExoPlayer
    }

    private fun fixture(test: (MainActivity, QueuePlayer, Library, RemoteVideo) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            (get(get(activity, "cloudAccount")!!, "worker") as ExecutorService)
                .submit {}
                .get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            library.autoNext = true
            val data = Detail(Series("1", "接续测试", badge = "共3集"), listOf("101", "102", "103"))
            val fake = QueuePlayer()
            set(activity, "detail", data)
            set(activity, "screen", "player")
            set(activity, "foreground", true)
            set(activity, "player", fake.player)
            set(activity, "episodeIndex", 0)
            set(activity, "playbackReady", true)
            set(activity, "playbackTitle", TextView(activity))
            set(activity, "controls", LinearLayout(activity))
            set(activity, "hud", LinearLayout(activity))
            set(activity, "playbackText", TextView(activity))
            set(activity, "progressBar", android.widget.ProgressBar(activity))
            set(activity, "episodeDuration", 90000L)
            val remote =
                RemoteVideo(OkHttpClient(), StreamInfo("https://example.com/next", null, "720P"))
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<RemoteVideo>
            val key = "102:${library.maxQuality}"
            slot.complete(slot.begin(key), key, remote)
            test(activity, fake, library, remote)
        } finally {
            // No formal playback tick/network is started by these tests.
            set(activity, "foreground", false)
            controller.destroy()
        }
    }

    @Test
    fun upOpensTwoRowControlsWithoutPausingAndClockModesRemainIndependent() =
        fixture { activity, fake, library, _ ->
            val hud = LinearLayout(activity).apply { visibility = android.view.View.GONE }
            val controls = LinearLayout(activity)
            val clock = android.widget.TextClock(activity)
            set(activity, "hud", hud)
            set(activity, "controls", controls)
            set(activity, "playbackText", TextView(activity))
            set(activity, "progressBar", android.widget.ProgressBar(activity))
            set(activity, "playbackClock", clock)
            val before = fake.player.playWhenReady
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(android.view.View.VISIBLE, hud.visibility)
            assertEquals(true, get(activity, "panel"))
            assertEquals(android.view.View.VISIBLE, clock.visibility)
            assertEquals(2, controls.childCount)
            assertEquals(3, (controls.getChildAt(0) as LinearLayout).childCount)
            assertEquals(4, (controls.getChildAt(1) as LinearLayout).childCount)
            assertEquals(before, fake.player.playWhenReady)
            invoke(activity, "hidePlaybackOverlay")
            assertEquals(android.view.View.GONE, clock.visibility)
            library.clockMode = 2
            invoke(activity, "updateClock")
            assertEquals(android.view.View.VISIBLE, clock.visibility)
            library.clockMode = 0
            invoke(activity, "updateClock")
            assertEquals(android.view.View.GONE, clock.visibility)
        }

    @Test
    fun automaticTransitionReusesPlayerAndUpdatesEpisodeProgress() =
        fixture { activity, fake, library, remote ->
            invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
            assertEquals(listOf("101", "102"), fake.items.map { it.mediaId })
            fake.index = 1
            assertEquals(true, invoke(activity, "advanceQueuedEpisode", 90000L, true))
            assertEquals(android.view.View.GONE, (get(activity, "hud") as LinearLayout).visibility)
            invoke(activity, "playbackFirstFrame")
            assertEquals(android.view.View.GONE, (get(activity, "hud") as LinearLayout).visibility)
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(android.view.View.VISIBLE, (get(activity, "hud") as LinearLayout).visibility)
            assertEquals(1, get(activity, "episodeIndex"))
            assertSame(remote, get(activity, "video"))
            assertNull(get(activity, "queuedNext"))
            assertEquals(listOf("102"), fake.items.map { it.mediaId })
            assertEquals("102", library.progress("1")?.episodeId)
            assertEquals(0L, library.progress("1")?.position)
            assertFalse(
                fake.calls.any {
                    it in listOf("stop", "clearMediaItems", "setMediaSource", "prepare", "release")
                }
            )
        }

    @Test
    fun manualNextUsesQueuedSourceWithoutConsumingEpisodeSleepCount() =
        fixture { activity, fake, library, _ ->
            val timer = get(activity, "sleepTimer") as SleepTimer
            timer.afterEpisodes(3)
            invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
            invoke(activity, "playEpisode", 1, 0L, true, false, false, false)
            assertTrue(fake.calls.contains("seekToNextMediaItem"))
            assertEquals(true, invoke(activity, "advanceQueuedEpisode", 12000L, false))
            assertEquals(3, timer.episodesLeft)
            assertEquals("102", library.progress("1")?.episodeId)
            assertFalse(
                fake.calls.any {
                    it in listOf("stop", "clearMediaItems", "setMediaSource", "prepare")
                }
            )
        }

    @Test
    fun cancellationDropsOnlyQueuedSourceAndClosesItsResource() =
        fixture { activity, fake, library, remote ->
            invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
            invoke(activity, "cancelPrefetch")
            assertEquals(listOf("101"), fake.items.map { it.mediaId })
            assertNull(get(activity, "queuedNext"))
            assertThrows(java.io.IOException::class.java) { remote.read(0, 1) }
            assertFalse(fake.calls.contains("stop"))
        }

    @Test
    fun timerAndQualityMismatchPreventQueueing() = fixture { activity, fake, library, _ ->
        invoke(activity, "queuePreparedEpisode", "102:wrong-quality")
        assertEquals(1, fake.items.size)
        (get(activity, "sleepTimer") as SleepTimer).afterEpisodes(1)
        invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
        assertEquals(1, fake.items.size)
        assertNull(get(activity, "queuedNext"))
    }

    @Test
    fun switchingToAnInFlightNextEpisodeDoesNotCancelAndRestartItsPreparation() =
        fixture { activity, fake, library, remote ->
            val metadataRequests = blockDuplicateMetadataRequests(activity)
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<RemoteVideo>
            val key = "102:${library.maxQuality}"
            assertSame(remote, slot.take(key))
            val ticket = slot.begin(key)
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val worker = get(activity, "prefetchWorker") as ExecutorService
            val scope = RequestScope()
            val http = OkHttpClient()
            val job =
                worker.submit {
                    started.countDown()
                    finish.await(5, TimeUnit.SECONDS)
                    slot.complete(ticket, key, remote)
                }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                set(activity, "prefetchedFor", key)
                set(activity, "prefetchJob", job)
                set(activity, "prefetchScope", scope)
                set(activity, "prefetchHttp", http)
                set(activity, "playerView", PlayerView(activity))
                set(activity, "hud", LinearLayout(activity))
                set(activity, "playbackText", TextView(activity))
                set(activity, "progressBar", android.widget.ProgressBar(activity))
                invoke(activity, "playEpisode", 1, 0L, true, false, false, true)
                assertEquals(android.view.View.GONE, (get(activity, "hud") as LinearLayout).visibility)
                assertFalse(
                    "Switching to the next episode must retain its running request",
                    job.isCancelled,
                )
                finish.countDown()
                (get(activity, "workJob") as Future<*>).get(5, TimeUnit.SECONDS)
                ShadowLooper.idleMainLooper()
                invoke(activity, "playbackFirstFrame")
                assertEquals(android.view.View.GONE, (get(activity, "hud") as LinearLayout).visibility)
                assertSame(remote, get(activity, "video"))
                assertSame(http, get(activity, "playbackHttp"))
                assertEquals(1, get(activity, "episodeIndex"))
                assertEquals("102", fake.items.single().mediaId)
                assertEquals(0, metadataRequests.get())
            } finally {
                finish.countDown()
            }
        }

    @Test
    fun nextTransportKeepsSessionAndPlaybackTimeoutsButOwnsItsCancellation() =
        fixture { activity, _, _, _ ->
            val root = (get(activity, "repository") as ContentRepository).http
            val transport = invoke(activity, "newPrefetchTransport") as OkHttpClient
            try {
                assertSame(root.cookieJar, transport.cookieJar)
                assertEquals(root.connectTimeoutMillis, transport.connectTimeoutMillis)
                assertEquals(root.readTimeoutMillis, transport.readTimeoutMillis)
                assertEquals(root.callTimeoutMillis, transport.callTimeoutMillis)
                assertNotSame(root.dispatcher, transport.dispatcher)
                assertNotSame(root.connectionPool, transport.connectionPool)
                transport.dispatcher.cancelAll()
                assertFalse(root.dispatcher.executorService.isShutdown)
            } finally {
                transport.connectionPool.evictAll()
                transport.dispatcher.executorService.shutdown()
            }
        }

    @Test
    fun automaticRecoveryKeepsTheActualFailureVisible() = fixture { activity, _, _, _ ->
        val text = TextView(activity)
        set(activity, "hud", LinearLayout(activity))
        set(activity, "playbackText", text)
        set(activity, "progressBar", android.widget.ProgressBar(activity))
        invoke(
            activity,
            "playerFailure",
            PlaybackException(
                "fixture",
                EmptyPlaybackResponseException(),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ),
        )
        assertTrue(text.text.toString().contains("播放信息接口返回空响应"))
        assertTrue(get(activity, "playError") == true)
    }

    @Test
    fun leavingWhileWaitingCancelsPromotedRequestAndClosesALateResult() =
        fixture { activity, _, library, remote ->
            val metadataRequests = blockDuplicateMetadataRequests(activity)
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<RemoteVideo>
            val key = "102:${library.maxQuality}"
            assertSame(remote, slot.take(key))
            val ticket = slot.begin(key)
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val worker = get(activity, "prefetchWorker") as ExecutorService
            val root = (get(activity, "repository") as ContentRepository).http
            val http = invoke(activity, "newPrefetchTransport") as OkHttpClient
            val job =
                worker.submit {
                    started.countDown()
                    try {
                        finish.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // 模拟取消时已经在返回途中的结果，确保迟到资源也会被关闭。
                        finish.await(5, TimeUnit.SECONDS)
                    }
                    slot.complete(ticket, key, remote)
                }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                set(activity, "prefetchedFor", key)
                set(activity, "prefetchJob", job)
                set(activity, "prefetchScope", RequestScope())
                set(activity, "prefetchHttp", http)
                set(activity, "playerView", PlayerView(activity))
                set(activity, "hud", LinearLayout(activity))
                set(activity, "playbackText", TextView(activity))
                set(activity, "progressBar", android.widget.ProgressBar(activity))
                invoke(activity, "playEpisode", 1, 0L, true, false, false, false)
                assertFalse(job.isCancelled)
                invoke(activity, "invalidatePage")
                assertTrue(job.isCancelled)
                assertNull(get(activity, "promotedPreparation"))
                finish.countDown()
                worker.submit {}.get(5, TimeUnit.SECONDS)
                (get(activity, "networkCleanup") as ExecutorService)
                    .submit {}
                    .get(5, TimeUnit.SECONDS)
                ShadowLooper.idleMainLooper()
                assertFalse(slot.isFresh(key))
                assertThrows(java.io.IOException::class.java) { remote.read(0, 1) }
                assertTrue(http.dispatcher.executorService.isShutdown)
                assertFalse(root.dispatcher.executorService.isShutdown)
                assertEquals(0, metadataRequests.get())
            } finally {
                finish.countDown()
            }
        }
}
