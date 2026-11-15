// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/** JVM persistence adapter; the business model lives in shared-core. */
fun FavoriteUpdate.json(): JSONObject =
    JSONObject().put("total", total).put("seen", seen).put("checkedAt", checkedAt)

fun FavoriteUpdate.Companion.decode(value: JSONObject?): FavoriteUpdate? =
    runCatching {
        require(value != null)
        val total = value.getInt("total")
        val seen = value.getInt("seen")
        val time = value.getLong("checkedAt")
        require(total > 0 && seen > 0 && time >= 0)
        FavoriteUpdate(total, seen, time)
    }.getOrNull()
