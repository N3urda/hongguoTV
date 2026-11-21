// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Android packages the library. Plain JVM consumers can still run the common rules. */
object RustPlaybackCore {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("hongguo_playback")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    internal fun resume(count: Int, matched: Int, position: Long, completed: Boolean): ResumeTarget? {
        if (!available) return null
        val target = nativeResume(count, matched, position, completed) ?: return null
        return ResumeTarget(target[0].toInt(), target[1])
    }

    internal fun select(rows: List<Rendition>, supported: BooleanArray, maximum: Int): Int? {
        if (!available || rows.isEmpty()) return null
        val packed = IntArray(rows.size * 2)
        rows.forEachIndexed { index, row ->
            packed[index * 2] = row.quality
            packed[index * 2 + 1] = (if (row.isAvc) 1 else 0) or (if (supported[index]) 2 else 0)
        }
        return nativeSelect(packed, maximum).takeIf { it in rows.indices }
    }

    /** A nonnegative result is the total length. Negative codes describe an invalid response. */
    fun rangeTotal(header: String, start: Long, end: Long, expected: Long): Long? =
        if (available) nativeRangeTotal(header, start, end, expected) else null

    private external fun nativeResume(count: Int, matched: Int, position: Long, completed: Boolean): LongArray?
    private external fun nativeSelect(packed: IntArray, maximum: Int): Int
    private external fun nativeRangeTotal(header: String, start: Long, end: Long, expected: Long): Long
}

internal actual fun nativeResumeTarget(count: Int, matched: Int, position: Long, completed: Boolean) =
    RustPlaybackCore.resume(count, matched, position, completed)

internal actual fun nativeRenditionIndex(rows: List<Rendition>, supported: BooleanArray, maximum: Int) =
    RustPlaybackCore.select(rows, supported, maximum)
