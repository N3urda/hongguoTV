package com.hongguotv.nativeapp

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class PlaybackRenderersTest {
    @Test fun defaultBuildRetainsAndroid5AacWorkaround() {
        for (sdk in listOf(21, 22)) {
            assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                audioExtensionRendererMode(sdk, false))
        }
    }

    @Test fun defaultBuildAlreadyDisablesExtensionsOnModernAndroid() {
        for (sdk in listOf(23, 26, 30, 36)) {
            assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                audioExtensionRendererMode(sdk, false))
        }
    }

    @Test fun diagnosticBuildNeverRequestsMissingExtension() {
        for (sdk in listOf(21, 22, 23, 26, 30, 36)) {
            assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                audioExtensionRendererMode(sdk, true))
        }
    }
}
