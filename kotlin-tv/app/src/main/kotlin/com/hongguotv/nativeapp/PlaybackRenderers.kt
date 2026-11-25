// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
internal fun playbackRenderers(
    context: Context,
    forceSoftwareDecoding: () -> Boolean,
): DefaultRenderersFactory =
    PlaybackRenderers(context, forceSoftwareDecoding)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(
            audioExtensionRendererMode(Build.VERSION.SDK_INT, BuildConfig.LEGACY_AUDIO)
        )

internal fun <T> orderVideoDecoders(
    candidates: List<T>,
    softwareOnly: (T) -> Boolean,
    supportsFormat: (T) -> Boolean,
): List<T> {
    val (software, hardware) = candidates.partition(softwareOnly)
    val (supported, unsupported) = hardware.partition(supportsFormat)
    // Some streams declare a higher level than they need. Keep software in the candidate list
    // even when its advertised level is lower, and try unsupported hardware only without software.
    return supported + software.ifEmpty { unsupported }
}

@UnstableApi
private class PlaybackVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val forceSoftwareDecoding: () -> Boolean,
) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> =
        orderVideoDecoders(
            super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder),
            { it.softwareOnly },
            { it.isFormatSupported(format) },
        )

    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
    ): DecoderReuseEvaluation {
        if (!codecInfo.softwareOnly && (forceSoftwareDecoding() || !codecInfo.isFormatSupported(newFormat))) {
            return DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE,
            )
        }
        return super.canReuseCodec(codecInfo, oldFormat, newFormat)
    }
}

@UnstableApi
internal fun audioExtensionRendererMode(sdk: Int, legacyAudio: Boolean): Int =
    if (legacyAudio && sdk <= 22) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

@UnstableApi
private class PlaybackRenderers(
    context: Context,
    private val forceSoftwareDecoding: () -> Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Media3 uses this selector for both track capabilities and codec initialization.
        val videoSelector = MediaCodecSelector { mimeType, secure, tunneling ->
            val candidates = mediaCodecSelector.getDecoderInfos(mimeType, secure, tunneling)
            if (forceSoftwareDecoding()) candidates.filter { it.softwareOnly } else candidates
        }
        // The only bundled extension is the Android 5 audio renderer.
        out += PlaybackVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(videoSelector)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                // Shed disposable frames before codec work when video falls behind audio.
                .experimentalSetLateThresholdToDropDecoderInputUs(50_000),
            forceSoftwareDecoding,
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
