package com.hongguotv.nativeapp

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class PlaybackRenderersTest {
    private data class Decoder(val name: String, val software: Boolean, val supported: Boolean)

    private val supportedHardware = Decoder("supported hardware", software = false, supported = true)
    private val unsupportedHardware = Decoder("unsupported hardware", software = false, supported = false)
    private val software = Decoder("software", software = true, supported = false)

    private fun order(vararg candidates: Decoder) =
        orderVideoDecoders(candidates.toList(), Decoder::software, Decoder::supported)

    @Test fun supportedHardwareRunsFirstWithSoftwareAsInitializationFallback() {
        assertEquals(
            listOf(supportedHardware, software),
            order(software, unsupportedHardware, supportedHardware),
        )
    }

    @Test fun softwareReplacesUnsupportedHardwareEvenWhenSoftwareReportsALowerLevel() {
        assertEquals(listOf(software), order(unsupportedHardware, software))
    }

    @Test fun unsupportedHardwareRemainsAvailableWhenThereIsNoSoftwareDecoder() {
        assertEquals(listOf(unsupportedHardware), order(unsupportedHardware))
        assertEquals(listOf(supportedHardware, unsupportedHardware), order(unsupportedHardware, supportedHardware))
    }

    @Test fun noCandidatesRemainEmpty() {
        assertEquals(emptyList<Decoder>(), order())
    }

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
