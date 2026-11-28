package com.hongguotv.nativeapp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@androidx.media3.common.util.UnstableApi
class PlaybackDecoderRecoveryTest {
    private val hardware = ActiveVideoDecoder("OMX.vendor.hevc.decoder", true, false)
    private val software = ActiveVideoDecoder("OMX.google.hevc.decoder", false, true)
    private fun failure(mime: String = MimeTypes.VIDEO_H265, code: Int = PlaybackException.ERROR_CODE_DECODING_FAILED) =
        ExoPlaybackException.createForRenderer(IllegalStateException("injected decoder failure"), "fixture", 0,
            Format.Builder().setSampleMimeType(mime).build(), C.FORMAT_HANDLED, null, false, code)

    @Test fun runningHardwareFailureCanRetrySoftwareOnlyOnceForThatEpisode() {
        val recovery = PlaybackDecoderRecovery()
        assertTrue(recovery.trySoftware("101", VideoDecoderMode.AUTOMATIC, failure(), hardware, true))
        assertEquals(VideoDecoderMode.SOFTWARE_ONLY, recovery.mode("101", VideoDecoderMode.AUTOMATIC))
        assertFalse(recovery.trySoftware("101", VideoDecoderMode.AUTOMATIC, failure(), hardware, true))
        assertEquals(VideoDecoderMode.AUTOMATIC, recovery.mode("102", VideoDecoderMode.AUTOMATIC))
        recovery.clear("101")
        assertEquals(VideoDecoderMode.AUTOMATIC, recovery.mode("101", VideoDecoderMode.AUTOMATIC))
    }

    @Test fun audioAndNetworkFailuresNeverChangeVideoDecoding() {
        val recovery = PlaybackDecoderRecovery()
        val audio = failure(MimeTypes.AUDIO_AAC)
        assertFalse(isVideoDecoderFailure(audio))
        assertFalse(recovery.trySoftware("101", VideoDecoderMode.AUTOMATIC, audio, hardware, true))
        val network = PlaybackException("network", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertFalse(isVideoDecoderFailure(network))
        assertFalse(recovery.trySoftware("101", VideoDecoderMode.AUTOMATIC, network, hardware, true))
    }

    @Test fun initializationExhaustionSoftwareFailureAndMissingSoftwareDoNotLoop() {
        val recovery = PlaybackDecoderRecovery()
        assertFalse(recovery.trySoftware("101", VideoDecoderMode.AUTOMATIC,
            failure(code = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED), hardware, true))
        assertFalse(recovery.trySoftware("101", VideoDecoderMode.AUTOMATIC, failure(), software, true))
        assertFalse(recovery.trySoftware("101", VideoDecoderMode.AUTOMATIC, failure(), hardware, false))
        assertFalse(recovery.trySoftware("101", VideoDecoderMode.SOFTWARE_ONLY, failure(), hardware, true))
    }

    @Test fun lastDecoderSurvivesReleaseForFailureDiagnosis() {
        val diagnostics = PlaybackDiagnostics("test")
        diagnostics.videoDecoder = hardware
        diagnostics.videoDecoder = null
        assertNull(diagnostics.videoDecoder)
        assertEquals(hardware, diagnostics.lastVideoDecoder)
    }
}
