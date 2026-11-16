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

/** Explicit real-network acceptance: uses the Activity's production player, never a sample URL. */
class LivePlaybackInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    override fun onStart() {
        val result = Bundle()
        var activity: MainActivity? = null
        try {
            check(targetContext.packageName.endsWith(".debug")) { "Use isolated debug app" }
            val current = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = current
            runOnMainSync { check(!(field("cloudAccount").get(current) as AccountController).signedIn) }
            val repo = field("repository").get(current) as ContentRepository
            val play = MainActivity::class.java.getDeclaredMethod("playEpisode", Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            fun player() = field("player").get(current) as? ExoPlayer
            fun awaitFrame(detail: Detail, index: Int, minimum: Long = 5000) {
                val deadline = SystemClock.elapsedRealtime() + 50_000
                var ready = false
                var failed = false
                var dimensions = ""
                while (SystemClock.elapsedRealtime() < deadline) {
                    runOnMainSync {
                        val p = player()
                        p?.volume = 0f
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
                check(ready && !failed) { "No decoded progress: episode=${index+1}; error=$failed" }
                val evidence = "series=${detail.series.title}; episode=${index+1}; frame=true; position>=${minimum}; size=$dimensions"
                result.putString("last", evidence)
                sendStatus(0, Bundle().apply { putString("stream", evidence + "\n") })
            }
            fun start(detail: Detail, index: Int) {
                runOnMainSync {
                    field("detail").set(current, detail)
                    play.invoke(current, index, 0L, true, false, false, false, false)
                }
                awaitFrame(detail, index)
            }
            val home = repo.home()
            check(home.items.size >= 2)
            val first = repo.detail(home.items[0].id)
            start(first, 0)
            runOnMainSync { player()!!.seekTo(15_000) }
            awaitFrame(first, 0, 18_000)
            runOnMainSync { player()!!.pause() }
            Thread.sleep(400)
            runOnMainSync { check(!player()!!.isPlaying); player()!!.play() }
            awaitFrame(first, 0, 19_000)
            runOnMainSync {
                (field("library").get(current) as Library).autoNext = true
                check(player()!!.duration > 3000)
                player()!!.seekTo(player()!!.duration - 1000)
            }
            awaitFrame(first, 1)
            val second = repo.detail(home.items[1].id)
            start(second, 0)
            start(second, 1)
            val later = repo.detail("7663491547907755033")
            for (index in listOf(53, 54, 69)) start(later, index)
            result.putString("stream", "PASS: seven real episodes, rendered frames, seek, pause/resume and automatic next episode\n")
            runOnMainSync { current.finish() }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            activity?.let { runOnMainSync { it.finish() } }
            result.putString("failure", error.javaClass.simpleName + ":" + error.message)
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
