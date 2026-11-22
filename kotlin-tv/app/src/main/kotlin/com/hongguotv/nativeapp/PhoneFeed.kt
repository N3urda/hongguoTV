// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.CatalogPage
import com.hongguotv.core.Detail
import com.hongguotv.core.DramaCategory
import com.hongguotv.core.Series
import com.hongguotv.core.ResumeTarget

/** Each switch prepares its own queue snapshot, which the activity commits with the result. */
internal data class PhoneFeed(
    val category: DramaCategory = DramaCategory.REAL,
    val items: List<Series> = emptyList(),
    val index: Int = -1,
    val page: Int = 0,
    val hasMore: Boolean = true,
) {
    val hasPrevious get() = index > 0
    val hasNext get() = index + 1 < items.size || hasMore

    data class Selection(val feed: PhoneFeed, val detail: Detail?)

    data class Prepared(val selection: Selection, val target: ResumeTarget?, val stream: OpenedStream?) : java.io.Closeable {
        override fun close() { stream?.close() }
    }

    fun select(
        direction: Int,
        catalogue: (Int) -> CatalogPage,
        details: (String) -> Detail,
    ): Selection {
        val step = if (direction < 0) -1 else 1
        var target = if (index == -1) 0 else index + step
        var number = page
        var more = hasMore
        var fetched = 0
        val queue = items.toMutableList()
        val seen = items.mapTo(mutableSetOf()) { it.id }
        repeat(12) {
            if (target < 0) return Selection(this, null)
            while (target >= queue.size && more && fetched < 3) {
                val result = catalogue(number + 1)
                number++
                fetched++
                queue += result.items.filter { seen.add(it.id) }.shuffled()
                more = result.hasMore && number < 100
            }
            val series = queue.getOrNull(target) ?: return Selection(copy(items = queue, page = number, hasMore = more), null)
            val detail = details(series.id)
            if (detail.episodes.isNotEmpty()) return Selection(copy(items = queue, index = target, page = number, hasMore = more), detail)
            queue.removeAt(target)
            if (step < 0) target--
        }
        return Selection(copy(items = queue, page = number, hasMore = more), null)
    }
}
