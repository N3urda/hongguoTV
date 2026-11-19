// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Media3 reports negative offsets when frames become available after their release deadline. */
internal class PlaybackSyncGuard(
    private val resyncThresholdUs: Long = 150_000,
    private val minFrames: Int = 120,
    private val cooldownMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Window(val averageUs: Long, val resync: Boolean)

    private var offsetUs = 0L
    private var frames = 0
    private var lastResyncAt = now()

    /**
     * Feed one onVideoFrameProcessingOffset batch; returns the closed window's verdict, or null
     * while accumulating.
     */
    fun offer(totalProcessingOffsetUs: Long, frameCount: Int, playing: Boolean): Window? {
        if (frameCount <= 0) return null
        offsetUs += totalProcessingOffsetUs
        frames += frameCount
        if (frames < minFrames) return null
        val average = offsetUs / frames
        offsetUs = 0
        frames = 0
        val advise =
            playing && average <= -resyncThresholdUs && now() - lastResyncAt >= cooldownMs
        if (advise) lastResyncAt = now()
        return Window(average, advise)
    }
}
