// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 音画同步保护：累计视频帧相对音频时钟的偏移，按阈值和冷却时间建议重新同步。

import kotlin.math.abs

/**
 * Watches ExoPlayer's video frame processing offset to detect growing audio/video drift (pause
 * keeps the offset, a seek clears it) and advises one seek-based resync per window. Drift shows up
 * as consistently signed offsets: video rendered durably late (positive) or early (negative)
 * relative to the audio clock.
 */
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
            playing && abs(average) >= resyncThresholdUs && now() - lastResyncAt >= cooldownMs
        if (advise) lastResyncAt = now()
        return Window(average, advise)
    }
}
