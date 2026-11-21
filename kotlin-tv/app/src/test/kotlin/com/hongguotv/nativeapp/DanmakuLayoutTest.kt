// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuLayoutTest {
    private fun items(vararg pairs: Pair<String, Long>) =
        pairs.mapIndexed { index, (text, at) -> DanmakuItem("id$index", text, at) }

    private fun schedule(
        rows: List<DanmakuItem>,
        lanes: Int,
        travelMs: Int,
        merge: Boolean = true,
        allowOverlap: Boolean = false,
        maxDelayMs: Long = 3000,
        gapPx: Int = 0,
        measureText: (String) -> Float = { 40f },
    ) = DanmakuLayout.assign(
        rows, lanes, travelMs, viewportWidthPx = 1000, gapPx = gapPx, merge = merge,
        allowOverlap = allowOverlap, maxDelayMs = maxDelayMs, measureText = measureText,
    )

    @Test
    fun overlapModeKeepsEveryCommentWhenLanesAreSaturated() {
        val rows = (0 until 60).map { DanmakuItem("id$it", "弹幕$it", (it * 30).toLong()) }
        val scheduled = schedule(rows, lanes = 2, travelMs = 12000, allowOverlap = true)
        assertEquals(60, scheduled.size)
    }

    @Test
    fun defaultSchedulingSkipsCommentsWhenNeitherLaneIsFree() {
        val rows = (0 until 12).map { DanmakuItem("id$it", "弹幕$it", 1000) }
        val scheduled = schedule(rows, lanes = 2, travelMs = 7000, maxDelayMs = 0)
        assertEquals(2, scheduled.size)
        assertEquals(setOf(0, 1), scheduled.map { it.lane }.toSet())
    }

    @Test
    fun sameLaneKeepsMinimumGap() {
        val rows = items("A" to 0L, "B" to 100L, "C" to 200L, "D" to 300L)
        val scheduled = schedule(rows, lanes = 1, travelMs = 900)
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
    fun aLongerCommentCannotCatchUpToThePreviousComment() {
        val rows = items("短" to 0L, "较长的弹幕" to 100L)
        val scheduled = schedule(rows, lanes = 1, travelMs = 6000, gapPx = 20) {
            if (it == "短") 100f else 1000f
        }
        val (leading, following) = scheduled
        assertEquals(3060L, following.startMs)
        val leadingExit = leading.endMs
        assertTrue(following.xAt(leadingExit, 1000) >= 20f)
    }

    @Test
    fun duplicateTextIsMergedWithCount() {
        val rows = items("哈哈" to 1000L, "哈哈" to 5000L, "好看" to 2000L)
        val scheduled = schedule(rows, lanes = 3, travelMs = 9000, merge = true)
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
        val scheduled = schedule(rows.reversed(), lanes = 3, travelMs = 9000)
        assertEquals(listOf(2, 1, 1), scheduled.map { it.count })
        assertEquals(listOf(0L, 9000L, 30000L), scheduled.map { it.startMs })
    }

    @Test
    fun denseCommentsAreNotQueuedPastMaximumDelay() {
        val rows = (0 until 300).map { DanmakuItem("id$it", "弹幕$it", 1000) }
        val scheduled = schedule(rows, lanes = 2, travelMs = 12000, allowOverlap = true)
        assertEquals(300, scheduled.size)
        assertTrue(scheduled.all { it.startMs in 1000L..4000L })
    }

    @Test
    fun mergeCanBeDisabled() {
        val rows = items("哈哈" to 1000L, "哈哈" to 5000L)
        val scheduled = schedule(rows, lanes = 3, travelMs = 9000, merge = false)
        assertEquals(2, scheduled.size)
        assertTrue(scheduled.all { it.count == 1 })
    }

    @Test
    fun laneCountFitsBothTheDisplayRegionAndTheSelectedRowLimit() {
        val quarter = DanmakuArea.TOP_QUARTER.region(620, 60, 40, 30)
        assertEquals(DanmakuRegion(60, 130), quarter)
        assertEquals(3, quarter.laneCount(textHeightPx = 30, lineHeightPx = 48, maxRows = 12))
        assertEquals(2, quarter.laneCount(textHeightPx = 30, lineHeightPx = 48, maxRows = 2))
        assertEquals(2, quarter.laneCount(textHeightPx = 30, lineHeightPx = 56, maxRows = 12))
        assertEquals(DanmakuRegion(60, 520), DanmakuArea.FULL.region(620, 60, 40, 30))
        assertEquals(DanmakuRegion(190, 260), DanmakuArea.MIDDLE.region(620, 60, 40, 30))
        assertEquals(DanmakuRegion(320, 260), DanmakuArea.BOTTOM.region(620, 60, 40, 30))
        val small = DanmakuArea.TOP_EIGHTH.region(100, 10, 10, 30)
        assertEquals(1, small.laneCount(textHeightPx = 30, lineHeightPx = 48, maxRows = 12))
    }

    @Test
    fun timeWindowSearchIncludesEveryCommentWithTheSameStartTime() {
        val rows = items("A" to 1000L, "B" to 1000L, "C" to 5000L)
        val scheduled = schedule(rows, lanes = 2, travelMs = 7000)
        assertEquals(0, DanmakuLayout.firstStartingAfter(scheduled, 999))
        assertEquals(2, DanmakuLayout.firstStartingAfter(scheduled, 1000))
        assertEquals(3, DanmakuLayout.firstStartingAfter(scheduled, 5000))
    }

    @Test
    fun emptyInputProducesNothing() {
        assertTrue(schedule(emptyList(), lanes = 8, travelMs = 9000).isEmpty())
        assertTrue(schedule(items("A" to 0L), lanes = 0, travelMs = 9000).isEmpty())
    }
}
