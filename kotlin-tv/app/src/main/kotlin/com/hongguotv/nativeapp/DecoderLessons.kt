// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * Learned decoder failures: capabilities the device claims but cannot actually decode. Keys are
 * codec plus coded size, so a failed portrait 1080x1920 never blocks landscape 1920x1080.
 */
internal class DecoderLessons {
    private val failed = HashSet<String>()

    fun key(codec: String, width: Int?, height: Int?): String? {
        val normalized = codec.trim().lowercase()
        if (normalized.isEmpty()) return null
        if (width == null || height == null || width <= 0 || height <= 0) return normalized
        return "$normalized|$width|$height"
    }

    fun remember(codec: String, width: Int?, height: Int?): String? {
        val exact = key(codec, width, height) ?: return null
        failed += exact
        return exact
    }

    fun fails(codec: String, width: Int?, height: Int?): Boolean {
        val normalized = codec.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (width != null && height != null && width > 0 && height > 0) {
            if (failed.contains("$normalized|$width|$height")) return true
        }
        // A remembered sizeless verdict (metadata was missing) covers that codec entirely.
        return failed.contains(normalized)
    }

    fun keys(): Set<String> = failed.toSet()

    fun restore(keys: Set<String>) {
        failed.clear()
        failed += keys
    }

    fun reset() = failed.clear()

    fun size() = failed.size
}
