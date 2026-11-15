// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

fun Series.toJson(): JSONObject = JSONObject()
    .put("id", id).put("title", title).put("cover", cover)
    .put("description", description).put("badge", badge).put("tags", tags)

fun Series.Companion.fromJson(o: JSONObject): Series = Series(
    o.str("id"), o.str("title"), o.str("cover"),
    o.str("description"), o.str("badge"), o.str("tags"),
)
