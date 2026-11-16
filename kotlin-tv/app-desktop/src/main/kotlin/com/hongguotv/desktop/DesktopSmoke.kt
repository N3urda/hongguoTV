// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.*
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*

/** Opt-in live acceptance. Never runs from unit tests or CI, never prints media URLs or keys. */
object DesktopSmoke {
    fun run() {
        val transport = DesktopNetwork.client().newBuilder()
            .eventListener(object : okhttp3.EventListener() {
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { println("Transport failure: ${call.request().url.host} ${ioe.javaClass.simpleName}") }
                override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { println("Response: ${call.request().url.host} HTTP ${response.code}") }
                override fun connectStart(call: okhttp3.Call, address: java.net.InetSocketAddress, proxy: java.net.Proxy) { println("Connection via ${proxy.type()}") }
            }).build()
        val repository = ContentRepository(transport)
        val catalog = repository.discover(DramaCategory.REAL)
        println("Live catalog: ${catalog.items.size} titles")
        val cover = kotlinx.coroutines.runBlocking { DesktopArtwork.load(catalog.items.first().cover) }
        println("Live cover decoded: ${cover != null}")
        var completed = false
        for (series in catalog.items.take(2)) {
            var stage = "detail"
            try {
                val detail = repository.detail(series.id)
                check(detail.episodes.isNotEmpty())
                println("Resolving a real episode (${detail.episodes.size} episodes in series)")
                stage = "resolve-stream"
                repository.preparePlaybackSession()
                val rendition = repository.streamLadderWithCompatibility(detail.episodes.first()).select(720) ?: error("No stream")
                stage = "prepare-media"
                val remote = RemoteVideo(repository.http, rendition.toStreamInfo())
                val bridge = try { PlaybackBridge(remote.prepare()) } catch(e: Exception) { remote.close(); throw e }
                stage = "player"
                bridge.use {
                    MpvPlayer(bridge.url, "红果 · 真实播放验收").use { player ->
                        verify(player, "desktop-live-smoke.json")
                    }
                }
                completed = true; break
            } catch(e: Exception) { println("Candidate failed at $stage: ${e.javaClass.simpleName}; trying another title") }
        }
        check(completed) { "No live candidate passed; see local network/upstream availability" }
    }

    fun runLocal(file: Path) {
        val bytes = Files.readAllBytes(file)
        PlaybackBridge(bytes.size.toLong(), { start, count -> bytes.copyOfRange(start.toInt(), start.toInt() + count) }, {}).use { bridge ->
            MpvPlayer(bridge.url, "红果 · 播放器测试").use { verify(it, "desktop-local-smoke.json") }
        }
    }

    private fun verify(player: MpvPlayer, reportName: String) {
        fun number(name: String) = (player.command("get_property", name) as? Number)?.toDouble() ?: 0.0
        val deadline = System.nanoTime() + 60_000_000_000L
        while (number("time-pos") < 2.0 && System.nanoTime() < deadline) Thread.sleep(300)
        val first = number("time-pos")
        check(first >= 2) { "Playback did not advance" }
        val video = player.command("get_property", "video-params") as? JSONObject
        check((video?.optInt("w") ?: 0) > 0) { "No decoded video" }
        val audio = player.command("get_property", "audio-params") as? JSONObject
        println("Decoded video ${video!!.optInt("w")}x${video.optInt("h")}; audio=${audio != null}; time advanced")
        player.command("set_property", "pause", true)
        Thread.sleep(350)
        val pausedAt = number("time-pos")
        Thread.sleep(900)
        check(kotlin.math.abs(number("time-pos") - pausedAt) < .2) { "Pause failed" }
        player.command("seek", 15, "absolute+exact")
        Thread.sleep(1000)
        player.command("set_property", "speed", 1.25)
        player.command("set_property", "pause", false)
        val seekDeadline = System.nanoTime() + 15_000_000_000L
        while (number("time-pos") < 15 && System.nanoTime() < seekDeadline) Thread.sleep(250)
        check(number("time-pos") >= 15) { "Seek failed" }
        check(number("speed") == 1.25)
        player.command("set_property", "keepaspect", false)
        check(player.command("get_property", "keepaspect") == false)
        player.command("set_property", "keepaspect", true)
        player.command("set_property", "panscan", 1.0)
        check(number("panscan") == 1.0)
        player.command("set_property", "video-rotate", 90)
        check(number("video-rotate") == 90.0)
        player.command("set_property", "video-rotate", 0)
        player.command("set_property", "panscan", 0.0)
        player.command("show-text", DesktopDanmaku.safeText("桌面弹幕显示验证"), 1000)
        val report = JSONObject().put("frameRotation", true).put("catalog", true).put("decodedVideo", true).put("audioTrack", audio != null).put("pause", true).put("seek", true).put("speed", true).put("positionSeconds", number("time-pos"))
        val output = Path.of("build/reports", reportName)
        Files.createDirectories(output.parent); Files.writeString(output, report.toString(2))
        println("PLAYBACK PASS: decoding, pause, seek, speed. Report saved without source URLs.")
        Thread.sleep(1500)
    }

