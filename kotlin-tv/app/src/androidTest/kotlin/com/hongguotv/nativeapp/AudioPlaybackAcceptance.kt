// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hongguotv.core.Detail
import com.hongguotv.core.PreparedSlot
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import com.hongguotv.core.Series
import com.hongguotv.core.StreamInfo
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Controlled local-source reproduction through the production full/feed players. */
internal object AudioPlaybackAcceptance {
    fun run(test: Instrumentation, args: Bundle) {
        var activity: MainActivity? = null
        var feed: Media3TrialPlayback? = null
        var remote: RemoteVideo? = null
        val samples = JSONArray()
        val frames = mutableListOf<JSONArray>()
        val result = Bundle()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun report(message: String) = test.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        try {
            check(test.targetContext.packageName.endsWith(".debug"))
            val url = checkNotNull(args.getString("audioProbeUrl"))
            check(url.startsWith("http://10.0.2.2:") || url.startsWith("http://127.0.0.1:"))
            val speed = args.getString("probeSpeed")?.toFloat() ?: 1f
            val seconds = args.getString("probeSeconds")?.toInt() ?: 45
            val stallMs = args.getString("probeStallMs")?.toLong() ?: 0L
            val isFeed = args.getString("probeFeed") == "true"
            val current = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = current
            val data = Detail(Series("audio-probe", "音画同步受控测试"), listOf("audio-probe-episode"))
            val library = field("library").get(current) as Library
            onMain { library.homeAutoplay = false; library.autoNext = false; library.maxQuality = 1080; library.playbackSpeed = speed }
            val key = if (args.getString("probeCrypto") == "true") ByteArray(16) { it.toByte() } else null
            val video = RemoteVideo(OkHttpClient(), StreamInfo(url, key, "1080P"))
            remote = video
            video.prepare()
            val track = video.trackInfo()
            val rendition = Rendition(1080, "h265", track?.width, track?.height, url, null)
            var player: ExoPlayer? = null
            var diagnostics: PlaybackDiagnostics? = null
            onMain {
                if (isFeed) {
                    MainActivity::class.java.getDeclaredMethod("invalidatePage").apply { isAccessible = true }.invoke(current)
                    MainActivity::class.java.getDeclaredMethod("releaseCatalogViews").apply { isAccessible = true }.invoke(current)
                    field("screen").set(current, "audio-probe")
                    val playback = Media3TrialPlayback(current, speed)
                    feed = playback
                    val root = field("root").get(current) as ViewGroup
                    root.removeAllViews(); root.addView(playback.view)
                    playback.load(TrialClip(data, video, 1, track, 1, rendition, listOf(rendition)), 0, true)
                    player = Media3TrialPlayback::class.java.getDeclaredField("player").apply { isAccessible = true }.get(playback) as ExoPlayer
                    diagnostics = Media3TrialPlayback::class.java.getDeclaredField("diagnostics").apply { isAccessible = true }.get(playback) as PlaybackDiagnostics
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val slot = field("preparedNext").get(current) as PreparedSlot<OpenedStream>
                    val key = "${data.episodes.first()}:1080"
                    check(slot.complete(slot.begin(key), key, OpenedStream(video, rendition, listOf(rendition), track)))
                    field("detail").set(current, data)
                    MainActivity::class.java.declaredMethods.single { it.name == "playEpisode" }.apply { isAccessible = true }
                        .invoke(current, 0, 0L, true, false, false, false, false)
                }
            }
            val readyDeadline = SystemClock.elapsedRealtime() + 30_000
            var ready = false
            var listenerInstalled = false
            while (!ready && SystemClock.elapsedRealtime() < readyDeadline) {
                onMain {
                    if (!isFeed) {
                        player = field("player").get(current) as? ExoPlayer
                        diagnostics = field("playerDiagnostics").get(current) as? PlaybackDiagnostics
                    }
                    player?.let { p ->
                        // Keep emulator speaker output quiet; the captured signal remains measurable.
                        p.volume = .15f
                        if (!listenerInstalled) {
                            p.setVideoFrameMetadataListener { pts, release, _, _ ->
                                synchronized(frames) { frames.add(JSONArray(listOf(SystemClock.elapsedRealtime(), pts, release))) }
                            }
                            listenerInstalled = true
                        }
                        check(p.playerError == null) { p.playerError.toString() }
                        ready = p.isPlaying && p.playbackState == Player.STATE_READY && p.currentPosition >= 1000
                    }
                }
                Thread.sleep(100)
            }
            check(ready) { "Playback did not start" }
            val p = checkNotNull(player)
            val started = SystemClock.elapsedRealtime()
            report("probe-ready sdk=${android.os.Build.VERSION.SDK_INT} feed=$isFeed speed=$speed stallMs=$stallMs")
            var stall = 0
            var ended = false
            while (!ended && SystemClock.elapsedRealtime() - started < seconds * 1000L) {
                onMain {
                    check(p.playerError == null) { p.playerError.toString() }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (stallMs > 0 && stall < 3 && elapsed >= 10_000 + stall * 11_000) {
                        stall++
                        Handler(p.playbackLooper).post { Thread.sleep(stallMs) }
                        report("playback-thread-stall elapsedMs=$elapsed durationMs=$stallMs")
                    }
                    samples.put(JSONArray(listOf(SystemClock.elapsedRealtime(), p.currentPosition,
                        p.playbackParameters.speed, p.playbackState, p.isPlaying, p.totalBufferedDuration,
                        p.videoDecoderCounters?.droppedBufferCount, p.audioDecoderCounters?.renderedOutputBufferCount)))
                    ended = p.playbackState == Player.STATE_ENDED
                }
                Thread.sleep(200)
            }
            onMain {
                report(checkNotNull(diagnostics).audioInfo(p.audioFormat,
                    p.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO), p.volume))
                report("probe-finished positionMs=${p.currentPosition} ended=$ended video=${p.videoFormat?.codecs} audio=${p.audioFormat?.codecs}")
            }
            val output = JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT).put("feed", isFeed)
                .put("speed", speed).put("stallMs", stallMs).put("samples", samples)
                .put("frames", synchronized(frames) { JSONArray(frames) })
            java.io.File(test.targetContext.filesDir, "audio-probe.json").writeText(output.toString())
            result.putString("stream", "PASS: playback probe completed; recorded A/V measurement still required\n")
            test.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(error)}\n")
            test.finish(Activity.RESULT_CANCELED, result)
        } finally {
            onMain { feed?.release(); activity?.finish() }
            remote?.close()
        }
    }
}
