// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

// 搜索页输入框下面那一行：空输入时铺热搜词，打字时铺联想提示词。
// 数据来自 core 的 suggestions() / hotSearches()（都不需要签名与登录态）。
//
// 三条纪律：
// * **只动这一行**：换关键词、热搜换成提示词都不重画整页，因此输入时页面不会闪；
// * 请求走自己的线程与令牌，**不占用 work 那条「一次导航一个请求」的通道**——
//   它是边打字边发的，绝不能把页面重排、更不能取消用户正在等的搜索；
// * 结果回来先比对令牌与当前输入框内容，晚回来的旧结果直接丢掉。

/** 输入停顿多久才去取提示词：每敲一个字都发请求会把接口打满，也会让这一行一直闪。 */
private const val SUGGEST_DEBOUNCE = 220L

/** 建立提示词行。进搜索页、还没有关键词时直接铺热搜词。 */
internal fun MainActivity.buildSuggestRow(parent: LinearLayout) {
    val line = column()
    val scroll = HorizontalScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
    }
    scroll.addView(line)
    parent.addView(scroll, lp(-1, TvStyle.touch(56)).apply { topMargin = dp(6) })
    suggestLine = line
    suggestRow = scroll
    if (query.isBlank()) loadHotSearches()
}

/**
 * 输入变化 → 去抖取提示词。
 *
 * 清空输入时立刻换回热搜词（不发请求）。
 */
internal fun MainActivity.scheduleSuggestions(text: String) {
    suggestRun?.let { main.removeCallbacks(it) }
    val clean = text.trim()
    if (clean.isEmpty()) {
        suggestToken.incrementAndGet()
        loadHotSearches()
        return
    }
    val run = Runnable { fetchSuggestions(clean) }
    suggestRun = run
    main.postDelayed(run, SUGGEST_DEBOUNCE)
}

/**
 * 取一次热搜词（搜索页空输入时铺在输入框下面那一行）。
 *
 * 与提示词共用同一行与同一套令牌：热搜词是「没有输入」时的默认内容，
 * 一旦开始打字就换成提示词。
 */
internal fun MainActivity.loadHotSearches() {
    val ticket = suggestToken.incrementAndGet()
    suggestJob?.cancel(true)
    suggestJob = io.submit {
        val result = runCatching { repository.hotSearches() }.getOrDefault(emptyList())
        main.post {
            if (isDestroyed || screen != "catalog" || tab != 1) return@post
            if (ticket != suggestToken.get()) return@post
            // 用户已经开始打字了（输入框里有内容）：这一批热搜词让位给提示词。
            if ((searchInput as? EditText)?.text?.toString()?.trim().orEmpty().isNotEmpty()) return@post
            renderSuggestRow(result)
        }
    }
}

/**
 * 取一次联想提示词。
 *
 * 结果回来时先比对令牌与当前输入框内容——晚回来的旧结果直接丢掉。
 */
internal fun MainActivity.fetchSuggestions(keyword: String) {
    suggestJob?.cancel(true)
    val ticket = suggestToken.incrementAndGet()
    suggestJob = io.submit {
        val result = runCatching { repository.suggestions(keyword) }.getOrDefault(emptyList())
        main.post {
            if (isDestroyed || screen != "catalog" || tab != 1) return@post
            if (ticket != suggestToken.get()) return@post
            // 输入框里的词已经变了（用户又敲了字）：这一批已经过时。
            val current = (searchInput as? EditText)?.text?.toString()?.trim().orEmpty()
            if (current != keyword) return@post
            renderSuggestRow(result.map { it.name })
        }
    }
}

/**
 * 重建输入框下面那一行（热搜词 / 提示词共用）。
 *
 * **只动这一行**：换关键词、热搜换成提示词都不重画整页，因此输入时页面不会闪。
 * 每一枚芯片都是**一个搜索词**：点了就把这个词填进输入框并搜——即使服务端在这一条上
 * 给了剧集信息，也不直接跳简介页：那一行是「搜索建议」，用户点它是想搜这个词。
 * 空列表时整行隐藏，不留空槽。
 */
internal fun MainActivity.renderSuggestRow(items: List<String>) {
    val line = suggestLine ?: return
    val scroll = suggestRow ?: return
    suggestButtons.clear()
    line.removeAllViews()
    val firstCard = catalogCards.values.firstOrNull { it.isShown }
    if (items.isEmpty()) {
        scroll.visibility = View.GONE
        // 这一行收起来之后，导航栏里那几件按下键要回到内容第一张卡——不能指向一条看不见的行。
        searchInput?.nextFocusDownId = firstCard?.id ?: searchInput?.id ?: View.NO_ID
        searchButton?.nextFocusDownId = searchInput?.nextFocusDownId ?: View.NO_ID
        searchActions.forEach { it.nextFocusDownId = searchInput?.nextFocusDownId ?: View.NO_ID }
        return
    }
    val chips = mutableListOf<View>()
    items.forEach { label ->
        val chip = button(label) {
            (searchInput as? EditText)?.setText(label)
            runSearch(label)
        }
        chip.contentDescription = "$label，搜索"
        line.addView(chip, lp(-2, -2).apply { rightMargin = dp(8) })
        chips += chip
    }
    // 左右在 HorizontalScrollView 内自然走（芯片都是可聚焦的）；向上回输入框，向下进内容第一张卡。
    chips.forEach {
        it.nextFocusUpId = searchInput?.id ?: it.id
        it.nextFocusDownId = firstCard?.id ?: it.id
    }
    searchInput?.nextFocusDownId = chips.first().id
    searchButton?.nextFocusDownId = chips.first().id
    searchActions.forEach { it.nextFocusDownId = chips.first().id }
    suggestButtons.addAll(chips)
    scroll.visibility = View.VISIBLE
}
