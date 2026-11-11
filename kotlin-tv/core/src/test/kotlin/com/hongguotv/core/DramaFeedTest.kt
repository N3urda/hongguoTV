package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class DramaFeedTest {
    @Test
    fun restartingOnlyCurrentSourcePreservesOtherSourceAndNormalRefreshProgress() {
        val state = DramaFeedState()
        state.recommendationCursor.restore("""{"session":"recommend-session","offset":20,"seen":["101"]}""")
        state.append(CatalogPage(listOf(Series("101", "推荐")), true), 1)
        state.select(DramaCategory.COMIC)
        val comic = state.recommendationCursor
        comic.restore("""{"session":"comic-session","offset":10,"seen":["202"]}""")
        state.append(CatalogPage(listOf(Series("202", "漫剧")), true), 1)
        state.recommend()
        state.refresh(restartCurrentSource = true)
        assertFalse(state.recommendationCursor.hasProgress)
        assertEquals(listOf("101"), state.recommendationCursor.seenIds().toList())
        state.select(DramaCategory.COMIC)
        assertSame(comic, state.recommendationCursor)
        assertEquals("202", state.current!!.id)
        state.refresh()
        assertTrue(state.recommendationCursor.hasProgress)
        assertEquals("comic-session", org.json.JSONObject(state.recommendationCursor.snapshot()).getString("session"))
    }

    @Test
    fun returningToSourceRestoresCardsSelectionAndPagingButAccountResetClearsAllSources() {
        val state = DramaFeedState()
        state.append(page(100), 1)
        state.move(2)
        val cursor = state.recommendationCursor
        state.select(DramaCategory.COMIC)
        state.append(page(200), 1)
        state.move(3)
        state.recommend()
        assertEquals("102", state.current?.id)
        assertEquals(1, state.page)
        assertSame(cursor, state.recommendationCursor)
        state.select(DramaCategory.COMIC)
        assertEquals("203", state.current?.id)
        state.refresh()
        assertNull(state.current)
        state.recommend()
        assertEquals("102", state.current?.id)
        state.refresh(resetRecommendations = true)
        state.select(DramaCategory.COMIC)
        assertNull(state.current)
        assertEquals(0, state.page)
    }

    private fun page(start: Int) =
        CatalogPage((start until start + 24).map { Series("$it", "作品$it") }, true)

    @Test
    fun switchingWorksResetsOnlyTrialPositionAndTypeClearsQueue() {
        val state = DramaFeedState()
        state.append(page(0), 1)
        state.position = 900
        state.userPaused = true
        state.ended = true
        assertTrue(state.move(1))
        assertEquals("1", state.current?.id)
        assertEquals(0L, state.position)
        assertFalse(state.userPaused)
        assertFalse(state.ended)
        state.select(DramaCategory.REAL)
        assertNull(state.current)
        assertEquals(0, state.page)
        assertTrue(state.hasMore)
    }

    @Test
    fun failedNavigationPreservesPlayback() {
        val state = DramaFeedState()
        state.append(page(0), 1)
        state.position = 777
        assertFalse(state.move(-1))
        assertEquals(777L, state.position)
    }

    @Test
    fun repeatedServerPageStopsPagination() {
        val state = DramaFeedState()
        state.append(page(0), 1)
        state.append(page(0), 2)
        assertFalse(state.hasMore)
        assertEquals(24, state.items.size)
    }

    @Test
    fun longBrowsingRetainsCurrentWorkAndBoundsQueue() {
        val state = DramaFeedState()
        state.append(page(0), 1)
        for (page in 2..20) {
            while (state.next != null) state.move(1)
            val id = state.current!!.id
            state.append(page((page - 1) * 24), page)
            assertEquals(id, state.current?.id)
            assertTrue(state.items.size <= 72)
        }
        assertTrue(state.offset > 0)
    }
}
