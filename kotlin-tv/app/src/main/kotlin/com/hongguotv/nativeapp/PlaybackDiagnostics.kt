// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime

internal fun systemMediaVolumeInfo(context: Context): String = try {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    if (manager == null) "系统媒体音量 无法读取" else buildString {
        append("系统媒体音量 ${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        if (Build.VERSION.SDK_INT >= 23 && manager.isStreamMute(AudioManager.STREAM_MUSIC)) append(" · 已静音")
        if (manager.isVolumeFixed) append(" · 系统固定音量")
    }
} catch (_: RuntimeException) { "系统媒体音量 无法读取" }

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
    @Volatile var lastAudioDecoder: String? = null
        private set
    @Volatile var audioTrackConfig: AudioSink.AudioTrackConfig? = null
        private set
    private var activeAudioTracks = 0
    @Volatile var lastVideoDecoder: ActiveVideoDecoder? = null
        private set
    @Volatile var lastVideoFormat: Format? = null
        private set
    @Volatile var videoDecoder: ActiveVideoDecoder? = null
        set(value) {
            field = value
            if (value != null) lastVideoDecoder = value
        }

    private fun record(event: EventTime, message: String) {
        Log.i("HGPlayback", "$screen tMs=${event.realtimeMs} item=${event.currentWindowIndex} posMs=${event.currentPlaybackPositionMs} bufferedMs=${event.totalBufferedDurationMs} $message")
    }

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) =
        record(eventTime, "dropped=$droppedFrames intervalMs=$elapsedMs")

    override fun onAudioUnderrun(eventTime: EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
        record(eventTime, "audioUnderrun bufferBytes=$bufferSize audioBufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs")

    override fun onAudioTrackInitialized(eventTime: EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        activeAudioTracks++
        this.audioTrackConfig = audioTrackConfig
        record(eventTime, "audioTrackBytes=${audioTrackConfig.bufferSize} rate=${audioTrackConfig.sampleRate} encoding=${audioTrackConfig.encoding}")
    }

    override fun onAudioTrackReleased(eventTime: EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        // AudioTrack release is asynchronous and can arrive after the replacement is initialized.
        activeAudioTracks = (activeAudioTracks - 1).coerceAtLeast(0)
        if (activeAudioTracks == 0) this.audioTrackConfig = null
        record(eventTime, "audioTrackReleased=true")
    }

    override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) =
        record(eventTime, "state=$state")

    override fun onAudioDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        lastAudioDecoder = decoderName
        record(eventTime, "audioDecoder=$decoderName")
    }

    fun audioInfo(format: Format?, selected: Boolean, volume: Float?): String = buildString {
        appendLine(if (selected) "音轨 已选择" else "音轨 未选择")
        format?.let { appendLine("音频格式 ${it.sampleMimeType} · ${it.sampleRate} Hz · ${it.channelCount} 声道") }
        appendLine("最近音频解码器 ${lastAudioDecoder ?: "尚未初始化"}")
        val output = audioTrackConfig
        append(if (output == null) "音频输出 未创建" else
            "音频输出 ${output.sampleRate} Hz · encoding=${output.encoding} · buffer=${output.bufferSize}")
        volume?.let { append("\n播放器音量 ${(it * 100).toInt()}%") }
    }

    override fun onVideoDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
        record(eventTime, "videoDecoder=$decoderName initMs=$initializationDurationMs")

    override fun onVideoDecoderReleased(eventTime: EventTime, decoderName: String) =
        record(eventTime, "videoDecoderReleased=$decoderName")

    override fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        lastVideoFormat = format
        record(eventTime, "videoFormat=${format.sampleMimeType}/${format.codecs} size=${format.width}x${format.height} " +
            "fps=${format.frameRate} reuse=${decoderReuseEvaluation?.result} discard=${decoderReuseEvaluation?.discardReasons}")
    }
}
