// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hongguotv.core.ContentRepository

/** Real stream/decoder lifecycle check. Emulator results do not certify vendor drivers. */
internal object DecoderStabilityAcceptance {
    fun run(test: Instrumentation, lowMemoryProfile: Boolean = false, hevcRecovery: Boolean = false, softwareOverload: Boolean = false) {
        val result = Bundle()
        val constrained = lowMemoryProfile || hevcRecovery
        val issue150 = constrained || softwareOverload
        var activity: MainActivity? = null
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        try {
            check(test.targetContext.packageName.endsWith(".debug"))
            val current = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = current
            val library = field("library").get(current) as Library
            onMain {
                check(!(field("cloudAccount").get(current) as AccountController).signedIn)
                library.maxQuality = if (softwareOverload) 720 else 1080
                library.playbackSpeed = if (softwareOverload) 2f else 1f
                library.videoDecoderMode = VideoDecoderMode.AUTOMATIC
                library.autoNext = false
                if (constrained) {
                    // Exercise the policy on the emulator; this does not emulate the affected SoC.
                    val ramMiB = if (hevcRecovery) 2048L else 984L
                    field("playbackSafety\$delegate").set(current, lazyOf(PlaybackSafetyPolicy(true, false, ramMiB * 1024 * 1024)))
                }
            }
            val repo = field("repository").get(current) as ContentRepository
            val detail = if (issue150) {
                val series = repo.searchAll("穿越八零，我把娘俩宠成宝").items.first { it.title == "穿越八零，我把娘俩宠成宝" }
                repo.detail(series.id)
            } else repo.detail("7709114780467858456")
            check(detail.episodes.size >= if (issue150) 316 else 6)
            val play = MainActivity::class.java.declaredMethods.single { it.name == "playEpisode" }.apply { isAccessible = true }
            val exit = MainActivity::class.java.getDeclaredMethod("returnToDetail", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            fun player() = field("player").get(current) as? ExoPlayer
            fun awaitFrame(index: Int, minimum: Long = 3000, autoplay: Boolean = true, maximumSize: Int = Int.MAX_VALUE) {
                val deadline = SystemClock.elapsedRealtime() + 50_000
                var ready = false
                var error = false
                var info = ""
                while (SystemClock.elapsedRealtime() < deadline) {
                    onMain {
                        val p = player()
                        p?.volume = 0f
                        error = field("playError").getBoolean(current)
                        ready = p?.playbackState == Player.STATE_READY && p.playWhenReady == autoplay &&
                            (!autoplay || p.isPlaying) && p.currentMediaItem?.mediaId == detail.episodes[index] &&
                            p.currentPosition >= minimum && !field("waitingForFirstFrame").getBoolean(current) &&
                            p.videoSize.width > 0 && p.videoSize.height > 0 &&
                            minOf(p.videoSize.width, p.videoSize.height) <= maximumSize
                        val decoder = (field("playerDiagnostics").get(current) as? PlaybackDiagnostics)?.videoDecoder
                        info = "size=${p?.videoSize?.width}x${p?.videoSize?.height}; decoder=${decoder?.name}; initCount=${p?.videoDecoderCounters?.decoderInitCount}"
                        if (ready && constrained) {
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
            if (hevcRecovery) report("forced-policy=32-bit/2048MiB; setting=1080P; HEVC-software-cap=720P; emulator-driver=true")
            repeat(if (issue150) 1 else 3) { cycle ->
                var retainedPlayer: ExoPlayer? = null
                var retainedSurface: android.view.View? = null
                for (offset in 0..1) {
                    val index = (if (issue150) 314 else 0) + cycle * 2 + offset
                    onMain {
                        field("detail").set(current, detail)
                        play.invoke(current, index, 0L, true, false, false, false, false)
                    }
                    awaitFrame(index)
                    onMain {
                        val view = field("playerView").get(current) as PlayerView
                        if (offset == 0) { retainedPlayer = player(); retainedSurface = view.videoSurfaceView }
                        else { check(player() === retainedPlayer); check(view.videoSurfaceView === retainedSurface) }
                        player()!!.seekTo(12_000)
                    }
                    awaitFrame(index, 14_000)
                }
                if (hevcRecovery) {
                    val request = MainActivity::class.java.getDeclaredMethod("requestPlayback", Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                    val failure = MainActivity::class.java.getDeclaredMethod("playerFailure", Throwable::class.java)
                        .apply { isAccessible = true }
                    var position = 0L
                    var oldPlayer: ExoPlayer? = null
                    onMain {
                        request.invoke(current, false)
                        oldPlayer = player()
                        position = oldPlayer!!.currentPosition
                        val diagnostics = field("playerDiagnostics").get(current) as PlaybackDiagnostics
                        // Inject only the reported hardware failure. Actual decoding remains the
                        // emulator's software implementation; this cannot certify a vendor driver.
                        diagnostics.videoDecoder = ActiveVideoDecoder("injected.hardware.hevc", true, false)
                        val error = ExoPlaybackException.createForRenderer(IllegalStateException("injected hardware failure"),
                            "video", 0, oldPlayer!!.videoFormat, C.FORMAT_HANDLED, null, false,
                            PlaybackException.ERROR_CODE_DECODING_FAILED)
                        failure.invoke(current, error)
                    }
                    awaitFrame(315, position, autoplay = false)
                    onMain {
                        check(player() !== oldPlayer)
                        check(!player()!!.playWhenReady)
                        val recovery = field("decoderRecovery").get(current) as PlaybackDecoderRecovery
                        check(recovery.mode(detail.episodes[315], library.videoDecoderMode) == VideoDecoderMode.SOFTWARE_ONLY)
                        check(recovery.mode(detail.episodes[314], library.videoDecoderMode) == VideoDecoderMode.AUTOMATIC)
                        request.invoke(current, true)
                    }
                    awaitFrame(315, position + 1000)
                    report("injected-hardware-failure=true; software-retry=true; position-and-pause-retained=true; other-episode-mode=AUTOMATIC")
                }
                if (softwareOverload) {
                    var position = 0L
                    onMain {
                        check(android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) { "This acceptance covers the previously excluded 64-bit device" }
                        position = player()!!.currentPosition
                        check((field("playerDiagnostics").get(current) as PlaybackDiagnostics).videoDecoder?.softwareOnly == true)
                        player()!!.analyticsCollector.onDroppedFrames(50, 2000)
                    }
                    Thread.sleep(100)
                    onMain { player()!!.analyticsCollector.onDroppedFrames(50, 2000) }
                    awaitFrame(315, position, maximumSize = 360)
                    onMain {
                        val p = player()!!
                        check(p.playbackParameters.speed == 2f && library.playbackSpeed == 2f)
                        check(library.maxQuality == 720)
                        check((field("qualityRecovery").get(current) as PlaybackQualityRecovery).maximum(detail.episodes[314], 720) == 720)
                        check(p.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
                        check(p.audioFormat?.sampleRate == 44100)
                        val diagnostics = field("playerDiagnostics").get(current) as PlaybackDiagnostics
                        check(diagnostics.lastAudioDecoder != null && diagnostics.audioTrackConfig != null)
                        report(diagnostics.audioInfo(p.audioFormat, true, p.volume))
                    }
                    report("64-bit=true; injected-dropped-frame-batches=2; reduced-coded-size=360x642; speed=2x; position-retained=true; preferences-preserved=true; audio-selected-and-output-created=true")
                }
                var elapsed = 0L
                onMain {
                    val started = SystemClock.elapsedRealtime()
                    exit.invoke(current, true)
                    elapsed = SystemClock.elapsedRealtime() - started
                    check(player() == null)
                    check(field("playerView").get(current) == null)
                }
                check(elapsed < 2500) { "Exit blocked main thread for $elapsed ms" }
                report("cycle=${cycle + 1}; player-and-surface-reused=true; released=true; exitMs=$elapsed")
            }
            onMain { current.finish() }
            result.putString("stream", if (softwareOverload)
                "PASS: 64-bit HEVC software overload recovery below 720P, actual smaller rendition, 2x speed and position retained, AAC output initialized\n"
                else if (constrained)
                "PASS: issue 150 episodes 315/316 under forced 32-bit policy; coded size within 720P; seek, reuse and exit; hardware-fault-injection=$hevcRecovery\n"
                else "PASS: six real HEVC episodes, seek, player/Surface reuse, three exits\n")
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            activity?.let { test.runOnMainSync { it.finish() } }
            result.putString("stream", "FAIL decoder lifecycle: ${error.javaClass.simpleName}: ${error.message}\n")
            test.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
