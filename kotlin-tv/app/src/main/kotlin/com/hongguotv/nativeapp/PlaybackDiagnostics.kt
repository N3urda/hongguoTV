// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime

internal data class ActiveVideoDecoder(val name: String, val hardwareAccelerated: Boolean, val softwareOnly: Boolean) {
    val typeLabel: String
        get() = when {
            softwareOnly -> "软解"
            hardwareAccelerated -> "硬解"
            else -> "类型未知"
        }
}

@androidx.media3.common.util.UnstableApi
internal fun playbackVideoInfo(quality: String, format: Format?, decoder: ActiveVideoDecoder?): String = buildString {
    if (quality.isNotBlank()) appendLine("播放档位 $quality")
    if (format != null) {
        if (format.width > 0 && format.height > 0) appendLine("片源尺寸 ${format.width} × ${format.height}")
        val codec = when (format.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            else -> format.sampleMimeType
        }
        val codecLabel = listOfNotNull(codec, format.codecs?.takeIf(String::isNotBlank)).joinToString(" · ")
        if (codecLabel.isNotEmpty()) appendLine("视频编码 $codecLabel")
    }
    append(if (decoder == null) "解码器 尚未初始化" else "解码器 ${decoder.typeLabel} · ${decoder.name}")
}

/** Event timestamps and media positions allow correlation with the periodic decoder counters. */
@androidx.media3.common.util.UnstableApi
internal class PlaybackDiagnostics(private val screen: String) : AnalyticsListener {
    @Volatile var videoDecoder: ActiveVideoDecoder? = null

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