    fun controller() = runBlocking {
        val repository = ContentRepository(DesktopNetwork.client())
        val detail = repository.detail(repository.discover(DramaCategory.REAL).items.first().id)
        check(detail.episodes.size > 1)
        val file = Files.createTempDirectory("hg-playback-acceptance").resolve("library.json")
        val library = DesktopLibrary(file)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = DesktopPlayback(scope, repository, library)
        suspend fun until(stage: String, condition: () -> Boolean) {
            println("Controller stage: $stage")
            try { withTimeout(90_000) {
                while (!withContext(Dispatchers.Main) { check(player.message == null || player.preparing || player.running) { "Playback stopped with an error" }; condition() }) delay(250)
            } } catch (e: Exception) {
                withContext(Dispatchers.Main) { println("Controller state: index=${player.index}, time=${player.position}, duration=${player.duration}, preparing=${player.preparing}, running=${player.running}, paused=${player.paused}, error=${player.message != null}") }
                throw e
            }
        }
        try {
            withContext(Dispatchers.Main) { player.play(detail, 0) }
            until("initial playback") { player.duration > 0 && player.position >= 2 }
            withContext(Dispatchers.Main) { player.togglePause() }
            until("pause") { player.paused }
            withContext(Dispatchers.Main) { player.seek(15.0); player.setSpeed(1.25f); player.togglePause() }
            until("seek and resume") { !player.paused && player.position >= 15 }
            withContext(Dispatchers.Main) { player.seek(player.duration - 2) }
            until("automatic next episode") { player.index == 1 && player.duration > 0 && player.position >= 3 }
            withContext(Dispatchers.Main) { player.stop() }
            val saved = DesktopLibrary(file).history[detail.series.id]!!
            check(saved.episodeId == detail.episodes[1] && saved.position >= 2500)
            withContext(Dispatchers.Main) { player.resume(detail) }
            until("persisted resume") { player.index == 1 && player.duration > 0 && player.position * 1000 >= saved.position }
            withContext(Dispatchers.Main) { player.sleepEpisode(); player.seek(player.duration - 2) }
            until("sleep after episode") { player.active == null && !player.running }
            println("CONTROLLER PASS: play, pause, seek, speed, automatic next episode, persisted progress, resume and stop after episode")
            val report = Path.of("build/reports/desktop-controller-smoke.json")
            Files.createDirectories(report.parent)
            Files.writeString(report, JSONObject().put("sleepAfterEpisode", true).put("automaticNext", true).put("persistedResume", true).put("pauseSeekSpeed", true).toString(2))
        } finally {
            withContext(Dispatchers.Main) { player.close() }
            scope.cancel()
            Files.deleteIfExists(file); Files.deleteIfExists(file.parent)
        }
    }
}
