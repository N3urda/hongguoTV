package com.hongguotv.nativeapp

import android.view.KeyEvent
import android.view.View
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
        var position = 0L
        var playing = true
        var speed = 1f
        val player =
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { _, method, args ->
                calls += method.name
                when (method.name) {
                    "getApplicationLooper" -> android.os.Looper.getMainLooper()
                    "getAvailableCommands" -> Player.Commands.Builder().add(Player.COMMAND_SET_VIDEO_SURFACE).build()
                    "isCommandAvailable" -> args!![0] == Player.COMMAND_SET_VIDEO_SURFACE
                    "getVideoSize" -> VideoSize.UNKNOWN
                    "getCurrentMediaItem" -> items.getOrNull(index)
                    "getMediaItemCount" -> items.size
                    "getCurrentMediaItemIndex" -> index
                    "getCurrentPosition" -> position
                    "getDuration" -> 90000L
                    "getPlaybackState" -> Player.STATE_READY
                    "getPlaybackParameters" -> PlaybackParameters(speed)
                    "setPlaybackSpeed" -> { speed = args!![0] as Float; null }
                    "getPlayWhenReady",
                    "isPlaying" -> playing
                    "pause" -> { playing = false; null }
                    "play" -> { playing = true; null }
                    "setPlayWhenReady" -> { playing = args!![0] as Boolean; null }
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

    private fun fixture(test: (MainActivity, QueuePlayer, Library, OpenedStream) -> Unit) {
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
            set(activity, "requestedAutoplay", true)
            val root = get(activity, "root") as android.widget.FrameLayout
            root.removeAllViews()
            val hud = get(activity, "hud") as LinearLayout
            hud.orientation = LinearLayout.VERTICAL
            hud.addView(get(activity, "controls") as LinearLayout)
            root.addView(hud)
            root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 960, 540)
            val remote =
                RemoteVideo(OkHttpClient(), StreamInfo("https://example.com/next", null, "720P"))
            val rendition = com.hongguotv.core.Rendition(720, "h264", 720, 1280, remote.info.url, null)
            val lower = rendition.copy(quality = 480, width = 480, height = 854)
            val opened = OpenedStream(remote, rendition, listOf(rendition, lower), null)
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<OpenedStream>
            val key = "102:${library.maxQuality}"
            slot.complete(slot.begin(key), key, opened)
            test(activity, fake, library, opened)
        } finally {
            // No formal playback tick/network is started by these tests.
            set(activity, "foreground", false)
            controller.destroy()
        }
    }

    @Test
    fun releaseRetiresDecoderBeforeDetachingTheSurface() = fixture { activity, fake, _, _ ->
        val view = PlayerView(activity).apply { player = fake.player }
        set(activity, "playerView", view)
        fake.calls.clear()
        invoke(activity, "releasePlayer", false)
        assertTrue(fake.calls.indexOf("release") >= 0)
        assertTrue(fake.calls.indexOf("clearVideoSurfaceView") > fake.calls.indexOf("release"))
        assertNull(view.player)
    }

    @Test
    fun menuOpensSingleRowWithoutPausingAndClockModesRemainIndependent() =
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
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MENU))
            assertEquals(android.view.View.VISIBLE, hud.visibility)
            assertEquals(true, get(activity, "panel"))
            assertEquals(android.view.View.VISIBLE, clock.visibility)
            assertEquals(1, controls.childCount)
            assertEquals(6, (controls.getChildAt(0) as LinearLayout).childCount)
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
    fun longMenuPressConsumesRepeatedReleasesAndKeepsSettingsFocused() = fixture { activity, fake, _, _ ->
        val now = android.os.SystemClock.uptimeMillis()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0)))
        val controls = get(activity, "controls") as LinearLayout
        val settings = controls.findViewWithTag<TextView>("playback-settings")
        assertTrue(settings.requestFocus())

        val repeat = KeyEvent(now, now + 600L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 1)
        assertTrue(activity.dispatchKeyEvent(KeyEvent.changeFlags(repeat, KeyEvent.FLAG_LONG_PRESS)))
        val up = KeyEvent(now, now + 700L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0)
        assertTrue(activity.dispatchKeyEvent(up))
        assertTrue(activity.dispatchKeyEvent(up))

        assertEquals(true, get(activity, "panel"))
        assertSame(settings, controls.findFocus())
        assertTrue(settings.performClick())
        assertTrue((get(activity, "tvTools") as TvTools).showing)
        assertTrue(fake.playing)
    }

    @Test
    fun menuPanelIgnoresUnmatchedAndCanceledBackReleases() = fixture { activity, _, _, _ ->
        press(activity, KeyEvent.KEYCODE_MENU, repeats = 3)
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(true, get(activity, "panel"))

        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        assertTrue(activity.dispatchKeyEvent(down))
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        assertTrue(activity.dispatchKeyEvent(KeyEvent.changeFlags(up, KeyEvent.FLAG_CANCELED)))
        assertEquals(true, get(activity, "panel"))

        press(activity, KeyEvent.KEYCODE_BACK)
        assertEquals(false, get(activity, "panel"))
        assertEquals("player", get(activity, "screen"))
    }

    @Test
    fun episodePlayerPreloadsSamplesOfTheQueuedMediaItem() = fixture { activity, _, _, _ ->
        set(activity, "player", null)
        val player = invoke(activity, "ensurePlayer") as ExoPlayer
        assertEquals(2_000_000L, player.preloadConfiguration.targetPreloadDurationUs)
    }

    @Test
    fun overloadedSoftwareDecoderResumesTheSameEpisodeAtALowerQuality() = fixture { activity, fake, library, opened ->
        val requests = blockDuplicateMetadataRequests(activity)
        library.maxQuality = 1080
        val lower = requireNotNull(opened.rendition).copy(width = null, height = null)
        val higher = lower.copy(quality = 1080)
        set(activity, "attemptedQuality", 1080)
        set(activity, "currentTiers", listOf(higher))
        fake.position = 12_000L
        invoke(activity, "lowerSoftwareVideoQuality", fake.player)
        ShadowLooper.idleMainLooper()
        assertFalse((get(activity, "qualityRecovery") as PlaybackQualityRecovery).limited("101"))

        val tiers = listOf(higher, lower)
        set(activity, "currentTiers", tiers)
        set(activity, "playerView", PlayerView(activity))
        @Suppress("UNCHECKED_CAST")
        val slot = get(activity, "preparedNext") as PreparedSlot<OpenedStream>
        assertSame(opened, slot.take("102:${library.maxQuality}"))
        slot.complete(slot.begin("101:720"), "101:720", OpenedStream(opened.video, lower, tiers, opened.track))
        invoke(activity, "lowerSoftwareVideoQuality", fake.player)
        ShadowLooper.idleMainLooper()
        (get(activity, "workJob") as Future<*>).get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()

        assertEquals("101", fake.player.currentMediaItem?.mediaId)
        assertEquals(12_000L, get(activity, "startPosition"))
        assertEquals(720, get(activity, "attemptedQuality"))
        assertEquals("720P（自动降清）", get(activity, "quality"))
        assertEquals(1080, library.maxQuality)
        assertEquals(0, requests.get())
    }

    @Test
    fun overloaded720PSoftwareDecoderCanStillReduceWorkInsteadOfRemainingStuck() = fixture { activity, fake, library, opened ->
        val requests = blockDuplicateMetadataRequests(activity)
        library.maxQuality = 1080
        library.playbackSpeed = 2f
        val current = requireNotNull(opened.rendition).copy(codec = "h265", width = null, height = null)
        val lower = current.copy(quality = 480)
        val tiers = listOf(current, lower)
        set(activity, "attemptedQuality", 720)
        set(activity, "currentTrack", VideoTracks.Track("hvc1", 720, 1280))
        set(activity, "currentTiers", tiers)
        set(activity, "playerView", PlayerView(activity))
        fake.position = 12_000L
        @Suppress("UNCHECKED_CAST")
        val slot = get(activity, "preparedNext") as PreparedSlot<OpenedStream>
        slot.clear()
        slot.complete(slot.begin("101:480"), "101:480", OpenedStream(opened.video, lower, tiers, null))
        invoke(activity, "lowerSoftwareVideoQuality", fake.player)
        ShadowLooper.idleMainLooper()
        (get(activity, "workJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
        assertEquals("Sustained software overload must also recover below 720P", 480, get(activity, "attemptedQuality"))
        assertEquals(12_000L, get(activity, "startPosition"))
        assertEquals(1080, library.maxQuality)
        assertEquals(2f, fake.speed)
        assertEquals(0, requests.get())
    }

    @Test
    fun automaticTransitionReusesPlayerAndUpdatesEpisodeProgress() =
        fixture { activity, fake, library, opened ->
            set(activity, "attemptedQuality", 1080)
            set(activity, "autoDowngrades", 2)
            invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
            assertEquals(listOf("101", "102"), fake.items.map { it.mediaId })
            fake.index = 1
            assertEquals(true, invoke(activity, "advanceQueuedEpisode", 90000L, true))
            assertEquals(android.view.View.GONE, (get(activity, "hud") as LinearLayout).visibility)
            invoke(activity, "playbackFirstFrame")
            assertEquals(android.view.View.GONE, (get(activity, "hud") as LinearLayout).visibility)
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MENU))
            assertEquals(android.view.View.VISIBLE, (get(activity, "hud") as LinearLayout).visibility)
            assertEquals(1, get(activity, "episodeIndex"))
            assertSame(opened.video, get(activity, "video"))
            assertSame(opened.rendition, get(activity, "currentRendition"))
            assertEquals(opened.tiers, get(activity, "currentTiers"))
            assertEquals(720, get(activity, "attemptedQuality"))
            assertEquals(0, get(activity, "autoDowngrades"))
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

    private fun press(activity: MainActivity, key: Int, repeats: Int = 0) {
        val now = android.os.SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        repeat(repeats) { n -> activity.dispatchKeyEvent(KeyEvent(now, now + n + 1, KeyEvent.ACTION_DOWN, key, n + 1)) }
        activity.dispatchKeyEvent(KeyEvent(now, now + repeats + 1, KeyEvent.ACTION_UP, key, 0))
    }

    @Test
    fun downDownUpCoalescesToOneQueuedTransitionAndLongPressDoesNotSkip() = fixture { activity, fake, library, _ ->
        val hud = get(activity, "hud") as LinearLayout
        hud.visibility = View.GONE
        invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN, repeats = 8)
        assertEquals(View.GONE, hud.visibility)
        assertEquals(1, get(activity, "pendingEpisodeTarget"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(2, get(activity, "pendingEpisodeTarget"))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(1, get(activity, "pendingEpisodeTarget"))
        assertFalse(fake.calls.contains("seekToNextMediaItem"))
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertEquals(1, fake.calls.count { it == "seekToNextMediaItem" })
        assertEquals(true, invoke(activity, "advanceQueuedEpisode", 12000L, false))
        assertEquals(1, get(activity, "episodeIndex"))
        assertEquals(View.GONE, hud.visibility)
        invoke(activity, "playbackFirstFrame")
        assertEquals(View.GONE, hud.visibility)
        assertEquals("102", library.progress("1")?.episodeId)
        assertFalse(fake.calls.any { it in listOf("stop", "clearMediaItems", "prepare") })
    }

    @Test
    fun endpointsAndSingleEpisodeNeverReload() = fixture { activity, fake, _, _ ->
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("已经是第一集", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        set(activity, "episodeIndex", 2)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("已经是最后一集", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        set(activity, "detail", Detail(Series("one", "单集"), listOf("one-1")))
        set(activity, "episodeIndex", 0)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertNull(get(activity, "pendingEpisodeTarget"))
        assertFalse(fake.calls.any { it in listOf("stop", "prepare", "seekToNextMediaItem") })
    }

    @Test
    fun confirmPausesOpensMenuAndItsReleaseCannotClickResume() = fixture { activity, fake, _, _ ->
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER, repeats = 5)
        assertFalse(fake.playing)
        assertEquals(true, get(activity, "panel"))
        val controls = get(activity, "controls") as LinearLayout
        val primary = controls.getChildAt(0) as LinearLayout
        assertEquals(listOf("播放", "选集", "倍速 1×", "弹幕设置", "本集评论", "更多设置"), (0 until primary.childCount).map { (primary.getChildAt(it) as TextView).text.toString() })
        assertTrue(primary.getChildAt(0).hasFocus())
        primary.getChildAt(0).performClick()
        assertTrue(fake.playing)
        assertEquals(false, get(activity, "panel"))
    }

    @Test
    fun inlineEpisodesBackOnlyCollapsesAndPlayResumesWithoutDoubleToggle() = fixture { activity, fake, _, _ ->
        press(activity, KeyEvent.KEYCODE_MENU)
        val controls = get(activity, "controls") as LinearLayout
        val menu = controls.getChildAt(0) as LinearLayout
        val episodes = menu.getChildAt(1)
        episodes.performClick()
        assertFalse(fake.playing)
        assertEquals(2, controls.childCount)
        assertEquals(false, invoke(activity, "playerModalOpen"))
        press(activity, KeyEvent.KEYCODE_BACK)
        assertNull(get(activity, "episodePanel"))
        assertEquals(true, get(activity, "panel"))
        assertEquals(1, controls.childCount)
        assertTrue(fake.playing)
        episodes.performClick()
        assertFalse(fake.playing)
        menu.getChildAt(0).performClick()
        assertTrue(fake.playing)
        assertNull(get(activity, "episodePanel"))
        assertEquals(false, get(activity, "panel"))
    }

    @Test
    fun focusSurvivesFirstFrameAndTimeoutAndDirectionsDoNotSwitchEpisodes() = fixture { activity, _, _, _ ->
        press(activity, KeyEvent.KEYCODE_MENU)
        val controls = get(activity, "controls") as LinearLayout
        val selection = (controls.getChildAt(0) as LinearLayout).getChildAt(1)
        selection.requestFocus()
        set(activity, "waitingForFirstFrame", true)
        invoke(activity, "playbackFirstFrame")
        (get(activity, "hideHud") as Runnable).run()
        assertEquals(true, get(activity, "panel"))
        assertTrue(selection.hasFocus())
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertNull(get(activity, "pendingEpisodeTarget"))
        assertEquals(0, get(activity, "episodeIndex"))
    }

    @Test
    fun recoveryControlsAndModalOwnDirectionsEvenWithoutPanelFlag() = fixture { activity, _, _, _ ->
        val controls = get(activity, "controls") as LinearLayout
        controls.addView(TextView(activity).apply { text = "取消恢复"; isFocusable = true })
        controls.visibility = View.VISIBLE
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertNull(get(activity, "pendingEpisodeTarget"))
        controls.removeAllViews()
        val tools = get(activity, "tvTools") as TvTools
        tools.choose("测试", listOf("第一项", "第二项"), controls, {})
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertNull(get(activity, "pendingEpisodeTarget"))
        tools.close()
    }

    @Test
    fun leavingOrOpeningMenuCancelsPendingSwitch() = fixture { activity, fake, _, _ ->
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_MENU)
        assertNull(get(activity, "pendingEpisodeTarget"))
        invoke(activity, "hidePanel")
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        invoke(activity, "invalidatePage")
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertNull(get(activity, "pendingEpisodeTarget"))
        assertFalse(fake.calls.contains("seekToNextMediaItem"))
    }

    @Test
    fun automaticTransitionAndOlderDownEventDoNotSkipTwoEpisodes() = fixture { activity, fake, library, _ ->
        invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
        val pressedAt = android.os.SystemClock.uptimeMillis()
        fake.index = 1
        invoke(activity, "advanceQueuedEpisode", 90000L, true)
        activity.dispatchKeyEvent(KeyEvent(pressedAt, pressedAt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
        activity.dispatchKeyEvent(KeyEvent(pressedAt, pressedAt, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0))
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertEquals(1, get(activity, "episodeIndex"))
        assertFalse(fake.calls.contains("prepare"))
    }

    @Test
    fun pendingManualSwitchWinsOverAutomaticNext() = fixture { activity, fake, library, _ ->
        invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        invoke(activity, "playEpisode", 1, 0L, true, false, false, true)
        assertFalse(fake.calls.contains("seekToNextMediaItem"))
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertEquals(1, fake.calls.count { it == "seekToNextMediaItem" })
    }

    @Test
    fun manualSwitchFromPausedStateStartsPlayback() = fixture { activity, fake, library, _ ->
        invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
        fake.playing = false
        set(activity, "requestedAutoplay", false)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertTrue(fake.playing)
        assertEquals(true, get(activity, "requestedAutoplay"))
    }

    @Test
    fun sleepStopCancelsPendingManualSwitch() = fixture { activity, fake, _, _ ->
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        invoke(activity, "stopForSleep")
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertNull(get(activity, "pendingEpisodeTarget"))
        assertFalse(fake.playing)
        assertFalse(fake.calls.contains("seekToNextMediaItem"))
        assertEquals(0, get(activity, "episodeIndex"))
    }

    @Test
    fun playbackCommentsUseCurrentEpisodePreservePlaybackAndRestoreFocus() = fixture { activity, fake, _, _ ->
        val requested = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()
        val social = get(activity, "social\$delegate") as Lazy<*>
        val reader: (String, String, String) -> org.json.JSONObject = { series, episode, _ ->
            requested.put(series to episode)
            org.json.JSONObject().put("items", org.json.JSONArray()).put("hasMore", false)
        }
        set(social.value!!, "readComments", reader)
        for ((index, playing) in listOf(0 to true, 1 to false)) {
            set(activity, "episodeIndex", index)
            fake.playing = playing
            press(activity, KeyEvent.KEYCODE_MENU)
            val anchor = (get(activity, "controls") as LinearLayout).findViewWithTag<TextView>("comments")
            assertEquals("本集评论", anchor.contentDescription)
            anchor.requestFocus()
            fake.calls.clear()
            anchor.performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertEquals("1" to if (index == 0) "101" else "102", requested.poll(5, TimeUnit.SECONDS))
            assertEquals(playing, fake.playing)
            assertTrue(invoke(activity, "playerModalOpen") as Boolean)
            dialog.dismiss()
            ShadowLooper.idleMainLooper()
            assertTrue(anchor.hasFocus())
            assertEquals(playing, fake.playing)
            assertFalse(fake.calls.any { it in listOf("pause", "play", "stop", "setPlayWhenReady") })
        }
    }

    @Test
    fun playbackSettingsOffersDecoderSwitchBesideQuality() = fixture { activity, _, _, _ ->
        press(activity, KeyEvent.KEYCODE_MENU)
        val anchor = (get(activity, "controls") as LinearLayout).findViewWithTag<TextView>("playback-settings")
        invoke(activity, "showPlaybackSettings", anchor)
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        fun labels(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            else -> emptyList()
        }
        val titles = labels(dialog.window!!.decorView)
        val decoder = dialog.window!!.decorView.findViewWithTag<View>("choice-1")
        assertEquals("解码方式 硬解优先（自动回退）", decoder.contentDescription)
        assertTrue(titles.contains("自动降低清晰度 开启"))
        assertTrue(titles.any { it.startsWith("画面旋转") })
        assertTrue(titles.any { it.startsWith("常驻播放进度") })
        dialog.dismiss()
    }

    @Test
    fun phoneDirectionUsesNewShortcutAndBackClosesMenuBeforeLeavingHomePlayback() = fixture { activity, _, _, _ ->
        invoke(activity, "phoneControl", org.json.JSONObject().put("action", "key").put("value", "down"))
        assertEquals(1, get(activity, "pendingEpisodeTarget"))
        press(activity, KeyEvent.KEYCODE_MENU)
        set(activity, "homePlayback", true)
        invoke(activity, "goBack")
        assertEquals(false, get(activity, "panel"))
        assertEquals("player", get(activity, "screen"))
        assertNull(get(activity, "pendingEpisodeTarget"))
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
        fixture { activity, fake, library, opened ->
            invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
            invoke(activity, "cancelPrefetch")
            assertEquals(listOf("101"), fake.items.map { it.mediaId })
            assertNull(get(activity, "queuedNext"))
            assertThrows(java.io.IOException::class.java) { opened.video.read(0, 1) }
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
        fixture { activity, fake, library, opened ->
            val metadataRequests = blockDuplicateMetadataRequests(activity)
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<OpenedStream>
            val key = "102:${library.maxQuality}"
            assertSame(opened, slot.take(key))
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
                    slot.complete(ticket, key, opened)
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
                assertSame(opened.video, get(activity, "video"))
                assertSame(opened.rendition, get(activity, "currentRendition"))
                assertSame(http, get(activity, "playbackHttp"))
                assertEquals(1, get(activity, "episodeIndex"))
                assertEquals("102", fake.items.single().mediaId)
                assertEquals(0, metadataRequests.get())
            } finally {
                finish.countDown()
            }
        }

    @Test
    fun aSlowPrefetchLeavesTimeForForegroundPlayback() =
        fixture { activity, _, library, opened ->
            val metadataRequests = blockDuplicateMetadataRequests(activity)
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<OpenedStream>
            val key = "102:${library.maxQuality}"
            assertSame(opened, slot.take(key))
            val ticket = slot.begin(key)
            val unfinished = java.util.concurrent.CompletableFuture<Unit>()
            set(activity, "prefetchedFor", key)
            set(activity, "prefetchJob", unfinished)
            set(activity, "prefetchScope", RequestScope())
            set(activity, "prefetchHttp", invoke(activity, "newPrefetchTransport"))
            set(activity, "playerView", PlayerView(activity))
            set(activity, "hud", LinearLayout(activity))
            set(activity, "playbackText", TextView(activity))
            set(activity, "progressBar", android.widget.ProgressBar(activity))

            invoke(activity, "playEpisode", 1, 0L, true, false, false, false)
            (get(activity, "workJob") as Future<*>).get(3, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()

            assertTrue(unfinished.isCancelled)
            assertEquals(1, metadataRequests.get())
            assertFalse(slot.complete(ticket, key, opened))
            assertThrows(java.io.IOException::class.java) { opened.video.read(0, 1) }
        }

    @Test
    fun manualSwitchKeepsOverlayHiddenDuringPreparationAndFirstFrame() =
        fixture { activity, fake, library, opened ->
            val metadataRequests = blockDuplicateMetadataRequests(activity)
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<OpenedStream>
            val key = "102:${library.maxQuality}"
            assertSame(opened, slot.take(key))
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
                    slot.complete(ticket, key, opened)
                }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                set(activity, "prefetchedFor", key)
                set(activity, "prefetchJob", job)
                set(activity, "prefetchScope", scope)
                set(activity, "prefetchHttp", http)
                set(activity, "playerView", PlayerView(activity))
                set(activity, "hud", LinearLayout(activity).apply { visibility = View.GONE })
                set(activity, "playbackText", TextView(activity))
                set(activity, "progressBar", android.widget.ProgressBar(activity))
                invoke(activity, "playEpisode", 1, 0L, true, false, false, false)
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
                assertSame(opened.video, get(activity, "video"))
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
    fun bothEmptyModelsKeepAutomaticRecoveryAfterTheFastAttempts() = fixture { activity, _, _, _ ->
        val status = TextView(activity)
        set(activity, "hud", LinearLayout(activity))
        set(activity, "playbackText", status)
        set(activity, "progressBar", android.widget.ProgressBar(activity))
        set(activity, "waitingForFirstFrame", true)
        val budget = get(activity, "recovery") as RecoveryBudget
        repeat(budget.fastAttempts) { budget.consume() }

        invoke(activity, "playerFailure", EmptyPlaybackResponseException("stage=mobile-model", allPlaybackModelsEmpty = true))

        assertTrue(get(activity, "autoRecovery") as Boolean)
        assertTrue(get(activity, "waitForEmptyPlaybackModels") as Boolean)
        assertFalse(status.text.contains("快速恢复暂未成功"))
    }

    @Test
    fun leavingWhileWaitingCancelsPromotedRequestAndClosesALateResult() =
        fixture { activity, _, library, opened ->
            val metadataRequests = blockDuplicateMetadataRequests(activity)
            @Suppress("UNCHECKED_CAST")
            val slot = get(activity, "preparedNext") as PreparedSlot<OpenedStream>
            val key = "102:${library.maxQuality}"
            assertSame(opened, slot.take(key))
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
                    slot.complete(ticket, key, opened)
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
                assertThrows(java.io.IOException::class.java) { opened.video.read(0, 1) }
                assertTrue(http.dispatcher.executorService.isShutdown)
                assertFalse(root.dispatcher.executorService.isShutdown)
                assertEquals(0, metadataRequests.get())
            } finally {
                finish.countDown()
            }
        }
    @Test fun disablingAutomaticQualityKeepsFullScreenAndFeedAtTheRequestedQualityDuringOverload() = fixture { activity, fake, library, opened ->
        library.maxQuality = 1080
        library.autoQualityDowngrade = false
        val requests = blockDuplicateMetadataRequests(activity)
        set(activity, "attemptedQuality", 1080)
        set(activity, "currentTiers", listOf(opened.rendition!!.copy(quality = 1080), opened.rendition))
        fake.position = 12_000L
        invoke(activity, "lowerSoftwareVideoQuality", fake.player)
        ShadowLooper.idleMainLooper()
        assertEquals(1080, get(activity, "attemptedQuality"))
        assertEquals(12_000L, fake.position)
        assertFalse((get(activity, "qualityRecovery") as PlaybackQualityRecovery).limited("101"))
        assertEquals(0, requests.get())
        val feed = PublicFeedBackend(1080, autoQualityDowngrade = false)
        try {
            val clip = TrialClip(Detail(Series("1", "测试"), listOf("101")), opened.video, 1,
                opened.track, 1, opened.rendition!!.copy(quality = 1080), opened.tiers)
            assertFalse(feed.lowerSoftwareQuality(clip))
        } finally { feed.close() }
    }

    @Test fun changingAutomaticQualityInvalidatesQueuedLowerQualityAndPersistsChoice() = fixture { activity, fake, library, opened ->
        invoke(activity, "queuePreparedEpisode", "102:${library.maxQuality}")
        assertEquals(2, fake.items.size)
        val recent = get(activity, "recentPlaybackSources") as PlaybackSourceCache
        recent.remember("102", opened.rendition!!, library.maxQuality)
        invoke(activity, "setAutoQualityDowngrade", false, false)
        assertFalse(library.autoQualityDowngrade)
        assertEquals(1, fake.items.size)
        assertNull(get(activity, "queuedNext"))
        assertNull(recent.find("102", library.maxQuality))
        library.flush()
        val writes = get(library, "writes") as LatestWriteQueue<*, *>
        (get(writes, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        assertFalse(activity.getSharedPreferences("native-library-v1", 0).getBoolean("autoQualityDowngrade", true))
    }

    @Test fun disabledAutomaticQualityShowsManualActionsAfterDecoderFailureWithoutReloading() = fixture { activity, _, library, opened ->
        library.maxQuality = 1080
        library.autoQualityDowngrade = false
        val requests = blockDuplicateMetadataRequests(activity)
        val high = opened.rendition!!.copy(quality = 1080)
        set(activity, "attemptedQuality", 1080)
        set(activity, "currentRendition", high)
        set(activity, "currentTiers", listOf(high, opened.rendition))
        invoke(activity, "playerFailure", PlaybackException("fixture", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(get(activity, "playError") as Boolean)
        assertEquals(0, get(activity, "autoDowngrades"))
        assertEquals(1080, library.maxQuality)
        assertEquals(0, requests.get())
        fun labels(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue(labels(get(activity, "controls") as View).contains("切换清晰度"))
        assertTrue(labels(get(activity, "controls") as View).contains("切换解码方式"))
    }

}
