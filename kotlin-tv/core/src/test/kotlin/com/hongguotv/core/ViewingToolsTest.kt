package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class ViewingToolsTest {
    @Test fun `zero rotation retains surface view and only surface type transitions rebuild`() {
        assertFalse(VideoRotation.usesTexture(0)); assertFalse(VideoRotation.usesTexture(45))
        for(rotation in listOf(90,180,270)) {
            assertTrue(VideoRotation.usesTexture(rotation))
            assertTrue(VideoRotation.requiresSurfaceRebuild(0,rotation))
            assertTrue(VideoRotation.requiresSurfaceRebuild(rotation,0))
            assertFalse(VideoRotation.requiresSurfaceRebuild(rotation,rotation))
        }
        assertFalse(VideoRotation.requiresSurfaceRebuild(0,0))
        assertFalse(VideoRotation.requiresSurfaceRebuild(90,270))
        assertFalse(VideoRotation.requiresSurfaceRebuild(270,180))
        // Once rebuilt for the new preference, applying that preference again is a no-op.
        assertFalse(VideoRotation.requiresSurfaceRebuild(90,90))
    }
    @Test fun `quarter turns swap video canvas dimensions without changing screen coordinates`() {
        for(degrees in listOf(0,180)) assertEquals(VideoRotation.Canvas(1920,1080),VideoRotation.canvas(1920,1080,degrees))
        for(degrees in listOf(90,270)) assertEquals(VideoRotation.Canvas(1080,1920),VideoRotation.canvas(1920,1080,degrees))
        assertEquals(VideoRotation.Canvas(1920,1080),VideoRotation.canvas(1080,1920,90))
        assertEquals(VideoRotation.Canvas(0,0),VideoRotation.canvas(-1,-3,270))
        assertEquals(0,VideoRotation.normalize(45)); assertEquals(0,VideoRotation.normalize(-90))
    }
    @Test fun `persistent progress is separate from transient controls and off by default`() {
        assertFalse(BackupSettings().alwaysShowProgress)
        assertTrue(PlaybackProgressDisplay.visible(true,true,false,false))
        assertFalse(PlaybackProgressDisplay.visible(false,true,false,false))
        assertFalse(PlaybackProgressDisplay.visible(true,false,false,false))
        assertFalse(PlaybackProgressDisplay.visible(true,true,true,false))
        assertFalse(PlaybackProgressDisplay.visible(true,true,false,true))
        assertEquals(500,PlaybackProgressDisplay.value(500,1000))
        assertEquals(0,PlaybackProgressDisplay.value(-1,1000))
        assertEquals(1000,PlaybackProgressDisplay.value(2000,1000))
        assertEquals(0,PlaybackProgressDisplay.value(100,-1))
        assertEquals(1000,PlaybackProgressDisplay.value(Long.MAX_VALUE,Long.MAX_VALUE))
    }
    @Test fun `display settings load with backward compatible defaults and normalization`() {
        assertEquals(0,LibraryDisk.decode(emptyMap<String, Any>()).settings.rotation)
        assertFalse(LibraryDisk.decode(emptyMap<String, Any>()).settings.alwaysShowProgress)
        val loaded=LibraryDisk.decode(mapOf("videoRotation" to 270,"alwaysShowProgress" to true))
        assertEquals(270,loaded.settings.rotation); assertTrue(loaded.settings.alwaysShowProgress)
        assertEquals(0,LibraryDisk.decode(mapOf("videoRotation" to 45)).settings.rotation)
    }
    @Test
    fun `jump validates the exact range without clamping`() {
        assertEquals(0, EpisodeTarget.parse("1", 536))
        assertEquals(535, EpisodeTarget.parse("536", 536))
        for (value in listOf("0", "537", "-1", "1.5", "1e2", "999999999999999", "")) assertNull(
            value,
            EpisodeTarget.parse(value, 536),
        )
        assertNull(EpisodeTarget.parse("1", 0))
        assertEquals(103, EpisodeTarget.parse(" 0104 ", 536))
    }

    @Test
    fun `search history is bounded deduplicated and keeps content types distinct`() {
        var rows = emptyList<RecentSearch>()
        repeat(30) { rows = SearchHistory.remember(rows, "词$it", ContentType.COMIC) }
        assertEquals(20, rows.size)
        assertEquals("词29", rows.first().query)
        rows = SearchHistory.remember(rows, " 词15 ", ContentType.COMIC)
        assertEquals("词15", rows.first().query)
        assertEquals(1, rows.count { it.query == "词15" })
        rows = SearchHistory.remember(rows, "词15", ContentType.SHORT)
        assertEquals(2, rows.count { it.query == "词15" })
        assertEquals(ContentType.SHORT, rows.first().type)
        assertEquals(rows, SearchHistory.decode(SearchHistory.encode(rows)))
        assertEquals(rows, SearchHistory.remember(rows, " ", ContentType.COMIC))
        assertEquals(rows, SearchHistory.remember(rows, "x".repeat(81), ContentType.COMIC))
    }

    @Test
    fun `corrupt search history is recoverable and invalid entries are discarded`() {
        assertTrue(SearchHistory.decode("broken json").isEmpty())
        val rows =
            SearchHistory.decode(
                """[null,{"query":"","type":"comic"},{"query":"a","type":"unknown"},{"query":"a","type":"comic"},{"query":"a","type":"comic"}]"""
            )
        assertEquals(listOf(RecentSearch("a", ContentType.COMIC)), rows)
    }
}
