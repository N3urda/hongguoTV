// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** Anonymous, read-only timed comments. No account session or write endpoint. */
class TimedComments(
    http: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build(),
    playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared,
) {
    private val transport = CommentTransport(playbackSession, http)
    private val cache = linkedMapOf<String, Pair<Long, String>>()

    /** 用户主动重读时清除短期缓存，保证确实重新请求服务端。 */
    fun clearCache() = synchronized(cache) { cache.clear() }

    fun read(series: String, episode: String, start: Long, duration: Long, cursor: String = ""): JSONObject {
        require(series.matches(Regex("[0-9]{6,30}")) && episode.matches(Regex("[0-9]{6,30}")))
        require(start >= 0 && duration > start && duration <= 86400000)
        require(cursor.toByteArray(Charsets.UTF_8).size <= 16384)
        val key = "$series/$episode/$start/$duration/$cursor"
        synchronized(cache) {
            cache[key]
                ?.takeIf { System.currentTimeMillis() - it.first < 60000 }
                ?.let {
                    return JSONObject(it.second)
                }
        }
        val payload =
            NativeCatalog.danmakuRequest(series, episode, start, duration, cursor) ?: JSONObject()
                .put("comment_source", 601)
                .put("server_channel", 1000)
                .put("group_id", episode)
                .put("group_type", 30)
                .put("comment_type", 20)
                .put("sort", 1)
                .put("count", 90)
                .put("cursor", cursor)
                .put("aid", 8662)
                .put("compliance_status", 0)
                .put(
                    "business_param",
                    JSONObject()
                        .put("book_id", series)
                        .put("start_offset_time", start)
                        .put("playlet_item_duration", duration)
                        .put("need_danmaku_guide_type", JSONArray(listOf(1, 3, 4, 2))),
                )
        val result =
            parse(transport.read(episode, payload, 601, 1000), series, episode, start, duration)
        synchronized(cache) {
            cache[key] = System.currentTimeMillis() to result.toString()
            while (cache.size > 64) cache.remove(cache.keys.first())
        }
        return result
    }

    companion object {
        fun continuation(page: JSONObject, series: String, episode: String, start: Long, duration: Long): String? =
            if (
                page.optBoolean("hasMore") &&
                    page.optString("seriesID") == series &&
                    page.optString("episodeID") == episode &&
                    page.optLong("nextMS") == start &&
                    page.optLong("durationMS") == duration
            ) page.optString("nextCursor") else null

        fun parse(
            result: JSONObject,
            series: String,
            episode: String,
            start: Long,
            duration: Long,
        ): JSONObject {
            NativeCatalog.danmakuPage(result, series, episode, start, duration)?.let { return it }
            require(result.opt("code")?.toString()?.toLongOrNull() == 0L) { "弹幕接口未返回成功数据" }
            val data = result.getJSONObject("data")
            val continuation = data.optJSONObject("common_list_info")
            val more = continuation?.opt("has_more")?.toString()?.toBooleanStrictOrNull()
            val nextValue = data.optJSONObject("extra")?.opt("next_query_danmaku_list_time")
            val next = nextValue?.toString()?.toLongOrNull()
            val end = when {
                next != null && next > start && next <= 86400000 -> minOf(next, duration)
                more == false && (nextValue == null || nextValue == JSONObject.NULL || next == 0L) -> duration
                else -> throw IllegalArgumentException("弹幕时间段无效")
            }
            val canContinue = more == true && end < duration
            val nextCursor = if (canContinue) continuation?.opt("cursor") as? String ?: "" else ""
            require(nextCursor.toByteArray(Charsets.UTF_8).size <= 16384) { "弹幕续页格式无效" }
            val items = mutableListOf<JSONObject>()
            val seen = mutableSetOf<String>()
            val rows = data.getJSONArray("data_list")
            for (i in 0 until minOf(rows.length(), 300)) {
                val comment = rows.optJSONObject(i)?.optJSONObject("comment") ?: continue
                val common = comment.optJSONObject("common") ?: continue
                val expand = comment.optJSONObject("expand") ?: continue
                if (common.optString("group_id") != episode || common.optInt("status", -1) != 1)
                    continue
                val time = expand.optLong("offset_time", -1)
                if (time < start || time >= end) continue
                val id = comment.optString("comment_id").take(120)
                val text =
                    common
                        .optJSONObject("content")
                        ?.optString("text")
                        .orEmpty()
                        .replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ")
                        .trim()
                        .take(180)
                if (id.isBlank() || text.isBlank() || !seen.add(id)) continue
                items.add(JSONObject().put("id", id).put("text", text).put("timeMS", time))
            }
            return JSONObject()
                .put("rawCount", rows.length())
                .put("nextCursor", nextCursor)
                .put("hasMore", canContinue)
                .put("seriesID", series)
                .put("episodeID", episode)
                .put("startMS", start)
                .put("nextMS", end)
                .put("durationMS", duration)
                .put("items", JSONArray(items.sortedBy { it.getLong("timeMS") }.take(90)))
        }
    }
}
