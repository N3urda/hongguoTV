// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 弹幕纯函数（聚合 / 中央轮次 / 样式归一）的对账，移植自 HGTV 1.0.8。 */
class DanmakuTest {
    private fun item(ms: Long,text: String,digg: Int=0)=Danmaku(ms,text,digg)

    @Test
    fun sameTextWithinWindowJoinsOneGroup() {
        val groups=repeatGroups(listOf(item(1_000,"好看"),item(5_000,"好看"),item(19_000,"好看")))
        assertEquals(1,groups.size)
        assertEquals(3,groups[0].count)
        assertEquals("好看 ×3",groups[0].label)
    }

    @Test
    fun sameTextBeyondWindowStartsNewRound() {
        // 20 秒窗口从第一条起算：21 秒后的再出现就是新的一轮，不会一路串到底。
        val groups=repeatGroups(listOf(item(1_000,"哈哈"),item(22_000,"哈哈")))
        assertEquals(2,groups.size)
        assertEquals(1,groups[0].count); assertEquals(1,groups[1].count)
        assertEquals("哈哈",groups[0].label)
    }

    @Test
    fun distinctTextsNeverMergeAndBlanksAreDropped() {
        val groups=repeatGroups(listOf(item(1_000,"A"),item(2_000,"B"),item(3_000,"  "),item(4_000,"")))
        assertEquals(listOf("A","B"),groups.map { it.text })
    }

    @Test
    fun highlightNeedsMinCountAndIsSorted() {
        val groups=repeatGroups(listOf(
            item(1_000,"刷屏"),item(2_000,"刷屏"),item(3_000,"刷屏"),
            item(4_000,"单发")))
        val rounds=planHighlights(groups)
        // 只有一条够 MIN_COUNT=2；单发永不进中央。
        assertEquals(1,rounds.size)
        assertEquals("刷屏 ×3",rounds[0].group.label)
        // 至少停留 MIN_DWELL_MS。
        assertTrue(rounds[0].endMs-rounds[0].startMs>=DanmakuHighlight.MIN_DWELL_MS)
    }

    @Test
    fun styleSnapClampsAndSnapsToStep() {
        assertEquals(0.6f,DanmakuStyle.scale(0.1f))
        assertEquals(2f,DanmakuStyle.scale(9f))
        assertEquals(0.9f,DanmakuStyle.scale(0.9f))
        assertEquals(1f,DanmakuStyle.alpha(1.7f))
        assertEquals(0.2f,DanmakuStyle.alpha(-1f))
        // 档位表覆盖全区间、逐项已归一。
        assertTrue(DanmakuStyle.SCALE_STEPS.first()==DanmakuStyle.SCALE_MIN)
        assertTrue(DanmakuStyle.SCALE_STEPS.last()==DanmakuStyle.SCALE_MAX)
        assertTrue(DanmakuStyle.ALPHA_STEPS.first()==DanmakuStyle.ALPHA_MIN)
        assertTrue(DanmakuStyle.ALPHA_STEPS.last()==DanmakuStyle.ALPHA_MAX)
    }

    @Test
    fun storedValuesRoundTrip() {
        DanmakuMode.entries.forEach { assertEquals(it,DanmakuMode.fromStored(it.storedValue)) }
        DanmakuSpeed.entries.forEach { assertEquals(it,DanmakuSpeed.fromStored(it.storedValue)) }
        // 未知 / 空值回落默认档。
        assertEquals(DanmakuMode.FULL,DanmakuMode.fromStored("bogus"))
        assertEquals(DanmakuSpeed.NORMAL,DanmakuSpeed.fromStored(null))
    }
}
