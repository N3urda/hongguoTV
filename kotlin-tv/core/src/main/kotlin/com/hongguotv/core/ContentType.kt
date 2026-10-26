// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 两类内容。`catalog` 是官网片库 `/category/{catalog}` 的路由段：
 * 官网搜索的翻页参数服务端不生效（永远回第 1 页），目录补页扫的就是这里。
 */
enum class ContentType(val storedValue: String, val label: String, val searchTab: Int, val catalog: String) {
    COMIC("comic","漫剧",19,"comic-drama"),
    SHORT("short","短剧",11,"real-drama");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
