// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.json.JSONObject

/** 按服务端实际覆盖区间补齐缺口，不假定每次一定返回三十秒。 */
internal object TimedCommentWindows {
    fun next(pages: List<JSONObject>, position: Long, duration: Long): Long? {
        var cursor = ((position - 12000).coerceAtLeast(0) / 30000) * 30000
        val until = minOf(duration, position + 10000)
        val ranges = pages.map { it.getLong("startMS") to it.getLong("nextMS") }
        while (cursor < until) {
            val coveredUntil = ranges.filter { it.first <= cursor && it.second > cursor }
                .maxOfOrNull { it.second } ?: return cursor
            cursor = coveredUntil
        }
        return null
    }
}
