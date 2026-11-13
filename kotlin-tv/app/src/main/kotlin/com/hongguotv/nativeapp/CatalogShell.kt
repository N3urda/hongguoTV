// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView

internal interface HandlesHorizontalKeys

/** Fixed navigation rail. Content geometry never changes when the rail gains focus. */
internal class CatalogShell(context: Context, private val phone: (View) -> Unit = {}, private val navigate: (Int) -> Unit) : LinearLayout(context) {
    val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val destinations = listOf(0 to "首页", 8 to "随心看", 1 to "找剧", 7 to "搜索", 2 to "排行榜", 3 to "我的", 9 to "扫码", 6 to "设置")
    private val buttons = mutableListOf<TextView>()
    private var remembered: View? = null
    private var selectedDestination = 0
    val navigation: List<TextView>
        get() = (0..8).map { index -> buttons[destinations.indexOfFirst { it.first == if (index in 3..5) 3 else index }] }

    val hasNavigationFocus: Boolean get() = rail.hasFocus()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        val compact = resources.configuration.screenHeightDp < 480
        orientation = HORIZONTAL
        setBackgroundColor(TvStyle.background)
        rail.setPadding(dp(12), dp(if (compact) 12 else 20), dp(12), dp(if (compact) 12 else 16))
        rail.setBackgroundColor(TvStyle.background)
        rail.addView(ImageView(context).apply {
            setImageResource(R.drawable.brand_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "红果 TV"
            isFocusable = false
            (drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = false
        }, LayoutParams(-1, dp(if (compact) 40 else 56)).apply { bottomMargin = dp(if (compact) 12 else 24) })
        destinations.forEach { (index, label) ->
            if (index == 9) rail.addView(Space(context), LayoutParams(1, 0, 1f))
            val button = TextView(context).apply {
                id = View.generateViewId()
                text = label
                if (index == 9) contentDescription = "手机扫码控制"
                textSize = if (compact) 13f else 14f
                maxLines = 1
                isFocusable = true
                isClickable = true
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(if (compact) 6 else 10), dp(8), dp(if (compact) 6 else 10))
                compoundDrawablePadding = dp(9)
                setCompoundDrawablesWithIntrinsicBounds(TvGlyph(index, dp(18), TvStyle.muted), null, null, null)
                isSelected = index == selectedDestination
                fun paint() {
                    paintNavigation(this)
                }
                setOnFocusChangeListener { _, _ -> paint() }
                setOnClickListener { if (index == 9) phone(this) else if (index != selectedDestination) navigate(index) else restoreContent() }
                paint()
            }
            buttons += button
            rail.addView(button, LayoutParams(-1, dp(if (compact) 30 else 42)).apply { bottomMargin = dp(if (compact) 3 else 8) })
        }
        addView(rail, LayoutParams(dp(112), -1))
        body.setPadding(dp(24), dp(18), dp(24), dp(16))
        addView(body, LayoutParams(0, -1, 1f))
    }

    private fun paintNavigation(button: TextView) {
        val focused = button.hasFocus()
        val ink = if (focused) TvStyle.background else if (button.isSelected) TvStyle.accent else TvStyle.muted
        button.background = TvStyle.shape(context,
            if (focused) TvStyle.text else if (button.isSelected) TvStyle.raised else Color.TRANSPARENT,
            radius = 12)
        button.setTextColor(ink)
        button.typeface = if (button.isSelected || focused) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        (button.compoundDrawables[0] as? TvGlyph)?.let { it.ink = ink; it.invalidateSelf() }
    }

    fun select(index: Int) {
        selectedDestination = if (index in 3..5) 3 else index
        buttons.forEachIndexed { i, button ->
            button.isSelected = destinations[i].first == selectedDestination
            paintNavigation(button)
        }
    }

    fun clearBody() {
        remembered = null
        body.removeAllViews()
    }

    private fun inContent(view: View?): Boolean {
        var current = view
        while (current != null) {
            if (current === body) return true
            current = current.parent as? View
        }
        return false
    }

    override fun requestChildFocus(child: View, focused: View) {
        if (inContent(focused)) remembered = focused
        super.requestChildFocus(child, focused)
    }

    fun restoreContent(): Boolean {
        val target = remembered?.takeIf { inContent(it) && it.isShown && it.isFocusable }
            ?: body.getFocusables(FOCUS_FORWARD).firstOrNull()
        return target?.requestFocus() == true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focused = findFocus()
        val index = buttons.indexOf(focused)
        if (index >= 0 && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> buttons[(index - 1).coerceAtLeast(0)].requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN -> buttons[(index + 1).coerceAtMost(buttons.lastIndex)].requestFocus()
                KeyEvent.KEYCODE_DPAD_RIGHT -> restoreContent()
            }
            return true
        }
        // The rail is beside the page: vertical movement must never escape into it.
        // Keep GridView's own row traversal before applying the boundary check below.
        val vertical = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action == KeyEvent.ACTION_DOWN && vertical && inContent(focused) &&
            focused !is android.widget.AbsListView) {
            val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) FOCUS_UP else FOCUS_DOWN
            val next = focused?.focusSearch(direction)
            if (next != null && !inContent(next)) {
                android.view.FocusFinder.getInstance().findNextFocus(body, focused, direction)
                    ?.takeIf { inContent(it) }?.requestFocus()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
            inContent(focused) && focused !is android.widget.AbsListView && focused !is HandlesHorizontalKeys) {
            val next = focused?.focusSearch(FOCUS_LEFT)
            if (next == null || !inContent(next) || next === focused) {
                navigation[selectedDestination].requestFocus()
                return true
            }
        }
        val handled = super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && vertical && inContent(focused) && !inContent(findFocus())) {
            focused?.requestFocus()
            return true
        }
        return handled
    }
}
