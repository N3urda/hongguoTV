package com.hongguotv.nativeapp

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialSelfHealTest {
    private val decoderInit = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    private val decoderUnsupported = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
    private val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED

    @Test
    fun healsDecoderFailuresWithTrackWithinBudget() {
        assertTrue(shouldSelfHealTrial(decoderInit, hasTrack = true, attempts = 0))
        assertTrue(shouldSelfHealTrial(decoderUnsupported, hasTrack = true, attempts = 1))
        assertTrue(
            shouldSelfHealTrial(
                decoderInit,
                hasTrack = true,
                attempts = TRIAL_SELF_HEAL_LIMIT - 1,
            )
        )
        assertFalse(
            shouldSelfHealTrial(decoderInit, hasTrack = true, attempts = TRIAL_SELF_HEAL_LIMIT)
        )
        assertFalse(
            shouldSelfHealTrial(
                decoderInit,
                hasTrack = true,
                attempts = TRIAL_SELF_HEAL_LIMIT + 1,
            )
        )
    }

    @Test
    fun ignoresNetworkErrorsAndMissingTracks() {
        assertFalse(shouldSelfHealTrial(io, hasTrack = true, attempts = 0))
        assertFalse(shouldSelfHealTrial(null, hasTrack = true, attempts = 0))
        assertFalse(shouldSelfHealTrial(decoderInit, hasTrack = false, attempts = 0))
    }
}
