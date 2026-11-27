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

/** Real directory -> production player: verify the preview boundary and distant episode indexes. */
internal object Issue144DirectoryAcceptance {
    fun run(test: Instrumentation) {
        var activity: MainActivity? = null
        val result = Bundle()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun report(text: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
        try {
            check(test.targetContext.packageName.endsWith(".debug"))
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
            fun awaitFrame(detail: Detail, index: Int, minimum: Long = 3000) {
                val deadline = SystemClock.elapsedRealtime() + 50_000
                var ready = false
                var failed = false
                var dimensions = ""
                while (SystemClock.elapsedRealtime() < deadline) {
                    test.runOnMainSync {
                        val p = player(); p?.volume = 0f
                        failed = field("playError").getBoolean(current)
                        ready = p?.isPlaying == true && p.currentPosition >= minimum &&
                            p.currentMediaItem?.mediaId == detail.episodes[index] &&
                            !field("waitingForFirstFrame").getBoolean(current) &&
                            p.videoSize.width > 0 && p.videoSize.height > 0
                        dimensions = "${p?.videoSize?.width}x${p?.videoSize?.height}"
                    }
                    if (ready || failed) break
                    Thread.sleep(100)
                }
                check(ready && !failed) { "No decoded progress: episode=${index + 1}; error=$failed" }
                report("series=${detail.series.title}; episode=${index + 1}; frame=true; position>=$minimum; size=$dimensions")
            }
            fun start(detail: Detail, index: Int) {
                test.runOnMainSync {
                    field("detail").set(current, detail)
                    play.invoke(current, index, 0L, true, false, false, false, false)
                }
                awaitFrame(detail, index)
            }
            for ((id, minimumCount) in listOf("7709114780467858456" to 210, "7710503618658454553" to 78)) {
                val detail = repo.detail(id)
                check(detail.episodes.size >= minimumCount)
                report("directory series=${detail.series.title}; episodes=${detail.episodes.size}")
                start(detail, 2)
                test.runOnMainSync {
                    (field("library").get(current) as Library).autoNext = true
                    check(player()!!.duration > 3000)
                    player()!!.seekTo(player()!!.duration - 1000)
                }
                awaitFrame(detail, 3)
                report("automatic-next=3->4")
                start(detail, detail.episodes.size / 2)
                start(detail, detail.episodes.lastIndex)
                test.runOnMainSync { player()!!.seekTo(15_000) }
                awaitFrame(detail, detail.episodes.lastIndex, 17_000)
                var pausedAt = 0L
                test.runOnMainSync { player()!!.pause(); pausedAt = player()!!.currentPosition }
                Thread.sleep(500)
                test.runOnMainSync {
                    check(!player()!!.isPlaying && kotlin.math.abs(player()!!.currentPosition - pausedAt) < 300)
                    player()!!.play()
                }
                awaitFrame(detail, detail.episodes.lastIndex, pausedAt + 1000)
                report("seek=true; pause-resume=true")
            }
            result.putString("stream", "PASS complete directories, automatic episode 3->4, middle/final episodes, seek and pause/resume\n")
            test.runOnMainSync { current.finish() }
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            activity?.let { test.runOnMainSync { it.finish() } }
            result.putString("stream", "FAIL directory playback: ${error.javaClass.simpleName}: ${error.message}\n")
            test.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
