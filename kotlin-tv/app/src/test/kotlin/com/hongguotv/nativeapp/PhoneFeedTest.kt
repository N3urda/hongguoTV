// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.CatalogPage
import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneFeedTest {
    @Test
    fun skipUnplayableAndRepeatedTitlesThenReturnToOriginal() {
        val first = Series("111", "第一部")
        val empty = Series("222", "无分集")
        val second = Series("333", "第二部")
        val start = PhoneFeed(items = listOf(first, empty), index = 0, page = 1)
        val browse = { page: Int ->
            assertEquals(2, page)
            CatalogPage(items = listOf(first, second), hasMore = false)
        }
        val detail = { id: String ->
            if (id == empty.id) Detail(empty, emptyList())
            else Detail(if (id == first.id) first else second, listOf("444"))
        }
        val next = start.select(1, browse, detail)
        assertEquals(second, next.detail?.series)
        assertEquals(listOf(first, second), next.feed.items)
        assertFalse(next.feed.hasNext)
        val previous = next.feed.select(-1, { error("Previous title is cached") }, detail)
        assertEquals(first, previous.detail?.series)
        assertEquals(0, previous.feed.index)
    }
}
