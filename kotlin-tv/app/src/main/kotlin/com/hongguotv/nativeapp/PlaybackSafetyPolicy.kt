// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks

/** Keep 1 GB, 32-bit devices away from the high-resolution allocation path before codec creation. */
internal class PlaybackSafetyPolicy(
    private val is32Bit: Boolean = false,
    lowRam: Boolean = false,
    totalMemoryBytes: Long = 0,
    private val automaticQuality: () -> Boolean = { true },
) {
    val constrained = is32Bit && (lowRam || totalMemoryBytes in 1..(1536L * 1024 * 1024))

    fun maxQuality(requested: Int, softwareOnly: Boolean = false): Int =
        if (automaticQuality() && (constrained || is32Bit && softwareOnly)) minOf(requested, 720) else requested

    private fun acceptsSize(width: Int?, height: Int?): Boolean =
        width != null && height != null && width > 0 && height > 0 &&
            minOf(width, height) <= 720 && maxOf(width, height) <= 1280

    fun accepts(rendition: Rendition): Boolean = !automaticQuality() || !constrained ||
        (rendition.quality <= 720 &&
            // Missing metadata may be resolved from the MP4 before the player sees the source.
            (rendition.width == null || rendition.height == null ||
                acceptsSize(rendition.width, rendition.height)))

    fun accepts(track: VideoTracks.Track?, softwareOnly: Boolean = false): Boolean =
        !automaticQuality() || !(constrained || is32Bit && softwareOnly) || track != null && acceptsSize(track.width, track.height)

    companion object {
        fun from(context: Context, automaticQuality: () -> Boolean = { true }): PlaybackSafetyPolicy {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(memory)
            return PlaybackSafetyPolicy(
                Build.SUPPORTED_64_BIT_ABIS.isEmpty(), manager?.isLowRamDevice == true, memory.totalMem,
                automaticQuality,
            )
        }
    }
}
