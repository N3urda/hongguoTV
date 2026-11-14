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
            textSize = 20f
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
    data class EditState(val draft: String, val words: List<String>, val word: Int, val recent: Int, val key: Int)
    private var lastKey = 0
    private var editingFocus: View? = null
    private val editor = LinearLayout(context)
    private val resultStage = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private lateinit var backButton: TextView
    var showingResults = false
        private set
    fun capture() = EditState(input.text.toString(), suggestions.values(), suggestions.selectedIndex(), recents.indexOfFirst { it.hasFocus() }, lastKey)
    fun restore(state: EditState) {
        input.setText(state.draft)
        suggestions.cancel()
        suggestions.restore(state.words)
        lastKey = state.key.coerceIn(0, 35)
        editingFocus = if (state.word >= 0) { suggestions.viewAt(state.word) }
            else recents.getOrNull(state.recent) ?: keys[lastKey]
        updateNavigation()
    }
    fun showResults() {
        if (!showingResults) editingFocus = findFocus() ?: editingFocus
        showingResults = true
        editor.visibility = GONE
        resultStage.visibility = VISIBLE
        backButton.requestFocus()
    }
    fun back(): Boolean {
        if (!showingResults) return false
        showingResults = false
        resultStage.visibility = GONE
        editor.visibility = VISIBLE
        if (editingFocus?.requestFocus() != true) entry().requestFocus()
        updateNavigation()
        return true
    }
    fun resultTitle(query: String) { resultHeading.text = "搜索 · $query" }
    private val resultHeading = label("搜索结果", 24f).apply { setTextColor(TvStyle.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    fun resultBackEntry(): View = backButton
    val results = LinearLayout(context).apply { orientation = VERTICAL }
    private val keys = mutableListOf<TextView>()
    private val actions = mutableListOf<TextView>()
    private val recents = mutableListOf<TextView>()
    var resultEntry: () -> View? = { null }
    val suggestions: SearchSuggestionsView
    val searchButton: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 18f) =
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
            textSize = 18f
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
        orientation = VERTICAL
        addView(editor, LayoutParams(-1, -1))
        addView(resultStage, LayoutParams(-1, -1))
        backButton = action("← 返回选词") { back() }
        val resultHeader = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        resultHeader.addView(backButton, LayoutParams(-2, dp(46)).apply { rightMargin = dp(20) })
        resultHeader.addView(resultHeading, LayoutParams(0, -2, 1f))
        resultStage.addView(resultHeader)
        resultStage.addView(results, LayoutParams(-1, 0, 1f))
        val compact = resources.configuration.screenWidthDp < 800
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        val leftScroll =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(left)
            }
        editor.addView(
            leftScroll,
            LayoutParams(dp(if (compact) 206 else 248), -1).apply { rightMargin = dp(36) },
        )
        left.addView(label("搜索", 30f).apply { setTextColor(TvStyle.text); typeface = android.graphics.Typeface.DEFAULT_BOLD })

        left.addView(input, LayoutParams(-1, dp(48)))
        val keyboard =
            LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(6).forEach { row ->
            val line = LinearLayout(context)
            row.forEach { char ->
                val key = action(char.toString()) { insert(char.lowercaseChar().toString()) }
                val keyIndex = keys.size
                val styleFocus = key.onFocusChangeListener
                key.setOnFocusChangeListener { view, focused ->
                    styleFocus?.onFocusChange(view, focused)
                    if (focused) { lastKey = keyIndex; suggestionsNavigation() }
                }
                keys += key
                line.addView(
                    key,
                    LayoutParams(0, dp(40), 1f).apply {
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
                    LayoutParams(0, dp(40), 1f).apply {
                        rightMargin = dp(5)
                        bottomMargin = dp(5)
                    },
                )
            }
            left.addView(line)
        }
        left.addView(label("→ 选词   ·   确认输入", 15f))
        val resultColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            background = TvStyle.shape(context, TvStyle.background, radius = 16)
        }
        editor.addView(resultColumn, LayoutParams(0, -1, 1f))
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        resultColumn.addView(ScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(words)
        }, LayoutParams(-1, -1))
        val suggestionLabel = label("猜你想搜", 22f).apply { visibility = GONE }
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
                grid = true,
            )
        words.addView(suggestions, LayoutParams(-1, -2))
        if (history.isNotEmpty()) words.addView(label("最近搜索", 22f))
        val historyLine = LinearLayout(context).apply { orientation = VERTICAL }
        val historyTitle = words.getChildAt(words.childCount - 1)
        val emptyHistory = label("输入剧名或拼音首字母，联想词会显示在这里", 16f).apply {
            visibility = if (history.isEmpty()) VISIBLE else GONE
        }
        words.addView(emptyHistory)
        words.addView(historyLine)
        history.distinct().take(8).forEachIndexed { index, value ->
            val recent =
                action(value) { submit(value) }
                    .apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        minHeight = maxOf(dp(76), (paint.fontSpacing * 2).toInt() + paddingTop + paddingBottom + dp(4))
                    }
            recents += recent
            if (index % 2 == 0) historyLine.addView(LinearLayout(context).apply { isBaselineAligned = false }, LayoutParams(-1, -2))
            (historyLine.getChildAt(historyLine.childCount - 1) as LinearLayout).addView(recent, LayoutParams(0, -2, 1f).apply { rightMargin = dp(10); bottomMargin = dp(10) })
        }
        suggestions.afterWords = { recents.firstOrNull() ?: resultEntry() ?: input }
        input.nextFocusUpId = input.id
        input.nextFocusDownId = keys.first().id
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        suggestions.keyboardEntry = { keys[lastKey] }
        suggestions.navigationChanged = {
            suggestionLabel.visibility = if (input.text.isNotBlank()) VISIBLE else GONE
            emptyHistory.visibility = if (history.isEmpty() && input.text.isBlank()) VISIBLE else GONE
            historyLine.visibility = if (input.text.isBlank()) VISIBLE else GONE
            if (history.isNotEmpty()) historyTitle.visibility = historyLine.visibility
            updateNavigation()
        }
        updateNavigation()
    }

    private fun suggestionsNavigation() { suggestions.updateNavigation() }

    fun middleEntry(): View? = suggestions.entry() ?: recents.firstOrNull()?.takeIf { input.text.isBlank() }

    fun entry(): View = if (showingResults) backButton else keys[lastKey]

    fun updateNavigation() {
        backButton.nextFocusDownId = resultEntry()?.id ?: backButton.id
        val right = middleEntry()?.id ?: input.id
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
            view.nextFocusUpId = recents.getOrNull(i - 2)?.id ?: view.id
            view.nextFocusDownId = recents.getOrNull(i + 2)?.id ?: view.id
            view.nextFocusLeftId = if (i % 2 == 0) keys[lastKey].id else recents[i - 1].id
            view.nextFocusRightId = if (i % 2 == 0) recents.getOrNull(i + 1)?.id ?: view.id else view.id
        }
        input.nextFocusRightId = right
        input.nextFocusUpId = input.id
        input.nextFocusDownId = keys.first().id
    }
}
