// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.Rendition
import com.hongguotv.core.VideoTracks

/** Temporary per-episode limits, shared by the feed and full player. User preferences stay intact. */
internal class PlaybackQualityRecovery(private val enabled: () -> Boolean = { true }) {
    private val limits = linkedMapOf<String, Int>()

    @Synchronized fun maximum(id: String?, requested: Int): Int =
        if (enabled()) minOf(requested, limits[id] ?: requested) else requested
    @Synchronized fun limited(id: String?): Boolean = enabled() && id in limits
    @Synchronized fun clear() { limits.clear() }

    @Synchronized fun accepts(id: String, track: VideoTracks.Track?): Boolean {
        if (!enabled()) return true
        val limit = limits[id] ?: return true
        return track != null && track.width > 0 && track.height > 0 &&
            minOf(track.width, track.height) <= limit
    }

    @Synchronized fun reduce(
        id: String,
        currentQuality: Int,
        track: VideoTracks.Track?,
        tiers: List<Rendition>,
        supports: (Rendition) -> Boolean,
    ): Boolean {
        if (!enabled()) return false
        // Some 720P/540P labels contain the same 480x854 video. Require a lower coded-size
        // ceiling, not merely a different label. The MP4 header is revalidated when reopened.
        val current = listOfNotNull(currentQuality.takeIf { it > 0 }, limits[id],
            track?.let { minOf(it.width, it.height).takeIf { size -> size > 0 } }).minOrNull() ?: return false
        val ceiling = listOf(720, 480, 360).firstOrNull { it < current } ?: return false
        val lower = tiers.filter {
            val width = it.width
            val height = it.height
            it.quality in 1..ceiling &&
                (width == null || height == null || minOf(width, height) <= ceiling) && supports(it)
        }.maxByOrNull { it.quality } ?: return false
        limits.remove(id)
        limits[id] = lower.quality
        while (limits.size > 16) limits.remove(limits.keys.first())
        return true
    }
}
