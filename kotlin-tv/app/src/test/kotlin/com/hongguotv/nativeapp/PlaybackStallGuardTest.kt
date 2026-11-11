package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PlaybackStallGuardTest {
    @Test fun stalledFramesRecoverTwiceAtMostPerEpisode() {
        val guard = PlaybackStallGuard()
        assertFalse(guard.offer("1", 0, 10, true))
        assertTrue(guard.offer("1", 20_000, 10, true))
        assertFalse(guard.offer("1", 21_000, 0, true))
        assertTrue(guard.offer("1", 41_000, 0, true))
        assertFalse(guard.offer("1", 42_000, 0, true))
        assertFalse(guard.offer("1", 100_000, 0, true))
        assertFalse(guard.offer("2", 101_000, 0, true))
        assertTrue(guard.offer("2", 121_000, 0, true))
    }

    @Test fun progressPauseBufferingAndDecoderReplacementResetObservation() {
        val guard = PlaybackStallGuard()
        assertFalse(guard.offer("1", 0, 10, true))
        assertFalse(guard.offer("1", 19_000, 11, true))
        assertFalse(guard.offer("1", 38_000, 11, true))
        assertFalse(guard.offer("1", 39_000, 11, false))
        assertFalse(guard.offer("1", 80_000, 11, true))
        assertFalse(guard.offer("1", 99_000, 0, true))
        assertFalse(guard.offer("1", 118_000, 0, true))
        assertTrue(guard.offer("1", 119_000, 0, true))
    }

    @Test fun backgroundTimeDoesNotCountAsStalledPlayback() {
        val guard = PlaybackStallGuard()
        assertFalse(guard.offer("1", 0, 10, true))
        guard.suspendObservation()
        assertFalse(guard.offer("1", 60_000, 10, true))
        assertFalse(guard.offer("1", 79_000, 10, true))
        assertTrue(guard.offer("1", 80_000, 10, true))
    }

    @Test fun audioOnlyOrMissingEpisodeNeverTriggers() {
        val guard = PlaybackStallGuard()
        assertFalse(guard.offer("1", 0, null, true))
        assertFalse(guard.offer("1", 60_000, null, true))
        assertFalse(guard.offer("", 0, 0, true))
        assertFalse(guard.offer("", 60_000, 0, true))
    }
}
