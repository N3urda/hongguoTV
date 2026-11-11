// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TimedCommentWindowsTest {
    private fun page(start: Long, end: Long) = JSONObject().put("startMS", start).put("nextMS", end)

    @Test
    fun shortServerWindowsAreContinuedWithoutThirtySecondGaps() {
        val pages = mutableListOf<JSONObject>()
        assertEquals(0L, TimedCommentWindows.next(pages, 8000, 90000))
        pages += page(0, 5000)
        assertEquals(5000L, TimedCommentWindows.next(pages, 8000, 90000))
        pages += page(5000, 11000)
        assertEquals(11000L, TimedCommentWindows.next(pages, 8000, 90000))
        pages += page(11000, 22000)
        assertNull(TimedCommentWindows.next(pages, 8000, 90000))
    }

    @Test
    fun overlappingPagesSeekingAndEndOfEpisodeRemainBounded() {
        val pages = listOf(page(0, 45000), page(30000, 60000))
        assertNull(TimedCommentWindows.next(pages, 40000, 90000))
        assertEquals(90000L, TimedCommentWindows.next(pages, 105000, 120000))
        assertNull(TimedCommentWindows.next(listOf(page(0, 90000)), 89000, 90000))
        assertEquals(0L, TimedCommentWindows.next(listOf(page(5000, 5000)), 0, 90000))
    }
}
