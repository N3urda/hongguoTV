// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hongguotv.core.ContentRepository

/** Real stream/decoder lifecycle check. Emulator results do not certify vendor drivers. */
internal object DecoderStabilityAcceptance {
    fun run(test: Instrumentation, lowMemoryProfile: Boolean = false) {
        val result = Bundle()
        var activity: MainActivity? = null
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        try {
            check(test.targetContext.packageName.endsWith(".debug"))
            val current = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = current
            val library = field("library").get(current) as Library
            test.runOnMainSync {
                check(!(field("cloudAccount").get(current) as AccountController).signedIn)
                library.maxQuality = 1080
                library.videoDecoderMode = VideoDecoderMode.AUTOMATIC
                library.autoNext = false
                if (lowMemoryProfile) {
                    // Exercise the policy on the emulator; this does not emulate the affected SoC.
                    field("playbackSafety\$delegate").set(current, lazyOf(PlaybackSafetyPolicy(true, false, 984L * 1024 * 1024)))
                }
            }
            val repo = field("repository").get(current) as ContentRepository
            val detail = if (lowMemoryProfile) {
                val series = repo.searchAll("穿越八零，我把娘俩宠成宝").items.first { it.title == "穿越八零，我把娘俩宠成宝" }
                repo.detail(series.id)
            } else repo.detail("7709114780467858456")
            check(detail.episodes.size >= if (lowMemoryProfile) 316 else 6)
            val play = MainActivity::class.java.declaredMethods.single { it.name == "playEpisode" }.apply { isAccessible = true }
            val exit = MainActivity::class.java.getDeclaredMethod("returnToDetail", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            fun player() = field("player").get(current) as? ExoPlayer
            fun awaitFrame(index: Int, minimum: Long = 3000) {
                val deadline = SystemClock.elapsedRealtime() + 50_000
                var ready = false
                var error = false
                var info = ""
                while (SystemClock.elapsedRealtime() < deadline) {
                    test.runOnMainSync {
                        val p = player()
                        p?.volume = 0f
                        error = field("playError").getBoolean(current)
                        ready = p?.isPlaying == true && p.currentMediaItem?.mediaId == detail.episodes[index] &&
                            p.currentPosition >= minimum && !field("waitingForFirstFrame").getBoolean(current) &&
                            p.videoSize.width > 0 && p.videoSize.height > 0
                        val decoder = (field("playerDiagnostics").get(current) as? PlaybackDiagnostics)?.videoDecoder
                        info = "size=${p?.videoSize?.width}x${p?.videoSize?.height}; decoder=${decoder?.name}; initCount=${p?.videoDecoderCounters?.decoderInitCount}"
                        if (ready && lowMemoryProfile) {
                            check(minOf(p!!.videoSize.width, p.videoSize.height) <= 720)
                            check(maxOf(p.videoSize.width, p.videoSize.height) <= 1280)
                            check((field("currentRendition").get(current) as com.hongguotv.core.Rendition).quality <= 720)
                        }
                    }
                    if (ready || error) break
                    Thread.sleep(100)
                }
                check(ready && !error) { "No rendered progress at episode ${index + 1}" }
                report("episode=${index + 1}; frame=true; minimumMs=$minimum; $info")
            }
            if (lowMemoryProfile) report("forced-policy=32-bit/984MiB; setting=1080P; maximum=720P; emulator-driver=true")
            repeat(if (lowMemoryProfile) 1 else 3) { cycle ->
                var retainedPlayer: ExoPlayer? = null
                var retainedSurface: android.view.View? = null
                for (offset in 0..1) {
                    val index = (if (lowMemoryProfile) 314 else 0) + cycle * 2 + offset
                    test.runOnMainSync {
                        field("detail").set(current, detail)
                        play.invoke(current, index, 0L, true, false, false, false, false)
                    }
                    awaitFrame(index)
                    test.runOnMainSync {
                        val view = field("playerView").get(current) as PlayerView
                        if (offset == 0) { retainedPlayer = player(); retainedSurface = view.videoSurfaceView }
                        else { check(player() === retainedPlayer); check(view.videoSurfaceView === retainedSurface) }
                        player()!!.seekTo(12_000)
                    }
                    awaitFrame(index, 14_000)
                }
                var elapsed = 0L
                test.runOnMainSync {
                    val started = SystemClock.elapsedRealtime()
                    exit.invoke(current, true)
                    elapsed = SystemClock.elapsedRealtime() - started
                    check(player() == null)
                    check(field("playerView").get(current) == null)
                }
                check(elapsed < 2500) { "Exit blocked main thread for $elapsed ms" }
                report("cycle=${cycle + 1}; player-and-surface-reused=true; released=true; exitMs=$elapsed")
            }
            test.runOnMainSync { current.finish() }
            result.putString("stream", if (lowMemoryProfile)
                "PASS: issue 150 episodes 315/316 under forced low-memory policy; coded size within 720P; seek, reuse and exit\n"
                else "PASS: six real HEVC episodes, seek, player/Surface reuse, three exits\n")
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            activity?.let { test.runOnMainSync { it.finish() } }
            result.putString("stream", "FAIL decoder lifecycle: ${error.javaClass.simpleName}: ${error.message}\n")
            test.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
