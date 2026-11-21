// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import kotlin.math.ceil

internal data class DanmakuItem(val id: String, val text: String, val timeMs: Long)

internal data class ScheduledDanmaku(
    val text: String,
    val startMs: Long,
    val lane: Int,
    val endMs: Long,
    val count: Int,
    val widthPx: Float,
) {
    fun xAt(timeMs: Long, viewportWidthPx: Int): Float =
        viewportWidthPx - (viewportWidthPx + widthPx) * (timeMs - startMs).toFloat() / (endMs - startMs)
}

internal data class DanmakuRegion(val topPx: Int, val heightPx: Int) {
    fun laneCount(textHeightPx: Int, lineHeightPx: Int, maxRows: Int): Int =
        if (heightPx < textHeightPx) 0 else minOf(maxRows, 1 + (heightPx - textHeightPx) / lineHeightPx)
}

internal enum class DanmakuArea(val preference: String, val label: String, private val fraction: Float) {
    TOP_EIGHTH("top_eighth", "顶部 1/8", 0.125f),
    TOP_QUARTER("top_quarter", "顶部 1/4", 0.25f),
    TOP_HALF("top", "顶部 1/2", 0.5f),
    TOP_THREE_QUARTERS("top_three_quarters", "顶部 3/4", 0.75f),
    FULL("full", "全屏", 1f),
    MIDDLE("middle", "中部 1/2", 0.5f),
    BOTTOM("bottom", "底部 1/2", 0.5f);

    fun region(viewHeightPx: Int, topInsetPx: Int, bottomInsetPx: Int, textHeightPx: Int): DanmakuRegion {
        val available = (viewHeightPx - topInsetPx - bottomInsetPx).coerceAtLeast(0)
        val height = (available * fraction).toInt().coerceAtLeast(textHeightPx).coerceAtMost(available)
        val space = available - height
        val offset = when (this) {
            MIDDLE -> space / 2
            BOTTOM -> space
            else -> 0
        }
        return DanmakuRegion(topInsetPx + offset, height)
    }

    companion object {
        fun fromPreference(value: String?): DanmakuArea = entries.firstOrNull { it.preference == value } ?: TOP_HALF
    }
}

internal object DanmakuLayout {
    /** 同轨道两条弹幕开始滚动的最小时间间隔。 */
    const val MIN_GAP_MS = 400L

    /** 限制顺延时间，让弹幕尽量留在对应剧情附近。 */
    const val MAX_DELAY_MS = 3000L

    /**
     * @param lanes 显示区域实际能容纳的轨道数
     * @param travelMs 单条弹幕完整穿过屏幕的时间
     * @param merge 五秒内相同文本是否合并为「文字 ×N」，保留最早出现时间
     * @param allowOverlap 轨道挤满时是否仍显示无法及时排入的弹幕
     */
    fun assign(
        items: List<DanmakuItem>,
        lanes: Int,
        travelMs: Int,
        viewportWidthPx: Int,
        gapPx: Int,
        merge: Boolean = true,
        allowOverlap: Boolean = false,
        maxDelayMs: Long = MAX_DELAY_MS,
        measureText: (String) -> Float,
    ): List<ScheduledDanmaku> {
        if (items.isEmpty() || lanes <= 0 || travelMs <= 0 || viewportWidthPx <= 0) return emptyList()
        // 先按真实 id 去重，避免重叠时间段重复计数；相同文字只在五秒内合并。
        val unique = items.distinctBy { it.id.ifBlank { "${it.timeMs}:${it.text}" } }
            .sortedWith(compareBy<DanmakuItem> { it.timeMs }.thenBy { it.id })
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

        val lastOnLane = arrayOfNulls<ScheduledDanmaku>(lanes)
        val clearanceMs = LongArray(lanes)
        val output = ArrayList<ScheduledDanmaku>(prepared.size)
        var overflowCount = 0
        for ((text, at, count) in prepared) {
            val display = if (count > 1) "$text ×$count" else text
            val widthPx = measureText(display)
            val clearance = ceil(travelMs * (widthPx.toDouble() + gapPx) / (viewportWidthPx + widthPx))
                .toLong().coerceAtLeast(MIN_GAP_MS)
            var bestLane = 0
            var bestStart = Long.MAX_VALUE
            for (lane in 0 until lanes) {
                val previous = lastOnLane[lane]
                // 前条尾部先离开入口，较长的后条也须等到追不上前条才入轨。
                val earliest = if (previous == null) at
                    else maxOf(at, previous.startMs + maxOf(clearanceMs[lane], clearance))
                if (earliest < bestStart) {
                    bestStart = earliest
                    bestLane = lane
                }
            }
            val latestStart = at + maxDelayMs.coerceAtLeast(0)
            if (bestStart > latestStart) {
                if (!allowOverlap) continue
                bestLane = overflowCount++ % lanes
                bestStart = latestStart
            }
            val item = ScheduledDanmaku(display, bestStart, bestLane, bestStart + travelMs, count, widthPx)
            lastOnLane[bestLane] = item
            clearanceMs[bestLane] = clearance
            output += item
        }
        output.sortBy { it.startMs }
        return output
    }

    fun firstStartingAfter(items: List<ScheduledDanmaku>, timeMs: Long): Int {
        var low = 0
        var high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (items[mid].startMs <= timeMs) low = mid + 1 else high = mid
        }
        return low
    }
}
