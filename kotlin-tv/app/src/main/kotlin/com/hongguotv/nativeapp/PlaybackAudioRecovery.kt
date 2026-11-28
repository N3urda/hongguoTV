// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Build
import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Android 5 can keep an audio clock ahead of output after a long PCM underrun. */
@UnstableApi
internal class PlaybackAudioRecovery(
    private val player: ExoPlayer,
    private val enabled: Boolean = Build.VERSION.SDK_INT <= 22,
    private val canRecover: () -> Boolean = { true },
) : AnalyticsListener {
    private val handler = Handler(player.applicationLooper)
    private var revision = 0
    private var pending = false
    private var episode: String? = null
    private var attempts = 0
    private var recoveredAt: Long? = null

    private fun invalidate() { revision++; pending = false }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        if (!isPlaying) invalidate()
    }

    override fun onPlaybackParametersChanged(eventTime: AnalyticsListener.EventTime, parameters: PlaybackParameters) = invalidate()

    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = invalidate()

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
        bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        if (!enabled || pending || bufferSizeMs <= 0 || elapsedSinceLastFeedMs - bufferSizeMs < 150 ||
            eventTime.mediaPeriodId == null || eventTime.mediaPeriodId != eventTime.currentMediaPeriodId ||
            !player.isPlaying || player.totalBufferedDuration < 2_000 || player.audioFormat == null || player.videoFormat == null) return
        val id = player.currentMediaItem?.mediaId ?: return
        if (episode != id) { episode = id; attempts = 0; recoveredAt = null }
        if (attempts >= 3 || recoveredAt?.let { eventTime.realtimeMs - it < 10_000 } == true) return
        val ticket = revision
        pending = true
        handler.post {
            if (revision != ticket) return@post
            pending = false
            if (!player.isPlaying || player.playbackState != Player.STATE_READY || player.playerError != null ||
                player.currentMediaItem?.mediaId != id || player.totalBufferedDuration < 2_000 || !canRecover()) return@post
            val position = player.currentPosition
            if (position <= 0) return@post
            attempts++
            recoveredAt = eventTime.realtimeMs
            android.util.Log.i("HGPlayback", "Recreating legacy audio clock after underrun gapMs=${elapsedSinceLastFeedMs - bufferSizeMs} attempt=$attempts")
            // Media3 ignores a seek to the same millisecond. Move back 1ms so the audio
            // renderer actually flushes its stale AudioTrack clock, without skipping content.
            player.seekTo(position - 1)
        }
    }
}
