// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

data class RecentSearch(val query: String, val type: ContentType)

object SearchHistory {
    fun remember(
        existing: List<RecentSearch>,
        query: String,
        type: ContentType,
    ): List<RecentSearch> {
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > 80) return existing
        val entry = RecentSearch(clean, type)
        return (listOf(entry) + existing.filterNot { it == entry }).take(20)
    }

    fun decode(value: String): List<RecentSearch> =
        runCatching {
                val rows = JSONArray(value)
                (0 until rows.length())
                    .mapNotNull { i ->
                        val row = rows.optJSONObject(i) ?: return@mapNotNull null
                        val query = row.optString("query").trim()
                        val type =
                            ContentType.entries.firstOrNull {
                                it.storedValue == row.optString("type")
                            }
                        if (query.isEmpty() || query.length > 80 || type == null) null
                        else RecentSearch(query, type)
                    }
                    .distinct()
                    .take(20)
            }
            .getOrDefault(emptyList())

    fun encode(rows: List<RecentSearch>) =
        JSONArray(rows.map { JSONObject().put("query", it.query).put("type", it.type.storedValue) })
            .toString()
}
