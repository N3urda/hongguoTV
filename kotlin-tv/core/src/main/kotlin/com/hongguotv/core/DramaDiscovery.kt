// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

internal object DiscoveryParser {
    fun info(
        section: JSONObject,
        category: DramaCategory,
        topic: String,
        page: Int,
    ): DiscoveryInfo {
        if (!section.optBoolean("isSuccess")) throw IOException("分类找剧暂不可用")
        val route = section.optJSONObject("categoryRoute") ?: throw IOException("分类信息已变化")
        val expected = "/category/${category.route}" + if (topic.isBlank()) "" else "/$topic"
        if (
            route.str("contentType") != category.route ||
                route.str("canonicalPath") != expected ||
                !route.optBoolean("enabled")
        )
            throw IOException("分类条件未被确认，请重新选择")
        val pagination = section.optJSONObject("pagination") ?: throw IOException("分类分页信息缺失")
        val pages = pagination.optInt("totalPages", -1)
        if (pagination.optInt("pageNum") != page || pages < 0) throw IOException("分类分页信息异常")
        val selectors = section.optJSONArray("selectorList") ?: throw IOException("题材选项格式已变化")
        val topics =
            selectors
                .objects()
                .flatMap { it.optJSONArray("items")?.objects().orEmpty() }
                .mapNotNull {
                    val id = it.str("selector_item_id")
                    val label = it.str("show_name")
                    if (
                        id.length <= 80 &&
                            id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) &&
                            label.isNotBlank() &&
                            label.length <= 40
                    )
                        DramaTopic(id, label)
                    else null
                }
                .distinctBy { it.id }
                .take(128)
        if (topic.isNotEmpty() && topics.none { it.id == topic }) throw IOException("该题材已变化，请重新选择")
        return DiscoveryInfo(
            topics,
            pages.coerceAtMost(100),
            pagination.optInt("pageSize", 24),
            pagination.optInt("total", 0).coerceAtLeast(0),
        )
    }
}
