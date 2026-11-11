// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 弹幕调度：不丢弃、同轨不重叠、合并计数与轨道推算。 */
class DanmakuLayoutTest {
    private fun items(vararg pairs: Pair<String, Long>) =
        pairs.mapIndexed { index, (text, at) -> DanmakuItem("id$index", text, at) }

    @Test
    fun everyCommentIsScheduledEvenWhenLanesAreSaturated() {
        // 2 秒内 60 条、只有 2 条轨道：旧实现会丢掉绝大部分，现在必须全部排上。
        val rows = (0 until 60).map { DanmakuItem("id$it", "弹幕$it", (it * 30).toLong()) }
        val scheduled = DanmakuLayout.assign(rows, lanes = 2, travelMs = 12000)
        assertEquals(60, scheduled.size)
    }

    @Test
    fun sameLaneKeepsMinimumGap() {
        val rows = items("A" to 0L, "B" to 100L, "C" to 200L, "D" to 300L)
        val scheduled = DanmakuLayout.assign(rows, lanes = 1, travelMs = 900)
        assertEquals(4, scheduled.size)
        val ordered = scheduled.sortedBy { it.startMs }
        for (index in 1 until ordered.size) {
            assertTrue(
                "同轨间隔不足：${ordered[index - 1]} -> ${ordered[index]}",
                ordered[index].startMs - ordered[index - 1].startMs >= DanmakuLayout.MIN_GAP_MS,
            )
            assertEquals(0, ordered[index].lane)
        }
    }

    @Test
    fun duplicateTextIsMergedWithCount() {
        val rows = items("哈哈" to 1000L, "哈哈" to 5000L, "好看" to 2000L)
        val scheduled = DanmakuLayout.assign(rows, lanes = 3, travelMs = 9000, merge = true)
        assertEquals(2, scheduled.size)
        val merged = scheduled.first { it.text.startsWith("哈哈") }
        assertEquals(2, merged.count)
        assertEquals("哈哈 ×2", merged.text)
        // 合并保留最早出现时间
        assertEquals(1000L, merged.startMs)
    }

    @Test
    fun repeatedTextAtDifferentTimesStaysVisibleAndOverlappingPagesDoNotDoubleCount() {
        val rows = listOf(
            DanmakuItem("a", "哈哈", 0), DanmakuItem("a", "哈哈", 0),
            DanmakuItem("b", "哈哈", 4000), DanmakuItem("c", "哈哈", 9000),
            DanmakuItem("d", "哈哈", 30000),
        )
        val scheduled = DanmakuLayout.assign(rows.reversed(), lanes = 3, travelMs = 9000)
        assertEquals(listOf(2, 1, 1), scheduled.map { it.count })
        assertEquals(listOf(0L, 9000L, 30000L), scheduled.map { it.startMs })
    }

    @Test
    fun denseCommentsAreNotQueuedPastMaximumDelay() {
        val rows = (0 until 300).map { DanmakuItem("id$it", "弹幕$it", 1000) }
        val scheduled = DanmakuLayout.assign(rows, lanes = 2, travelMs = 12000)
        assertEquals(300, scheduled.size)
        assertTrue(scheduled.all { it.startMs in 1000L..4000L })
    }

    @Test
    fun mergeCanBeDisabled() {
        val rows = items("哈哈" to 1000L, "哈哈" to 5000L)
        val scheduled = DanmakuLayout.assign(rows, lanes = 3, travelMs = 9000, merge = false)
        assertEquals(2, scheduled.size)
        assertTrue(scheduled.all { it.count == 1 })
    }

    @Test
    fun laneCountFollowsScreenHeight() {
        // 1080p 屏幕、24dp 字号（约 72px 高）：可用高度能放下多条轨道
        val lanes = DanmakuLayout.laneCountFor(heightPx = 1000, textSizePx = 72, topPaddingPx = 100)
        assertTrue(lanes in 3..10)
        assertEquals(1, DanmakuLayout.laneCountFor(heightPx = 0, textSizePx = 72, topPaddingPx = 0))
        assertEquals(12, DanmakuLayout.laneCountFor(heightPx = 10000, textSizePx = 10, topPaddingPx = 0))
    }

    @Test
    fun emptyInputProducesNothing() {
        assertTrue(DanmakuLayout.assign(emptyList(), lanes = 8, travelMs = 9000).isEmpty())
        assertTrue(DanmakuLayout.assign(items("A" to 0L), lanes = 0, travelMs = 9000).isEmpty())
    }
}
