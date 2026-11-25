// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class KidsCatalogTest {
    @Test fun onlyAnExactProviderTagQualifies() {
        assertTrue(KidsCatalog.includes(Series("1", "剧名", tags = "动画 · 少儿 · 完结")))
        assertFalse(KidsCatalog.includes(Series("2", "少儿剧", description = "适合少儿", tags = "萌宝 · 少儿不宜")))
        assertFalse(KidsCatalog.includes(Series("3", "没有标签")))
    }

    @Test fun usesOnlyAnExactSelectorExposedByProvider() {
        val theme = DiscoveryGroup("category_dim_theme", "主题", listOf(DiscoveryFilter("cate_5003", "萌宝")))
        assertNull(KidsCatalog.selection(listOf(theme)))
        val exact = theme.copy(options = theme.options + DiscoveryFilter("kids_fixture", "少儿"))
        assertEquals(mapOf("category_dim_theme" to "kids_fixture"), KidsCatalog.selection(listOf(exact))?.values)
    }

    @Test fun websiteSearchTagFilterAcceptsExactKidsCategory() {
        assertTrue(KidsCatalog.includes(Series("1", "儿歌多多超级医生", tags = "剧情 · 少儿 · 治愈 · 医生")))
        assertFalse(KidsCatalog.includes(Series("2", "咕咕嘎嘎和哈基蜂上学记", tags = "青春 · 校园生活 · 轻松搞笑")))
    }
}
