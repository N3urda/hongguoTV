package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesDisplayOrderTest {
    private val rows = listOf(Series("3", "测试剧第三季"), Series("10", "测试剧第十季"),
        Series("1", "测试剧第一季"), Series("2", "测试剧第2季"))
    @Test fun seasonsAreNumericButAnExplicitSeasonIsFirst() {
        assertEquals(listOf("1", "2", "3", "10"), SeriesDisplayOrder.search(rows, "测试剧").map { it.id })
        assertEquals(listOf("3", "1", "2", "10"), SeriesDisplayOrder.search(rows, "测试剧第3季").map { it.id })
        val unrelated = listOf(Series("a", "爱在第三季的我们"), Series("b", "爱在第二季的我们"))
        assertEquals(unrelated, SeriesDisplayOrder.search(unrelated, "爱在"))
    }
    @Test fun namesUseChineseOrderAndStableIdsWithoutMergingWorks() {
        val result = SeriesDisplayOrder.byName(rows + Series("b", "北京") + Series("a", "安徽") + Series("4", "测试剧第三季"))
        assertEquals(listOf("a", "b", "1", "2", "3", "4", "10"), result.map { it.id })
    }
}
