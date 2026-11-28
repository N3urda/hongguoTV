package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackQualityLimitTest {
    private fun tier(quality: Int, codec: String = "h265") =
        Rendition(quality, codec, quality, quality * 16 / 9, "https://example.org/$quality/$codec", null)

    @Test fun requestedLimitNeverFallsBackToAHigherRendition() {
        assertTrue(StreamLadder(listOf(tier(1080))).playbackOrder(720).isEmpty())
    }

    @Test fun unsupportedRenditionsCannotReenterThroughTheFallbackList() {
        val ladder = StreamLadder(listOf(tier(1080), tier(720), tier(480, "h264")))
        assertEquals(listOf(480), ladder.playbackOrder(1080) { it.codec == "h264" }.map { it.quality })
        assertTrue(ladder.playbackOrder(1080) { false }.isEmpty())
    }
}
