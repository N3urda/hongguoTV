// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
internal fun playbackRenderers(
    context: Context,
    diagnostics: PlaybackDiagnostics? = null,
    videoDecoderMode: () -> VideoDecoderMode,
): DefaultRenderersFactory =
    PlaybackRenderers(context, diagnostics, videoDecoderMode)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(
            audioExtensionRendererMode(Build.VERSION.SDK_INT, BuildConfig.LEGACY_AUDIO)
        )

internal fun <T> orderVideoDecoders(
    candidates: List<T>,
    softwareOnly: (T) -> Boolean,
    hardwareAccelerated: (T) -> Boolean,
    supportsFormat: (T) -> Boolean,
    supportsCompatibleHardware: (T) -> Boolean = { false },
): List<T> {
    val (software, others) = candidates.partition(softwareOnly)
    val (supported, unsupported) = others.partition(supportsFormat)
    val preferred = supported.sortedByDescending(hardwareAccelerated)
    if (software.isEmpty()) return preferred + unsupported
    return preferred + unsupported.filter(supportsCompatibleHardware) + software
}

// Some HEVC streams declare a higher level than their coded dimensions and frame rate need.
@UnstableApi
internal fun supportsHevcWithoutLevel(codec: MediaCodecInfo, format: Format): Boolean =
    codec.hardwareAccelerated && format.sampleMimeType == MimeTypes.VIDEO_H265 &&
        format.width > 0 && format.height > 0 && codec.isFormatFunctionallySupported(format) &&
        codec.isVideoSizeAndRateSupportedV21(format.width, format.height, format.frameRate.toDouble())

@UnstableApi
private class PlaybackVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val diagnostics: PlaybackDiagnostics?,
    private val videoDecoderMode: () -> VideoDecoderMode,
) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val tryCompatibleHardware = videoDecoderMode() == VideoDecoderMode.COMPATIBLE_HARDWARE
        return orderVideoDecoders(
            super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder),
            { it.softwareOnly },
            { it.hardwareAccelerated },
            { it.isFormatSupported(format) },
        ) { tryCompatibleHardware && supportsHevcWithoutLevel(it, format) }
    }

    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
    ): DecoderReuseEvaluation {
        val mode = videoDecoderMode()
        val canUseDecoder = codecInfo.softwareOnly ||
            (mode != VideoDecoderMode.SOFTWARE_ONLY && (codecInfo.isFormatSupported(newFormat) ||
                (mode == VideoDecoderMode.COMPATIBLE_HARDWARE && supportsHevcWithoutLevel(codecInfo, newFormat))))
        if (!canUseDecoder) {
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

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        diagnostics?.videoDecoder = configuration.codecInfo.let {
            ActiveVideoDecoder(name, it.hardwareAccelerated, it.softwareOnly)
        }
    }

    override fun onCodecReleased(name: String) {
        diagnostics?.videoDecoder = null
        super.onCodecReleased(name)
    }
}

@UnstableApi
internal fun audioExtensionRendererMode(sdk: Int, legacyAudio: Boolean): Int =
    if (legacyAudio && sdk <= 22) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

@UnstableApi
private class PlaybackRenderers(
    context: Context,
    private val diagnostics: PlaybackDiagnostics?,
    private val videoDecoderMode: () -> VideoDecoderMode,
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
            if (videoDecoderMode() == VideoDecoderMode.SOFTWARE_ONLY) candidates.filter { it.softwareOnly } else candidates
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
            diagnostics,
            videoDecoderMode,
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
