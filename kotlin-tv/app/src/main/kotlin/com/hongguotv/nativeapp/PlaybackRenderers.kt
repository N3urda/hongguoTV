// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Build
import androidx.media3.exoplayer.DefaultRenderersFactory

/** Android 5's platform AAC decoder can stall without reporting an error. */
@androidx.media3.common.util.UnstableApi
internal fun playbackRenderers(context: Context): DefaultRenderersFactory =
    DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(
            audioExtensionRendererMode(Build.VERSION.SDK_INT, BuildConfig.LEGACY_AUDIO)
        )

@androidx.media3.common.util.UnstableApi
internal fun audioExtensionRendererMode(sdk: Int, legacyAudio: Boolean): Int =
    if (legacyAudio && sdk <= 22) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
