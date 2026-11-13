// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 两栏遥控搜索：左侧输入，右侧联想、历史与结果。

import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** Remote-first search workspace; the existing discovery filters remain a separate screen. */
internal class TvSearchPanel(
    context: Context,
    initial: String,
    history: List<String>,
    private val upper: View,
    fetch: (String) -> List<String>,
    private val search: (String) -> Unit,
) : LinearLayout(context) {
    val input =
        EditText(context).apply {
            id = View.generateViewId()
            hint = "剧名 / 拼音首字母"
            textSize = 15f
            setTextColor(TvStyle.text)
            setHintTextColor(TvStyle.muted)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(80))
            showSoftInputOnFocus = false
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(initial)
            setSelection(text.length)
            background = TvStyle.shape(context, TvStyle.surface, TvStyle.outline)
            setPadding(dp(14), 0, dp(12), 0)
        }
    val results = LinearLayout(context).apply { orientation = VERTICAL }
    private val keys = mutableListOf<TextView>()
    private val actions = mutableListOf<TextView>()
    private val recents = mutableListOf<TextView>()
    var resultEntry: () -> View? = { null }
    val suggestions: SearchSuggestionsView
    val searchButton: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 14f) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(TvStyle.muted)
            setPadding(0, dp(6), 0, dp(8))
        }

    private fun action(value: String, click: (View) -> Unit) =
        TextView(context).apply {
            id = View.generateViewId()
            text = value
            textSize = 13f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            minHeight = dp(36)
            setTextColor(TvStyle.text)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = TvStyle.card(context, false)
            setOnFocusChangeListener { _, focused ->
                background = TvStyle.shape(context, if (focused) TvStyle.text else TvStyle.surface, radius = 5)
                setTextColor(if (focused) TvStyle.background else TvStyle.text)
            }
            setOnClickListener(click)
        }

    private fun hideKeyboard() {
        input.showSoftInputOnFocus = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    // 提交前清理空白和隐藏输入法；非空关键词通过回调交给主页面搜索。
    private fun submit(value: String = input.text.toString()) {
        value
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let {
                hideKeyboard()
                search(it)
            }
    }

    // 替换输入框当前选区；没有选区时，在光标位置插入字符。
    private fun insert(value: String) {
        hideKeyboard()
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        input.text.replace(start, end, value)
    }

    // 优先删除选区，否则按 Unicode 码点删除，避免截断代理对字符。
    private fun delete() {
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        if (end > start) input.text.delete(start, end)
        else if (start > 0)
            input.text.delete(Character.offsetByCodePoints(input.text, start, -1), start)
    }

    // 组装左侧键盘、中间联想历史和右侧结果容器，并建立遥控器焦点关系。
    init {
        orientation = HORIZONTAL
        val compact = resources.configuration.screenWidthDp < 800
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        val leftScroll =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(left)
            }
        addView(
            leftScroll,
            LayoutParams(dp(if (compact) 206 else 248), -1).apply { rightMargin = dp(36) },
        )
        left.addView(label("搜索", 30f).apply { setTextColor(TvStyle.text); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        left.addView(label("下一部想看的，从这里开始", 12f).apply { setPadding(0, 0, 0, dp(18)) })
        left.addView(input, LayoutParams(-1, dp(42)))
        val keyboard =
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(6).forEach { row ->
            val line = LinearLayout(context)
            row.forEach { char ->
                val key = action(char.toString()) { insert(char.lowercaseChar().toString()) }
                keys += key
                line.addView(
                    key,
                    LayoutParams(0, dp(34), 1f).apply {
                        rightMargin = dp(5)
                        bottomMargin = dp(5)
                    },
                )
            }
            keyboard.addView(line)
        }
        left.addView(keyboard)
        searchButton = action("搜索") { submit() }
        actions +=
            listOf(
                action("删除") { delete() },
                action("清空") { input.setText("") },
                searchButton,
                action("打开输入法") {
                    input.showSoftInputOnFocus = true
                    input.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                },
            )
        actions.chunked(3).forEach { row ->
            val line = LinearLayout(context)
            row.forEach {
                line.addView(
                    it,
                    LayoutParams(0, dp(38), 1f).apply {
                        rightMargin = dp(5)
                        bottomMargin = dp(5)
                    },
                )
            }
            left.addView(line)
        }
        left.addView(label("方向键选字，确认键输入", 11f))
        val resultColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            background = TvStyle.shape(context, TvStyle.surface, radius = 16)
        }
        addView(resultColumn, LayoutParams(0, -1, 1f))
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        resultColumn.addView(words, LayoutParams(-1, -2))
        val suggestionLabel = label("联想词", 13f).apply { visibility = GONE }
        words.addView(suggestionLabel)
        suggestions =
            SearchSuggestionsView(
                context,
                input,
                listOf(keys.last()),
                { resultEntry() ?: input },
                fetch,
                { submit(it) },
                vertical = false,
                exitHorizontalEdges = true,
            )
        words.addView(suggestions, LayoutParams(-1, -2))
        if (history.isNotEmpty()) words.addView(label("最近搜索", 13f))
        val historyLine = LinearLayout(context)
        val historyScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(historyLine) }
        words.addView(historyScroll)
        history.distinct().take(8).forEach { value ->
            val recent =
                action(value) { submit(value) }
                    .apply {
                        maxLines = 2
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                    }
            recents += recent
            historyLine.addView(recent, LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        suggestions.afterWords = { recents.firstOrNull() ?: resultEntry() ?: input }
        resultColumn.addView(label("搜索结果", 16f).apply { setTextColor(TvStyle.text); setPadding(0, dp(12), 0, dp(12)) })
        resultColumn.addView(results, LayoutParams(-1, 0, 1f))
        input.nextFocusUpId = input.id
        input.nextFocusDownId = keys.first().id
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        suggestions.navigationChanged = { suggestionLabel.visibility = suggestions.visibility; updateNavigation() }
        updateNavigation()
    }

    fun middleEntry(): View? = suggestions.entry() ?: recents.firstOrNull()

    fun entry(): View = keys.first()

    fun updateNavigation() {
        val right = middleEntry()?.id ?: resultEntry()?.id ?: input.id
        keys.forEachIndexed { i, key ->
            key.nextFocusLeftId = if (i % 6 == 0) upper.id else keys[i - 1].id
            key.nextFocusRightId = if (i % 6 == 5) right else keys[i + 1].id
            key.nextFocusUpId = keys.getOrNull(i - 6)?.id ?: input.id
            key.nextFocusDownId = keys.getOrNull(i + 6)?.id ?: actions.first().id
        }
        actions.forEachIndexed { i, view ->
            view.nextFocusLeftId = actions.getOrNull(i - 1)?.takeIf { i % 3 != 0 }?.id ?: view.id
            view.nextFocusRightId = if (i % 3 == 2) right else actions.getOrNull(i + 1)?.id ?: right
            view.nextFocusUpId = actions.getOrNull(i - 3)?.id ?: keys[30 + i % 3].id
            view.nextFocusDownId = actions.getOrNull(i + 3)?.id ?: view.id
        }
        recents.forEachIndexed { i, view ->
            view.nextFocusUpId = recents.getOrNull(i - 1)?.id ?: suggestions.entry()?.id ?: input.id
            view.nextFocusDownId = recents.getOrNull(i + 1)?.id ?: view.id
            view.nextFocusLeftId = recents.getOrNull(i - 1)?.id ?: keys[5].id
            view.nextFocusRightId = recents.getOrNull(i + 1)?.id ?: resultEntry()?.id ?: input.id
        }
        input.nextFocusRightId = right
        input.nextFocusUpId = input.id
        input.nextFocusDownId = keys.first().id
    }
}
