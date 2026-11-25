// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

@androidx.media3.common.util.UnstableApi
internal fun playbackRenderers(context: Context, mode: VideoDecodeMode): DefaultRenderersFactory =
    PlaybackRenderers(context, mode)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(audioExtensionRendererMode(Build.VERSION.SDK_INT, BuildConfig.LEGACY_AUDIO))
        .apply {
            if (mode == VideoDecodeMode.SOFTWARE_ONLY) {
                // Apply the preference to track capability queries as well as codec initialization.
                setMediaCodecSelector { mime, secure, tunneling ->
                    val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                    if (MimeTypes.isVideo(mime)) codecs.filter { it.softwareOnly } else codecs
                }
            }
        }

internal fun <T> videoDecoderOrder(
    candidates: List<T>, mode: VideoDecodeMode, softwareOnly: (T) -> Boolean, supported: (T) -> Boolean,
): List<T> {
    val (software, platform) = candidates.partition(softwareOnly)
    if (mode == VideoDecodeMode.SOFTWARE_ONLY) return software
    return platform.filter(supported) + software
}

@androidx.media3.common.util.UnstableApi
private class ModeVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val mode: VideoDecodeMode,
) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val candidates = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        val ordered = videoDecoderOrder(candidates, mode, { it.softwareOnly }) { it.isFormatSupported(format) }
        if (mode == VideoDecodeMode.HARDWARE_PREFERRED && ordered.firstOrNull()?.softwareOnly == true) {
            Log.i("HGPlayback", "Video decoder selection uses software for ${format.sampleMimeType} ${format.codecs}")
        }
        return ordered
    }

    override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format): DecoderReuseEvaluation {
        if (!codecInfo.softwareOnly && !codecInfo.isFormatSupported(newFormat)) {
            return DecoderReuseEvaluation(
                codecInfo.name, oldFormat, newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE,
            )
        }
        return super.canReuseCodec(codecInfo, oldFormat, newFormat)
    }
}

@androidx.media3.common.util.UnstableApi
internal fun audioExtensionRendererMode(sdk: Int, legacyAudio: Boolean): Int =
    if (legacyAudio && sdk <= 22) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

@androidx.media3.common.util.UnstableApi
private class PlaybackRenderers(context: Context, private val mode: VideoDecodeMode) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        // This project ships no video extension renderer; the legacy FFmpeg extension is audio only.
        out += ModeVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(50)
                .experimentalSetLateThresholdToDropDecoderInputUs(50_000),
            mode,
        )
    }

    /** Keep PCM available to AudioFlinger through brief playback-thread stalls on Android 5. */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (Build.VERSION.SDK_INT > 22) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(
                DefaultAudioTrackBufferSizeProvider.Builder().setMinPcmBufferDurationUs(650_000).build()
            )
            .build()
    }
}
