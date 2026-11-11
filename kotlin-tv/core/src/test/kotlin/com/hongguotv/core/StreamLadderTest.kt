package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class StreamLadderTest {
    private fun rendition(
        quality: Int,
        codec: String = "h264",
        width: Int? = null,
        height: Int? = null,
    ) = Rendition(quality, codec, width, height, "https://example.com/$quality-$codec", null)

    private fun ladder(vararg renditions: Rendition) = StreamLadder(renditions.toList())

    @Test
    fun highestTierWithinCapWins() {
        val pick = ladder(rendition(480), rendition(1080, "bytevc1"), rendition(720)).select(1080)!!
        assertEquals(1080, pick.quality)
        assertEquals("bytevc1", pick.codec)
    }

    @Test
    fun h264PreferredOverHevcAtEqualQuality() {
        val pick = ladder(rendition(720, "bytevc1"), rendition(720, "h264")).select(1080)!!
        assertEquals("h264", pick.codec)
    }

    @Test
    fun unsupportedTiersAreSkippedEvenWhenHigher() {
        val pick =
            ladder(
                    rendition(1080, "bytevc1", 1080, 1920),
                    rendition(720, "h264", 720, 1280),
                    rendition(480),
                )
                .select(1080) { it.codec == "h264" }!!
        assertEquals(720, pick.quality)
    }

    @Test
    fun allUnsupportedFallsBackToBestRendition() {
        val pick =
            ladder(rendition(1080, "bytevc1", 1080, 1920), rendition(720, "h264", 720, 1280))
                .select(1080) { false }!!
        assertEquals(1080, pick.quality)
    }

    @Test
    fun everythingAboveCapFallsBackToLowestRendition() {
        val pick = ladder(rendition(2160), rendition(1080)).select(720)!!
        assertEquals(1080, pick.quality)
    }

    @Test
    fun unknownQualityTierStaysEligible() {
        val pick = ladder(rendition(2160), rendition(0, "h264")).select(720)!!
        assertEquals(0, pick.quality)
    }

    @Test
    fun playbackOrderLeadsWithSelectionThenLowerTiersBestFirst() {
        val order =
            ladder(
                    rendition(1080, "bytevc1"),
                    rendition(720, "bytevc1"),
                    rendition(720, "h264"),
                    rendition(480),
                )
                .playbackOrder(1080) { it.codec == "h264" }
        // Unverified alternates stay eligible: capability metadata can be missing or wrong.
        assertEquals(
            listOf(720 to "h264", 720 to "bytevc1", 480 to "h264"),
            order.map { it.quality to it.codec },
        )
    }

    @Test
    fun playbackOrderKeepsSameQualityAlternatesRightAfterPick() {
        val order =
            ladder(rendition(720, "bytevc1"), rendition(720, "h264"), rendition(540, "bytevc1"))
                .playbackOrder(720) { it.codec == "h264" }
        assertEquals(listOf("h264", "bytevc1", "bytevc1"), order.map { it.codec })
    }

    @Test
    fun emptyLadderSelectsNothing() {
        assertNull(ladder().select(1080))
        assertTrue(ladder().playbackOrder(1080).isEmpty())
    }
}
