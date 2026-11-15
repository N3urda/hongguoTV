package com.hongguotv.nativeapp

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class PlaybackRenderersTest {
    @Test fun platformBuildNeverEnablesExtensions() {
        for (sdk in listOf(21, 22, 23, 30, 36)) {
            assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                audioExtensionRendererMode(sdk, false))
        }
    }
    @Test fun compatibilityBuildOnlyUsesExtensionsOnAndroid5() {
        for (sdk in listOf(21, 22, 23, 30, 36)) {
            val expected = if (sdk <= 22) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            assertEquals(expected, audioExtensionRendererMode(sdk, true))
        }
    }
}
