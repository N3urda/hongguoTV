// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * A runtime failure is scoped to an episode and decoder mode, never a device-wide codec ban.
 * Different streams at the same size can have different profiles, levels or damaged bitstreams.
 */
internal class DecoderLessons {
    private val failed = HashSet<String>()

    fun key(codec: String, width: Int?, height: Int?, scope: String?): String? {
        val normalized = when (val name = codec.trim().lowercase()) {
            "hvc1", "hev1", "bytevc1", "h265", "hevc" -> "hevc"
            "avc1", "avc3", "h264", "avc" -> "avc"
            else -> name
        }
        if (scope.isNullOrBlank() || normalized.isEmpty() ||
            width == null || height == null || width <= 0 || height <= 0) return null
        return "$scope|$normalized|$width|$height"
    }

    @Synchronized
    fun remember(codec: String, width: Int?, height: Int?, scope: String?): String? {
        val exact = key(codec, width, height, scope) ?: return null
        if (failed.size >= 128) failed.clear()
        failed += exact
        return exact
    }

    @Synchronized
    fun fails(codec: String, width: Int?, height: Int?, scope: String?): Boolean =
        key(codec, width, height, scope)?.let(failed::contains) == true

    @Synchronized fun keys(): Set<String> = failed.toSet()

    @Synchronized fun restore(keys: Set<String>) {
        failed.clear()
        failed += keys.filter { it.count { c -> c == '|' } == 3 }.take(128)
    }

    @Synchronized fun reset() = failed.clear()

    @Synchronized fun size() = failed.size
}
