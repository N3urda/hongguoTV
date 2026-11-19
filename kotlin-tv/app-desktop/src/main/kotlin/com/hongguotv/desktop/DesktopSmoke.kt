// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.*
import kotlinx.coroutines.*
import org.openani.mediamp.mpv.MPVHandle
import java.nio.file.Path
import java.nio.file.Files
import java.awt.Window
import java.awt.Robot
import java.awt.Rectangle

/** Opt-in acceptance through the same window, controller and media surface used by the app. */
object DesktopSmoke {
    suspend fun verify(source: LiveCatalogSource, player: DesktopPlayback, state: DesktopState, file: Path, window: Window) {
        val detail = withContext(Dispatchers.IO) {
            source.repository.detail(source.repository.discover(DramaCategory.REAL).items.first().id)
        }
        require(detail.episodes.size >= 2)
        suspend fun until(stage: String, condition: () -> Boolean) {
            println("Checking $stage")
            withTimeout(90_000) {
                while (!condition()) {
                    check(player.message == null) { "Playback failed during $stage" }
                    delay(150)
                }
            }
        }

        state.open(detail.series)
        until("episode list") { state.detail != null }
        state.selectEpisode(0)
        until("embedded playback") { player.position >= 2 && player.duration > 20 }
        val native = player.engine!!.impl as MPVHandle
        check(native.getPropertyInt("video-params/w") > 0 && native.getPropertyInt("audio-params/samplerate") > 0)
        // Sample the centre of the actual video pane, away from controls and letterboxing.
        window.toFront()
        delay(500)
        val origin = window.locationOnScreen
        val region = Rectangle(origin.x + window.width / 2 + 48, origin.y + window.height / 2, 48, 48)
        val robot = Robot(window.graphicsConfiguration.device)
        fun pixels() = robot.createScreenCapture(region).getRGB(0, 0, 48, 48, null, 0, 48)
        val initial = pixels()
        until("moving picture in the app window") { val frame = pixels(); frame.distinct().size > 12 && frame.indices.count { frame[it] != initial[it] } > 300 }

        player.togglePause()
        until("pause") { player.paused }
        player.seek(15.0)
        until("seek while paused") { player.position in 14.0..16.0 }
        player.togglePause()
        until("play after seek") { player.position >= 16 }
        player.next(1)
        until("next episode") { player.index == 1 && player.running && player.position >= 2 }
        player.seek(12.0)
        until("second episode position") { player.position in 12.0..15.0 }
        player.stop()
        val saved = DesktopLibrary(file).history.getValue(detail.series.id)
        check(saved.episodeId == detail.episodes[1] && saved.position >= 12_000)
        state.resume()
        until("resume saved episode and time") { player.index == 1 && player.running && player.duration > 0 && player.position * 1000 in saved.position.toDouble()..(saved.position + 3000).toDouble() }
        println("PASS embedded frames, audio track, pause, seek, next episode and resume at ${saved.position} ms")
    }
}
