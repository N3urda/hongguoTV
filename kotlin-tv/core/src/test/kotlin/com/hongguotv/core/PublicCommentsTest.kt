// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicCommentsTest {
    private fun html(
        series: String = "123456",
        episode: String = "234567",
        rows: JSONArray = JSONArray(),
    ): String {
        val row =
            JSONObject()
                .put("seriesDetail", JSONObject().put("series_id", series))
                .put("vid", episode)
                .put(
                    "episodeSocialInfo",
                    JSONObject()
                        .put("reviews", JSONObject().put("total_count", 800).put("reviews", rows)),
                )
                .put(
                    "seriesSocialInfo",
                    JSONObject()
                        .put("rating", 9.1)
                        .put("reviews", JSONObject().put("total_count", 400).put("reviews", rows)),
                )
        return "<script>_ROUTER_DATA = " +
            JSONObject().put("loaderData", JSONObject().put("player", row)).toString() +
            ";</script>"
    }

    @Test
    fun normalizesDeduplicatesAndPreservesSourceTotals() {
        val item =
            JSONObject()
                .put("content", "评论")
                .put("user_name", "用户")
                .put("user_avatar", "javascript:alert(1)")
                .put("digg_count", -1)
                .put("create_time", 1700000000)
        val page =
            PublicComments.parse(
                html(rows = JSONArray().put(item).put(item).put(JSONObject().put("content", " "))),
                "123456",
                "234567",
            )
        val comments = page.getJSONObject("comments")
        assertEquals(800, comments.getInt("total"))
        assertEquals(1, comments.getJSONArray("items").length())
        val comment = comments.getJSONArray("items").getJSONObject(0)
        assertEquals("", comment.getString("avatar"))
        assertEquals(0, comment.getInt("likes"))
        assertTrue(page.getBoolean("limited"))
        assertEquals(64, comment.getString("id").length)
    }

    @Test
    fun rejectsWrongEpisodeOrSeries() {
        for (pair in listOf("other" to "234567", "123456" to "wrong")) {
            try {
                PublicComments.parse(html(pair.first, pair.second), "123456", "234567")
                fail("Accepted another episode")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun detailAndEpisodeReviewsStaySeparate() {
        val page =
            PublicComments.parse(
                html(),
                "123456",
                "234567",
                html(rows = JSONArray().put(JSONObject().put("content", "整剧剧评"))),
            )
        assertEquals(0, page.getJSONObject("comments").getJSONArray("items").length())
        assertEquals(1, page.getJSONObject("reviews").getJSONArray("items").length())
    }
}
