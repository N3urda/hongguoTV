// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail

/** Explicit live test of the production TV player, with the official App absent. */
internal object Issue102PlaybackAcceptance {
    fun run(test: Instrumentation) {
        var activity: MainActivity? = null
        val result = Bundle()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun report(text: String) {
            test.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
        }
        try {
            check(test.targetContext.packageName.endsWith(".debug"))
            check("native-signing-sdk.json" !in test.targetContext.assets.list("").orEmpty())
            java.util.zip.ZipFile(test.targetContext.applicationInfo.sourceDir).use { apk ->
                check(apk.entries().asSequence().none { "metasec" in it.name || "native-signing-sdk" in it.name })
            }
            check(runCatching { test.targetContext.packageManager.getPackageInfo("com.phoenix.read", 0) }.isFailure) {
                "Use an isolated TV emulator without the official App"
            }
            val current = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = current
            test.runOnMainSync { check(!(field("cloudAccount").get(current) as AccountController).signedIn) }
            val repo = field("repository").get(current) as ContentRepository
            val play = MainActivity::class.java.getDeclaredMethod("playEpisode", Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            fun player() = field("player").get(current) as? ExoPlayer
            fun awaitFrame(id: String, minimum: Long = 5000) {
                val deadline = SystemClock.elapsedRealtime() + 45_000
                var ready = false
                var failed = false
                var dimensions = ""
                while (SystemClock.elapsedRealtime() < deadline) {
                    test.runOnMainSync {
                        val p = player(); p?.volume = 0f
                        failed = field("playError").getBoolean(current)
                        ready = p?.isPlaying == true && p.currentPosition >= minimum &&
                            p.currentMediaItem?.mediaId == id &&
                            !field("waitingForFirstFrame").getBoolean(current) &&
                            p.videoSize.width > 0 && p.videoSize.height > 0
                        dimensions = "${p?.videoSize?.width}x${p?.videoSize?.height}"
                    }
                    if (ready || failed) break
                    Thread.sleep(100)
                }
                check(ready && !failed) { "No decoded progress for $id; playerError=$failed" }
                report("episode=$id frame=true position>=$minimum size=$dimensions")
            }
            fun start(detail: Detail, index: Int) {
                check(index in detail.episodes.indices)
                test.runOnMainSync {
                    field("detail").set(current, detail)
                    play.invoke(current, index, 0L, true, false, false, false, false)
                }
                awaitFrame(detail.episodes[index])
            }
            val samples = listOf(
                "7704857878514895896" to "7704857946630409240",
                "7709114780467858456" to "7709114791150767128",
                "7709197479090064446" to "7709197486451084312",
            )
            for ((series, episode) in samples) {
                val detail = repo.detail(series)
                val index = detail.episodes.indexOf(episode)
                start(detail, index)
                if (episode == samples.first().second) {
                    test.runOnMainSync { player()!!.seekTo(20_000) }
                    awaitFrame(episode, 22_000)
                    report("seek=true")
                    start(detail, index + 1)
                    report("episode-switch=true")
                }
            }
            var pausedAt = 0L
            test.runOnMainSync { player()!!.pause(); pausedAt = player()!!.currentPosition }
            Thread.sleep(700)
            test.runOnMainSync {
                check(!player()!!.isPlaying && kotlin.math.abs(player()!!.currentPosition - pausedAt) < 300)
                player()!!.play()
            }
            awaitFrame(samples.last().second, pausedAt + 1500)
            report("pause-resume=true")
            result.putString("stream", "PASS pure Kotlin TV playback: 3 issue samples, next episode, seek, pause/resume; no native signing SDK\n")
            test.runOnMainSync { current.finish() }
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            activity?.let { test.runOnMainSync { it.finish() } }
            result.putString("stream", "FAIL pure Kotlin TV playback: ${error.javaClass.simpleName}: ${error.message}\n")
            test.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
