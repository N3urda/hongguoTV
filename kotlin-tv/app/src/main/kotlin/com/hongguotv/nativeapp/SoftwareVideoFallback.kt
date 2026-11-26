// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime

/** Reduce quality when a 32-bit TV keeps dropping frames on a HEVC software decoder. */
@UnstableApi
internal class SoftwareVideoFallback(
    private val player: ExoPlayer,
    private val diagnostics: PlaybackDiagnostics,
    private val onOverload: (ExoPlayer) -> Unit,
) : AnalyticsListener {
    private val drops = SoftwareVideoDropGuard()
    override fun onVideoDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
        drops.reset()

    override fun onVideoDecoderReleased(eventTime: EventTime, decoderName: String) = drops.reset()

    override fun onIsPlayingChanged(eventTime: EventTime, isPlaying: Boolean) {
        if (!isPlaying) drops.reset()
    }

    override fun onPositionDiscontinuity(eventTime: EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        drops.reset()

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
        if (diagnostics.videoDecoder?.softwareOnly == true && player.isPlaying && player.videoFormat?.sampleMimeType == MimeTypes.VIDEO_H265 &&
            eventTime.mediaPeriodId != null && eventTime.mediaPeriodId == eventTime.currentMediaPeriodId &&
            drops.offer(droppedFrames, elapsedMs, eventTime.realtimeMs)
        ) onOverload(player)
    }

    companion object {
        const val QUALITY_LIMIT = 720
    }
}

internal class SoftwareVideoDropGuard {
    private var lastLargeDropAt: Long? = null

    fun offer(droppedFrames: Int, elapsedMs: Long, timestampMs: Long): Boolean {
        if (droppedFrames < 50 || elapsedMs !in 1L..10_000L) {
            reset()
            return false
        }
        val previous = lastLargeDropAt
        lastLargeDropAt = timestampMs
        return previous != null && timestampMs - previous in 1L..12_000L
    }

    fun reset() {
        lastLargeDropAt = null
    }
}
