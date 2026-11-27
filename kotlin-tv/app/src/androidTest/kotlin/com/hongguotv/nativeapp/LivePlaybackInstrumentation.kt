// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail

/** Explicit real-network acceptance: uses the Activity's production player, never a sample URL. */
class LivePlaybackInstrumentation : Instrumentation() {
    private var issue102Samples = false
    private var issue144Samples = false
    override fun onCreate(arguments: Bundle?) {
        issue102Samples = arguments?.getString("issue102Samples") == "true"
        issue144Samples = arguments?.getString("issue144Samples") == "true"
        super.onCreate(arguments); start()
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    override fun onStart() {
        if (issue144Samples) { Issue144DirectoryAcceptance.run(this); return }
        if (issue102Samples) { Issue102PlaybackAcceptance.run(this); return }
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
            var comments: android.app.Dialog? = null
            var commentsAnchor: TextView? = null
            var beforeComments = 0L
            runOnMainSync {
                current.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
                current.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
                commentsAnchor = (field("controls").get(current) as View).findViewWithTag("comments")
                check(commentsAnchor!!.requestFocus())
                beforeComments = player()!!.currentPosition
                commentsAnchor!!.performClick()
                val social = (field("social\$delegate").get(current) as Lazy<*>).value!!
                comments = social.javaClass.getDeclaredField("dialog").apply { isAccessible = true }
                    .get(social) as android.app.Dialog
                check(comments!!.isShowing && player()!!.isPlaying)
            }
            fun labels(view: View): List<String> = when (view) {
                is TextView -> listOf(view.text.toString())
                is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                else -> emptyList()
            }
            var commentsLoaded = false
            val commentsDeadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < commentsDeadline && !commentsLoaded) {
                Thread.sleep(100)
                runOnMainSync {
                    commentsLoaded = labels(comments!!.window!!.decorView).any { it.startsWith("已加载 ") }
                }
            }
            check(commentsLoaded) { "Episode comments did not load" }
            Thread.sleep(3000)
            runOnMainSync {
                check(comments!!.isShowing && player()!!.isPlaying)
                check(player()!!.currentPosition >= beforeComments + 2500)
                comments!!.dismiss()
            }
            waitForIdleSync()
            runOnMainSync { check(commentsAnchor!!.hasFocus() && player()!!.isPlaying) }
            sendStatus(0, Bundle().apply { putString("stream", "PASS: episode comments loaded during advancing playback; closing restored menu focus\n") })
            val library = field("library").get(current) as Library
            val initialProgressSetting = library.alwaysShowProgress
            try {
                for (alwaysShow in listOf(true, false)) {
                    var target = 0L
                    runOnMainSync {
                        library.alwaysShowProgress = alwaysShow
                        val p = player()!!
                        p.pause()
                        p.seekTo(0)
                        MainActivity::class.java.getDeclaredMethod("hidePlaybackOverlay")
                            .apply { isAccessible = true }.invoke(current)
                        val hud = field("hud").get(current) as View
                        val bar = field("persistentBar").get(current) as View
                        val hint = field("seekHint").get(current) as TextView
                        check(bar.isShown == alwaysShow)
                        val now = SystemClock.uptimeMillis()
                        for ((key, repeat) in listOf(KeyEvent.KEYCODE_DPAD_RIGHT to 0,
                            KeyEvent.KEYCODE_DPAD_RIGHT to 5, KeyEvent.KEYCODE_DPAD_LEFT to 0)) {
                            current.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, repeat))
                            target = (target + (if (key == KeyEvent.KEYCODE_DPAD_LEFT) -10000L
                                else if (repeat == 5) 30000L else 10000L)).coerceIn(0, p.duration - 500)
                            val seconds = target / 1000
                            check(hint.isShown && hint.text == "%02d:%02d".format(seconds / 60, seconds % 60))
                            check(!hud.isShown && bar.isShown == alwaysShow)
                        }
                    }
                    Thread.sleep(1500)
                    runOnMainSync {
                        check(!(field("seekHint").get(current) as View).isShown)
                        check(!(field("hud").get(current) as View).isShown)
                        check(kotlin.math.abs(player()!!.currentPosition - target) < 1000)
                    }
                }
                sendStatus(0, Bundle().apply { putString("stream", "PASS: seek target, repeat keys, hint dismissal and both progress settings\n") })
            } finally {
                runOnMainSync { library.alwaysShowProgress = initialProgressSetting; player()?.play() }
            }
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
