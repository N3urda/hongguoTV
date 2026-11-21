package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PlaybackSyncGuardTest {
    private var clock = 1_000L

    private fun guard(
        thresholdUs: Long = 150_000,
        minFrames: Int = 120,
        cooldownMs: Long = 30_000,
    ) = PlaybackSyncGuard(thresholdUs, minFrames, cooldownMs) { clock }

    @Test
    fun accumulatesUntilWindowCloses() {
        val g = guard()
        assertNull(g.offer(1_000_000, 60, true))
        val window = g.offer(1_400_000, 60, true)
        assertNotNull(window)
        assertEquals(20_000, window!!.averageUs) // (1.0M + 1.4M) / 120 frames
        assertFalse(window.resync)
    }

    @Test
    fun sustainedLateFramesAdviseOneResyncThenCoolDown() {
        val g = guard()
        clock += 31_000 // past the startup cooldown
        assertTrue(g.offer(-48_000_000, 120, true)!!.resync) // 400ms late on average
        val again = g.offer(-48_000_000, 120, true)
        assertNotNull(again)
        assertFalse(again!!.resync) // cooldown
        clock += 31_000
        assertTrue(g.offer(-48_000_000, 120, true)!!.resync)
    }

    @Test
    fun earlyFramesDoNotTriggerASeek() {
        val g = guard()
        clock += 31_000
        assertFalse(g.offer(36_000_000, 120, true)!!.resync) // 300ms before deadline
    }

    @Test
    fun smallOffsetsAndPausedPlaybackNeverResync() {
        val g = guard()
        clock += 31_000
        assertFalse(g.offer(-12_000_000, 120, true)!!.resync) // 100ms late on average
        assertFalse(g.offer(-48_000_000, 120, false)!!.resync)
    }

    @Test
    fun nonPositiveFrameCountIsIgnored() {
        assertNull(guard().offer(400_000, 0, true))
    }
}
