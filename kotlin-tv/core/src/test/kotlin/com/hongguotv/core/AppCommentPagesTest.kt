// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppCommentPagesTest {
    private fun cursor(offset: Int) =
        JSONObject().put("session_id", "anonymous-test").put("offset", offset).toString()

    private fun row(
        id: String = "1",
        series: String = "123456",
        episode: String = "234567",
        status: Int = 1,
    ) =
        JSONObject()
            .put(
                "comment",
                JSONObject()
                    .put("comment_id", id)
                    .put(
                        "common",
                        JSONObject()
                            .put("group_id", episode)
                            .put("status", status)
                            .put("create_timestamp", 1700000000)
                            .put("content", JSONObject().put("text", "普通评论"))
                            .put(
                                "user_info",
                                JSONObject()
                                    .put(
                                        "base_info",
                                        JSONObject()
                                            .put("user_name", "作者")
                                            .put("user_avatar", "javascript:bad"),
                                    ),
                            ),
                    )
                    .put("expand", JSONObject().put("book_id", series))
                    .put("stat", JSONObject().put("digg_count", 4)),
            )

    private fun page(
        rows: JSONArray = JSONArray().put(row()),
        next: Int = 20,
        more: Boolean = true,
    ) =
        JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONObject()
                    .put(
                        "common_list_info",
                        JSONObject()
                            .put("cursor", if (more) cursor(next) else "")
                            .put("has_more", more)
                            .put("total", 120),
                    )
                    .put("data_list", rows),
            )

    @Test
    fun preservesRealIdentityTotalsAndCursorWhileDeduplicating() {
        val result =
            AppCommentPages.parse(
                page(JSONArray().put(row()).put(row()).put(row("2", status = 0))),
                "123456",
                "234567",
                "",
            )
        assertEquals(1, result.getJSONArray("items").length())
        assertEquals(120, result.getInt("total"))
        assertEquals(cursor(20), result.getString("nextCursor"))
        assertTrue(result.getBoolean("hasMore"))
        val comment = result.getJSONArray("items").getJSONObject(0)
        assertEquals("1", comment.getString("id"))
        assertEquals("", comment.getString("avatar"))
        assertEquals(4, comment.getInt("likes"))
    }

    @Test
    fun rejectsWrongEpisodeBookAndNonAdvancingCursors() {
        for (value in
            listOf(
                page(JSONArray().put(row(episode = "345678"))),
                page(JSONArray().put(row(series = "345678"))),
            )) {
            try {
                AppCommentPages.parse(value, "123456", "234567", "")
                fail("Accepted mismatched data or cursor")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun rejectsNonAdvancingCursor() {
        try {
            AppCommentPages.parse(page(next = 20), "123456", "234567", cursor(20))
            fail("Repeated cursor accepted")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun permitsEmptyFinalPageWithoutContinuation() {
        val result =
            AppCommentPages.parse(page(JSONArray(), more = false), "123456", "234567", cursor(20))
        assertFalse(result.getBoolean("hasMore"))
        assertEquals("", result.getString("nextCursor"))
    }
}
