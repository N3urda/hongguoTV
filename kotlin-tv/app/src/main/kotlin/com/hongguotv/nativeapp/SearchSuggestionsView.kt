// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 搜索联想组件：连接输入变化与候选词展示，并处理异步结果及焦点选择。

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.hongguotv.core.RequestScope
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Suggestions have their own cancellable worker, independent of result pagination/navigation. */
internal class SearchSuggestionsView(
    context: Context,
    private val input: EditText,
    private val peers: List<View>,
    private val destination: () -> View?,
    private val fetch: (String) -> List<String>,
    private val search: (String) -> Unit,
    private val vertical: Boolean = false,
    private val exitHorizontalEdges: Boolean = false,
    private val grid: Boolean = false,
) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val line = LinearLayout(context)
    private val chips = mutableListOf<TextView>()
    private var pending: Runnable? = null
    private var job: Future<*>? = null
    private var scope: RequestScope? = null
    private var ticket = 0
    private var closed = false
    var navigationChanged: (() -> Unit)? = null
    var afterWords: () -> View? = destination

    fun entry(): View? = chips.firstOrNull()
    fun values(): List<String> = chips.map { it.text.toString() }
    fun restore(values: List<String>) = render(values)
    fun viewAt(index: Int): View? = chips.getOrNull(index)
    fun selectedIndex(): Int = chips.indexOfFirst { it.hasFocus() }
    fun focusAt(index: Int) { chips.getOrNull(index)?.requestFocus() }
    var keyboardEntry: () -> View = { peers.lastOrNull() ?: input }

    private val watcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                schedule(s?.toString().orEmpty())
            }
        }

    init {
        isFocusable = false
        line.orientation = if (vertical || grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (grid) addView(line, LayoutParams(-1, -2))
        else addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(line) }, LayoutParams(-1, -2))
        visibility = GONE
        input.addTextChangedListener(watcher)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun schedule(value: String) {
        cancel()
        val keyword = value.trim()
        if (closed || keyword.isEmpty()) return
        val expected = ticket
        val task = Runnable {
            pending = null
            val request = RequestScope()
            scope = request
            job =
                worker.submit {
                    val result = runCatching { request.run { fetch(keyword) } }
                    main.post {
                        if (closed || ticket != expected || input.text.toString().trim() != keyword)
                            return@post
                        render(result.getOrDefault(emptyList()), result.isFailure)
                    }
                }
        }
        pending = task
        main.postDelayed(task, 350)
    }

    private fun render(values: List<String>, failed: Boolean = false) {
        // Replacing suggestions must never leave focus on a detached chip.
        val focusedValue = chips.firstOrNull { it.hasFocus() }?.text?.toString()
        line.removeAllViews()
        chips.clear()
        values.distinct().take(8).forEachIndexed { index, value ->
            val chip =
                TextView(context).apply {
                    id = View.generateViewId()
                    text = value
                    textSize = if (grid) 18f else 13f
                    maxLines = if (vertical || grid) 2 else 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (!grid) maxWidth = dp(300)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    includeFontPadding = true
                    isFocusable = true
                    isClickable = true
                    setTextColor(TvStyle.text)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    minHeight = if (grid) maxOf(dp(76), (paint.fontSpacing * 2).toInt() + paddingTop + paddingBottom + dp(4)) else dp(36)
                    background = if (grid) TvStyle.shape(context, TvStyle.surface, TvStyle.outline) else TvStyle.card(context, false)
                    setOnFocusChangeListener { _, focus ->
                        background = if (grid) TvStyle.shape(context, if (focus) TvStyle.text else TvStyle.surface, if (focus) TvStyle.text else TvStyle.outline) else TvStyle.card(context, focus)
                        setTextColor(if (grid && focus) TvStyle.background else TvStyle.text)
                    }
                    setOnClickListener { search(value) }
                }
            chips += chip
            if (grid) {
                if (index % 2 == 0) line.addView(LinearLayout(context).apply { isBaselineAligned = false }, LinearLayout.LayoutParams(-1, -2))
                (line.getChildAt(line.childCount - 1) as LinearLayout).addView(chip,
                    LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10); bottomMargin = dp(10) })
            } else line.addView(chip, LinearLayout.LayoutParams(if (vertical) dp(148) else -2, -2))
        }
        if (failed)
            line.addView(
                TextView(context).apply {
                    text = "联想暂不可用，可直接按搜索"
                    textSize = 12f
                    setTextColor(TvStyle.muted)
                    setPadding(0, dp(7), 0, dp(7))
                }
            )
        visibility = if (chips.isNotEmpty() || failed) VISIBLE else GONE
        scrollTo(0, 0)
        updateNavigation()
        if (focusedValue != null) {
            (chips.firstOrNull { it.text.toString() == focusedValue } ?: chips.firstOrNull() ?: keyboardEntry()).requestFocus()
        }
    }

    fun updateNavigation() {
        val target = destination()?.id ?: input.id
        if (grid) {
            chips.forEachIndexed { i, chip ->
                chip.nextFocusLeftId = if (i % 2 == 0) keyboardEntry().id else chips[i - 1].id
                chip.nextFocusRightId = if (i % 2 == 0) chips.getOrNull(i + 1)?.id ?: chip.id else chip.id
                chip.nextFocusUpId = chips.getOrNull(i - 2)?.id ?: chip.id
                chip.nextFocusDownId = chips.getOrNull(i + 2)?.id ?: chip.id
            }
            navigationChanged?.invoke()
            return
        }
        if (vertical) {
            chips.forEachIndexed { i, chip ->
                chip.nextFocusUpId = chips.getOrNull(i - 1)?.id ?: input.id
                chip.nextFocusDownId = chips.getOrNull(i + 1)?.id ?: afterWords()?.id ?: target
                chip.nextFocusLeftId = peers.lastOrNull()?.id ?: input.id
                chip.nextFocusRightId = target
            }
            navigationChanged?.invoke()
            return
        }
        (peers + input).forEach { it.nextFocusDownId = chips.firstOrNull()?.id ?: target }
        chips.forEachIndexed { i, chip ->
            chip.nextFocusUpId = input.id
            chip.nextFocusDownId = target
            chip.nextFocusLeftId = chips.getOrNull(i - 1)?.id ?: if (exitHorizontalEdges) peers.lastOrNull()?.id ?: input.id else chip.id
            chip.nextFocusRightId = chips.getOrNull(i + 1)?.id ?: if (exitHorizontalEdges) target else chip.id
        }
        if (exitHorizontalEdges) navigationChanged?.invoke()
    }

    fun cancel() {
        ticket++
        pending?.let(main::removeCallbacks)
        pending = null
        scope?.cancel()
        scope = null
        job?.cancel(true)
        job = null
        render(emptyList())
    }

    fun close() {
        if (closed) return
        closed = true
        input.removeTextChangedListener(watcher)
        cancel()
        worker.shutdownNow()
    }
}
