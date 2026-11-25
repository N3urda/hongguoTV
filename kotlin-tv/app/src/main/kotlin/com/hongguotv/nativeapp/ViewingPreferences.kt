// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

internal enum class VideoDecodeMode(val label: String) {
    HARDWARE_PREFERRED("硬解优先（不支持时软解）"),
    SOFTWARE_ONLY("兼容（软解）"),
}

/** Local viewing preferences; unknown persisted values fall back to safe defaults. */
internal data class ViewingPreferences(
    val startupPage: Int = 0,
    val homeAutoplay: Boolean = true,
    val clockMode: Int = 1,
    val seasonAutoplay: Boolean = false,
    val videoDecodeMode: VideoDecodeMode = VideoDecodeMode.HARDWARE_PREFERRED,
    val kidsMode: Boolean = false,
) {
    fun initialTab(): Int = startupPage
    companion object {
        fun decode(values: Map<String, *>): ViewingPreferences {
            fun number(key: String, range: IntRange, fallback: Int) =
                (values[key] as? Int)?.takeIf { it in range } ?: fallback
            return ViewingPreferences(
                number("startupTab", 0..8, if (values["startupPage"] == 1) 4 else 0),
                values["homeAutoplay"] as? Boolean ?: true, number("clockMode", 0..2, 1),
                values["seasonAutoplay"] as? Boolean ?: false,
                VideoDecodeMode.entries.firstOrNull { it.name == values["videoDecodeMode"] }
                    ?: VideoDecodeMode.HARDWARE_PREFERRED,
                values["kidsMode"] as? Boolean ?: false,
            )
        }
    }
}
