// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime

/** Event timestamps and media positions allow correlation with the periodic decoder counters. */
@androidx.media3.common.util.UnstableApi
internal class PlaybackDiagnostics(private val screen: String) : AnalyticsListener {
    private fun record(event: EventTime, message: String) {
        Log.i("HGPlayback", "$screen tMs=${event.realtimeMs} item=${event.currentWindowIndex} posMs=${event.currentPlaybackPositionMs} bufferedMs=${event.totalBufferedDurationMs} $message")
    }

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) =
        record(eventTime, "dropped=$droppedFrames intervalMs=$elapsedMs")

    override fun onAudioUnderrun(eventTime: EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
        record(eventTime, "audioUnderrun bufferBytes=$bufferSize audioBufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs")

    override fun onAudioTrackInitialized(eventTime: EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
        record(eventTime, "audioTrackBytes=${audioTrackConfig.bufferSize} rate=${audioTrackConfig.sampleRate} encoding=${audioTrackConfig.encoding}")

    override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) =
        record(eventTime, "state=$state")

    override fun onAudioDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
        record(eventTime, "audioDecoder=$decoderName")

    override fun onVideoDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
        record(eventTime, "videoDecoder=$decoderName")
}
