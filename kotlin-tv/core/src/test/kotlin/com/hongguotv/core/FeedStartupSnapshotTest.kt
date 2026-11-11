// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FeedStartupSnapshotTest {
    private val page =
        CatalogPage(
            listOf(Series("101", "首屏作品")),
            false,
            trialHints = mapOf("101" to TrialHint("10101", 80)),
        )

    @Test
    fun shortLivedSnapshotKeepsVerifiedFirstEpisodeButExpiresAndRejectsCorruption() {
        val value = FeedStartupSnapshot.encode(page, 1000)
        val decoded = FeedStartupSnapshot.decode(value, 1100)!!
        assertEquals(page.items, decoded.items)
        assertEquals(page.trialHints, decoded.trialHints)
        assertTrue(decoded.hasMore)
        assertNull(FeedStartupSnapshot.decode(value, 999))
        assertNull(FeedStartupSnapshot.decode(value, 601001))
        assertNull(FeedStartupSnapshot.decode("bad", 1100))
        val corrupted = JSONObject(value)
        corrupted
            .getJSONObject("trialHints")
            .getJSONObject("101")
            .put("episodeId", "https://invalid.test/")
        assertNull(FeedStartupSnapshot.decode(corrupted.toString(), 1100))
        assertFalse(value.contains("session_id"))
        assertFalse(value.contains("cookie"))
    }

    @Test
    fun cachedStarterContinuesFromFreshServerSessionAndSkipsAlreadyDisplayedCards() {
        val requests = mutableListOf<Request>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    val id = if (requests.size == 1) "101" else "102"
                    val json =
                        """{"code":0,"data":{"tab_item":[{"tab_type":16,"session_id":"new-test-session","cell_data":[{"cell_data":[{"video_data":[{"series_id":"$id","series_title":"作品","episode_cnt":80}]}]}]}]}}"""
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody())
                        .build()
                }
                .build()
        val cursor = RecommendationCursor()
        val starter = FeedStartupSnapshot.decode(FeedStartupSnapshot.encode(page, 1000), 1100)!!
        assertTrue(cursor.restoreStarter(starter))
        val feed = RecommendationFeed(http, cursor = cursor)
        assertSame(starter, feed.page(1))
        assertTrue(requests.isEmpty())
        assertEquals("102", feed.page(2).items.single().id)
        assertEquals(listOf("0", "1"), requests.map { it.url.queryParameter("offset") })
        assertNull(requests[0].url.queryParameter("session_id"))
        assertEquals("new-test-session", requests[1].url.queryParameter("session_id"))
        assertFalse(cursor.restoreStarter(starter))
        assertFalse(cursor.nextBatch().restoreStarter(starter))
        assertSame(feed.page(2), feed.page(2))
        assertEquals(2, requests.size)
    }
}
