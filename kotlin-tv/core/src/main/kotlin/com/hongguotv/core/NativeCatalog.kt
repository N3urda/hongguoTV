// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

internal object NativeCatalog {
    fun discoveryRequest(filters: Map<String, String>, cursor: DiscoveryCursor?): JSONObject? =
        call(JSONObject().put("operation", "discovery_request").put("filters", JSONObject(filters))
            .put("cursor", cursorValue(cursor)))

    fun discoveryPage(response: String, cursor: DiscoveryCursor?): JSONObject? =
        call(JSONObject().put("operation", "discovery_page").put("cursor", cursorValue(cursor)), response)

    fun danmakuRequest(series: String, episode: String, start: Long, duration: Long, cursor: String): JSONObject? =
        call(danmakuArguments("danmaku_request", series, episode, start, duration).put("cursor", cursor))

    fun danmakuPage(response: JSONObject, series: String, episode: String, start: Long, duration: Long): JSONObject? {
        if (!RustPlaybackCore.available) return null
        return call(danmakuArguments("danmaku_page", series, episode, start, duration), response.toString())
    }

    private fun danmakuArguments(operation: String, series: String, episode: String, start: Long, duration: Long): JSONObject =
        JSONObject().put("operation", operation).put("series", series).put("episode", episode)
            .put("start", start).put("duration", duration)

    private fun cursorValue(cursor: DiscoveryCursor?): Any =
        cursor?.let { JSONObject().put("offset", it.offset).put("page", it.page).put("session", it.session) }
            ?: JSONObject.NULL

    private fun call(arguments: JSONObject, response: String = ""): JSONObject? {
        val encoded = RustPlaybackCore.catalog(arguments.toString(), response) ?: return null
        val result = JSONObject(encoded)
        require(!result.has("error")) { result.getString("error") }
        return result.getJSONObject("value")
    }
}
