// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

enum class DramaCategory(val label: String, val route: String) {
    REAL("真人剧", "real-drama"),
    COMIC("漫剧", "comic-drama"),
    AI("AI 剧", "ai-drama"),
}

data class DramaTopic(val id: String, val label: String)

data class DiscoveryInfo(
    val topics: List<DramaTopic>,
    val totalPages: Int,
    val pageSize: Int,
    val total: Int,
)

data class DiscoveryMemory(
    val page: Int,
    val items: List<Series>,
    val hasMore: Boolean,
    val focus: String,
    val info: DiscoveryInfo?,
)

class DramaDiscoveryState {
    var category = DramaCategory.COMIC
    var topic = ""
    var info: DiscoveryInfo? = null
    var browse: DiscoveryMemory? = null

    fun select(category: DramaCategory, topic: String = "") {
        require(topic.isEmpty() || topic.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
        this.category = category
        this.topic = topic
        info = null
        browse = null
    }

    fun topicLabel() = info?.topics?.firstOrNull { it.id == topic }?.label ?: "全部题材"
}
