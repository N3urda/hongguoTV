// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Local viewing preferences; unknown persisted values fall back to safe defaults. */
internal data class ViewingPreferences(
    val startupPage: Int = 0,
    val homeAutoplay: Boolean = true,
    val clockMode: Int = 1,
    val seasonAutoplay: Boolean = false,
) {
    fun initialTab(): Int = startupPage
    companion object {
        fun decode(values: Map<String, *>): ViewingPreferences {
            fun number(key: String, range: IntRange, fallback: Int) =
                (values[key] as? Int)?.takeIf { it in range } ?: fallback
            return ViewingPreferences(
                number("startupTab", 0..7, if (values["startupPage"] == 1) 4 else 0),
                values["homeAutoplay"] as? Boolean ?: true, number("clockMode", 0..2, 1),
                values["seasonAutoplay"] as? Boolean ?: false,
            )
        }
    }
}
