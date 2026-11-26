package com.hongguotv.nativeapp

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class PlaybackRenderersTest {
    private data class Decoder(
        val name: String,
        val software: Boolean,
        val supported: Boolean,
        val levelMismatchOnly: Boolean = false,
        val hardwareAccelerated: Boolean = !software,
    )

    private val supportedHardware = Decoder("supported hardware", software = false, supported = true)
    private val unsupportedHardware = Decoder("unsupported hardware", software = false, supported = false)
    private val levelMismatch = unsupportedHardware.copy(name = "HEVC level mismatch", levelMismatchOnly = true)
    private val software = Decoder("software", software = true, supported = false)
    private val unclassified = supportedHardware.copy(name = "unclassified", hardwareAccelerated = false)

    private fun order(vararg candidates: Decoder, tryCompatibleHardware: Boolean = false) =
        orderVideoDecoders(candidates.toList(), Decoder::software, Decoder::hardwareAccelerated, Decoder::supported) {
            tryCompatibleHardware && it.levelMismatchOnly
        }

    @Test fun supportedHardwareRunsFirstWithSoftwareAsInitializationFallback() {
        assertEquals(
            listOf(supportedHardware, unclassified, software),
            order(software, unsupportedHardware, unclassified, supportedHardware),
        )
    }

    @Test fun softwareReplacesUnsupportedHardwareEvenWhenSoftwareReportsALowerLevel() {
        assertEquals(listOf(software), order(unsupportedHardware, levelMismatch, software))
    }

    @Test fun compatibilityModeTriesHardwareForLevelMismatchAndKeepsSoftwareFallback() {
        assertEquals(
            listOf(supportedHardware, levelMismatch, software),
            order(unsupportedHardware, software, levelMismatch, supportedHardware, tryCompatibleHardware = true),
        )
        assertEquals(listOf(software), order(unsupportedHardware, software, tryCompatibleHardware = true))
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
