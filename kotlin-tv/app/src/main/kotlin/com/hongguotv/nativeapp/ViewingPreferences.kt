// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

internal enum class VideoDecoderMode(val label: String) {
    AUTOMATIC("硬解优先（自动回退）"),
    COMPATIBLE_HARDWARE("尝试兼容硬解（HEVC）"),
    SOFTWARE_ONLY("强制软件解码"),
}

/** Local viewing preferences; unknown persisted values fall back to safe defaults. */
internal data class ViewingPreferences(
    val startupPage: Int = 0,
    val homeAutoplay: Boolean = true,
    val clockMode: Int = 1,
    val seasonAutoplay: Boolean = false,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTOMATIC,
    val autoQualityDowngrade: Boolean = true,
) {
    fun initialTab(): Int = startupPage
    companion object {
        fun decode(values: Map<String, *>): ViewingPreferences {
            fun number(key: String, range: IntRange, fallback: Int) =
                (values[key] as? Int)?.takeIf { it in range } ?: fallback
            val oldDecoderMode = if (values["forceSoftwareDecoding"] == true) VideoDecoderMode.SOFTWARE_ONLY
                else VideoDecoderMode.AUTOMATIC
            val decoderMode = VideoDecoderMode.entries.firstOrNull { it.name == values["videoDecoderMode"] }
            return ViewingPreferences(
                number("startupTab", 0..8, if (values["startupPage"] == 1) 4 else 0),
                values["homeAutoplay"] as? Boolean ?: true, number("clockMode", 0..2, 1),
                values["seasonAutoplay"] as? Boolean ?: false,
                decoderMode ?: oldDecoderMode,
                values["autoQualityDowngrade"] as? Boolean ?: true,
            )
        }
    }
}
