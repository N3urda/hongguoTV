// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/** Android 5's platform AAC decoder can stall without reporting an error. */
@androidx.media3.common.util.UnstableApi
internal fun playbackRenderers(context: Context): DefaultRenderersFactory =
    (if (Build.VERSION.SDK_INT <= 22) LegacyPlaybackRenderers(context) else DefaultRenderersFactory(context))
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(
            audioExtensionRendererMode(Build.VERSION.SDK_INT, BuildConfig.LEGACY_AUDIO)
        )

@androidx.media3.common.util.UnstableApi
internal fun audioExtensionRendererMode(sdk: Int, legacyAudio: Boolean): Int =
    if (legacyAudio && sdk <= 22) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

/** Keep PCM available to AudioFlinger through brief playback-thread stalls on Android 5. */
@androidx.media3.common.util.UnstableApi
private class LegacyPlaybackRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioTrackBufferSizeProvider(
            DefaultAudioTrackBufferSizeProvider.Builder().setMinPcmBufferDurationUs(650_000).build()
        )
        .build()
}
