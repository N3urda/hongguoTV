// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.KidsCatalog
import com.hongguotv.core.Series

/** Accumulates pages of the website's exact-tag search; tag filtering happens before display. */
internal class KidsCatalogPage(private val repository: ContentRepository) {
    private val all = linkedMapOf<String, Series>()
    private var page = 0
    private var hasMore = true
    val items: List<Series>
        get() = all.values.toList()

    fun reset() {
        all.clear()
        page = 0
        hasMore = true
    }

    fun canLoadMore() = hasMore && page < 100

    fun loadMore(): Boolean {
        if (!canLoadMore()) return false
        val result = repository.websiteSearch(KidsCatalog.LABEL, page + 1)
        page += 1
        hasMore = result.hasMore && page < 100
        result.items.forEach { all.putIfAbsent(it.id, it) }
        return items.isNotEmpty()
    }
}
