// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * 弹幕轨道调度：只做时间与轨道分配，不涉及绘制。
 *
 * 旧实现是「轨道被占用就丢弃这条弹幕」，默认 2 条轨道、每条占满整个滚动时长，
 * 120 秒最多只能显示 20 条，实测一集 259 条弹幕会被丢掉九成以上。
 * 现在改为：轨道按需排队，超出容量时短暂顺延或同时进入，不丢弃；同一文本可合并计数。
 */
internal data class DanmakuItem(val id: String, val text: String, val timeMs: Long)

internal data class ScheduledDanmaku(
    val text: String,
    val startMs: Long,
    val lane: Int,
    val endMs: Long,
    val count: Int,
)

internal object DanmakuLayout {
    /** 同轨道两条弹幕之间的最小间隔，避免文字重叠。 */
    const val MIN_GAP_MS = 400L

    /** 允许的最大顺延；拥挤时允许重叠，避免排队到剧集结束后。 */
    const val MAX_DELAY_MS = 3000L

    /**
     * @param lanes 可用轨道数（已按屏幕高度算好）
     * @param travelMs 单条弹幕完整穿过屏幕的时间
     * @param merge 五秒内相同文本是否合并为「文字 ×N」，保留最早出现时间
     */
    fun assign(
        items: List<DanmakuItem>,
        lanes: Int,
        travelMs: Int,
        merge: Boolean = true,
        minGapMs: Long = MIN_GAP_MS,
        maxDelayMs: Long = MAX_DELAY_MS,
    ): List<ScheduledDanmaku> {
        if (items.isEmpty() || lanes <= 0 || travelMs <= 0) return emptyList()
        // 先按真实 id 去重，避免重叠时间段重复计数；相同文字只在五秒内合并。
        val unique = items.distinctBy { it.id.ifBlank { "${it.timeMs}:${it.text}" } }.sortedBy { it.timeMs }
        val prepared = mutableListOf<Triple<String, Long, Int>>()
        val latest = mutableMapOf<String, Int>()
        for (item in unique) {
            val text = item.text.trim()
            if (text.isEmpty()) continue
            val index = if (merge) latest[text] else null
            val previous = index?.let { prepared[it] }
            if (previous != null && item.timeMs - previous.second <= 5000L) {
                prepared[index!!] = previous.copy(third = previous.third + 1)
            } else {
                latest[text] = prepared.size
                prepared += Triple(text, item.timeMs, 1)
            }
        }

        val laneFreeAt = LongArray(lanes) { Long.MIN_VALUE }
        val output = ArrayList<ScheduledDanmaku>(prepared.size)
        for ((text, at, count) in prepared) {
            var bestLane = 0
            var bestStart = Long.MAX_VALUE
            for (lane in 0 until lanes) {
                val earliest = maxOf(at, laneFreeAt[lane] + minGapMs)
                if (earliest < bestStart) {
                    bestStart = earliest
                    bestLane = lane
                }
            }
            // 限制等待时间，轨道拥挤时允许重叠，不能把弹幕推迟到片尾之后。
            val start = minOf(bestStart, at + maxDelayMs.coerceAtLeast(0)).coerceAtLeast(at)
            laneFreeAt[bestLane] = start + travelMs / 3
            output +=
                ScheduledDanmaku(
                    text = if (count > 1) "$text ×$count" else text,
                    startMs = start,
                    lane = bestLane,
                    endMs = start + travelMs,
                    count = count,
                )
        }
        return output
    }

    /** 按屏幕高度推算可用轨道数，单条行高由字号决定。 */
    fun laneCountFor(heightPx: Int, textSizePx: Int, topPaddingPx: Int, maxLanes: Int = 12): Int {
        if (heightPx <= 0 || textSizePx <= 0) return 1
        val usable = heightPx - topPaddingPx
        val lineHeight = (textSizePx * 1.8f).toInt().coerceAtLeast(1)
        return (usable / lineHeight).coerceIn(1, maxLanes)
    }
}
