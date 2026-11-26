package com.hongguotv.nativeapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareVideoDropGuardTest {
    @Test fun reportsRepeatedLargeBatchesAtTheRateObservedOnLowMemoryTv() {
        val guard = SoftwareVideoDropGuard()

        assertFalse(guard.offer(droppedFrames = 50, elapsedMs = 2_000, timestampMs = 4_000))
        assertTrue(guard.offer(droppedFrames = 50, elapsedMs = 7_000, timestampMs = 11_000))
    }

    @Test fun ignoresSmallBatchesAndDiscardsHistoryAcrossGapsOrSeeks() {
        val guard = SoftwareVideoDropGuard()

        assertFalse(guard.offer(50, 2_000, 2_000))
        assertFalse(guard.offer(50, 2_000, 16_000))
        assertFalse(guard.offer(12, 2_000, 18_000))
        assertFalse(guard.offer(50, 2_000, 20_000))
        guard.reset()
        assertFalse(guard.offer(50, 2_000, 22_000))
        assertFalse(guard.offer(50, 11_000, 33_000))
    }
}
