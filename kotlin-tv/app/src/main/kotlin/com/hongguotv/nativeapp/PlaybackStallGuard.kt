// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Detect silent decoder stalls only while buffered video should be rendering. */
internal class PlaybackStallGuard(private val timeoutMs: Long = 20_000) {
    private var episode = ""
    private var lastFrames: Int? = null
    private var lastProgressAt = 0L
    private var recoveries = 0

    fun suspendObservation() { lastFrames = null }

    fun offer(episodeId: String, now: Long, frames: Int?, eligible: Boolean): Boolean {
        if (episodeId != episode) {
            episode = episodeId
            recoveries = 0
            lastFrames = null
        }
        if (!eligible || frames == null || episodeId.isEmpty()) {
            lastFrames = null
            lastProgressAt = now
            return false
        }
        if (lastFrames == null || frames != lastFrames) {
            lastFrames = frames
            lastProgressAt = now
            return false
        }
        if (recoveries >= 2 || now - lastProgressAt < timeoutMs) return false
        recoveries++
        lastFrames = null
        lastProgressAt = now
        return true
    }
}
