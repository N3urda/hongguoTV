package com.hongguotv.nativeapp

import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks
import org.junit.Assert.*
import org.junit.Test

class PlaybackQualityRecoveryTest {
    private val tiers = listOf(1080, 720, 540, 480, 360).map {
        Rendition(it, "h265", null, null, "https://example.com/$it", null)
    }

    @Test fun persistentOverloadCanStepDownAtMostThreeTimesAndNeverChangesAnotherEpisode() {
        val recovery = PlaybackQualityRecovery()
        for ((from, to) in listOf(1080 to 720, 720 to 480, 480 to 360)) {
            assertTrue(recovery.reduce("101", from, null, tiers) { true })
            assertEquals(to, recovery.maximum("101", 1080))
            assertEquals(1080, recovery.maximum("102", 1080))
        }
        assertFalse(recovery.reduce("101", 360, null, tiers) { true })
        assertFalse("Stale metadata cannot raise the cap or restart the budget",
            recovery.reduce("101", 1080, null, tiers) { true })
        recovery.clear()
        assertEquals(1080, recovery.maximum("101", 1080))
    }

    @Test fun labelsWithTheSameActualSizeAreSkipped() {
        val recovery = PlaybackQualityRecovery()
        val actual = VideoTracks.Track("hvc1", 480, 854, "hvc1.1.6.L186")
        assertTrue(recovery.reduce("101", 720, actual, tiers) { true })
        assertEquals(360, recovery.maximum("101", 1080))
    }

    @Test fun noUsableSmallerRenditionDoesNotConsumeARecoveryOrAlterPreferences() {
        val recovery = PlaybackQualityRecovery()
        assertFalse(recovery.reduce("101", 720, null, tiers) { false })
        assertFalse(recovery.reduce("101", 720, null, tiers.take(2)) { true })
        val mislabeled = tiers.last().copy(width = 1080, height = 1920)
        assertFalse(recovery.reduce("101", 720, null, listOf(mislabeled)) { true })
        assertFalse(recovery.limited("101"))
        assertEquals(480, recovery.maximum("101", 480))
    }

    @Test fun reopenedHeadersMustRespectTheRecoveryLimitEvenWhenMetadataWasMissingOrWrong() {
        val recovery = PlaybackQualityRecovery()
        val original = VideoTracks.Track("hvc1", 480, 854, "hvc1.1.6.L186")
        assertTrue(recovery.accepts("101", null))
        assertTrue(recovery.reduce("101", 720, original, tiers) { true })
        assertFalse(recovery.accepts("101", original))
        assertFalse(recovery.accepts("101", null))
        assertFalse(recovery.accepts("101", original.copy(width = 0)))
        assertTrue(recovery.accepts("101", original.copy(width = 360, height = 642)))
        assertTrue(recovery.accepts("102", original))
        recovery.clear()
        assertTrue(recovery.accepts("101", original))
    }
    @Test fun disablingAutomaticQualityIgnoresPriorLimitsAndStopsFurtherReductions() {
        var enabled = true
        val recovery = PlaybackQualityRecovery { enabled }
        assertTrue(recovery.reduce("101", 1080, null, tiers) { true })
        enabled = false
        assertEquals(1080, recovery.maximum("101", 1080))
        assertFalse(recovery.limited("101"))
        assertTrue(recovery.accepts("101", VideoTracks.Track("hvc1", 1080, 1920)))
        assertFalse(recovery.reduce("101", 1080, null, tiers) { true })
        recovery.clear()
        enabled = true
        assertEquals(1080, recovery.maximum("101", 1080))
        assertTrue(recovery.reduce("101", 1080, null, tiers) { true })
    }

}
