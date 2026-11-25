package com.hongguotv.nativeapp

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.media3.common.util.UnstableApi
class PlaybackRenderersTest {
    private data class Decoder(val software: Boolean, val supportsFormat: Boolean)
    private val rejectedHardware = Decoder(false, false)
    private val compatibleHardware = Decoder(false, true)
    // Some working software decoders also report an exceeds-capabilities warning for this stream.
    private val software = Decoder(true, false)

    private fun order(mode: VideoDecodeMode, vararg candidates: Decoder) =
        videoDecoderOrder(candidates.toList(), mode, Decoder::software, Decoder::supportsFormat)

    @Test fun hardwarePreferredSkipsUnsupportedHardwareBeforeCodecInitialization() {
        assertEquals(listOf(software), order(VideoDecodeMode.HARDWARE_PREFERRED, rejectedHardware, software))
        assertEquals(emptyList<Decoder>(), order(VideoDecodeMode.HARDWARE_PREFERRED, rejectedHardware))
    }

    @Test fun compatibleHardwareRunsFirstWithSoftwareAsInitializationFallback() {
        assertEquals(listOf(compatibleHardware, software),
            order(VideoDecodeMode.HARDWARE_PREFERRED, software, rejectedHardware, compatibleHardware))
    }

    @Test fun softwareOnlyNeverOpensHardware() {
        assertEquals(listOf(software), order(VideoDecodeMode.SOFTWARE_ONLY, compatibleHardware, software))
        assertEquals(emptyList<Decoder>(), order(VideoDecodeMode.SOFTWARE_ONLY, compatibleHardware))
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
