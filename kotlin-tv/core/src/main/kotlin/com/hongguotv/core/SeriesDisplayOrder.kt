// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Presentation-only ordering; never changes upstream requests, filtering, or pagination. */
object SeriesDisplayOrder {
    private val suffix = Regex("第?([0-9零〇一二两三四五六七八九十百]+)季(?:完结篇|完結篇)?$")
    private data class Name(val stem: String, val season: Int?, val full: String)
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        var total = 0
        var digit = 0
        for (c in value) when (c) {
            '十', '百' -> { total += maxOf(1, digit) * if (c == '十') 10 else 100; digit = 0 }
            '两' -> digit = 2
            '〇' -> digit = 0
            else -> { digit = "零一二三四五六七八九".indexOf(c); if (digit < 0) return null }
        }
        return (total + digit).takeIf { it > 0 }
    }
    private fun name(title: String): Name {
        val full = normalize(title)
        val match = suffix.find(full)
        val season = match?.let { number(it.groupValues[1]) }
        return if (season != null && match.range.first > 0)
            Name(full.substring(0, match.range.first), season, full) else Name(full, null, full)
    }
    fun byName(items: List<Series>): List<Series> {
        val collator = Collator.getInstance(Locale.CHINA)
        val names = items.associate { it.id to name(it.title) }
        return items.sortedWith(Comparator { a, b ->
            val x = names.getValue(a.id); val y = names.getValue(b.id)
            val stem = collator.compare(x.stem, y.stem)
            if (stem != 0) stem else {
                val season = (x.season ?: 1).compareTo(y.season ?: 1)
                if (season != 0) season else {
                    val title = collator.compare(x.full, y.full)
                    if (title != 0) title else a.id.compareTo(b.id)
                }
            }
        })
    }
    fun search(items: List<Series>, keyword: String): List<Series> {
        val query = name(keyword)
        fun rank(value: String) = when {
            value == query.full -> 0
            value.startsWith(query.full) -> 1
            value.contains(query.full) -> 2
            else -> 3
        }
        val groups = items.groupBy { name(it.title).stem }
        return groups.values.sortedBy { rows ->
            if (query.season != null && name(rows.first().title).stem == query.stem) 0
            else rows.minOf { rank(name(it.title).full) }
        }.flatMap { rows ->
            rows.sortedWith(compareBy<Series> {
                val item = name(it.title)
                if (query.season != null && item.stem == query.stem && item.season == query.season) 0 else 1
            }.thenBy { name(it.title).season ?: 1 })
        }
    }
}

fun CatalogPage.inSearchOrder(keyword: String): CatalogPage = copy(items = SeriesDisplayOrder.search(items, keyword))
