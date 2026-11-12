// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The navigation stays attached and focused while only the selected page body is replaced. */
internal class CatalogShell(context: Context, private val selectTab: (Int) -> Unit) : LinearLayout(context) {
    val body = LinearLayout(context).apply { orientation = VERTICAL }
    val navigation = mutableListOf<TextView>()
    private var selected = 0
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(context).apply { setImageResource(R.drawable.brand_mark); contentDescription = "红果 TV 像素小电视"; scaleType = ImageView.ScaleType.FIT_CENTER },
            LayoutParams(dp(48), dp(42)).apply { rightMargin = dp(9) })
        brand.addView(TextView(context).apply {
            text = "红果 TV"; textSize = if (widthDp < 800) 17f else 22f
            setTextColor(TvVisual.primary); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        header.addView(brand, LayoutParams(dp(if (widthDp < 800) 130 else 160), dp(44)))
        listOf("首页", "找剧", "排行榜", "收藏", "观看历史", "账户预约", "设置", "搜索").forEachIndexed { index, title ->
            val tab = TextView(context).apply {
                id = View.generateViewId(); text = title
                textSize = if (widthDp < 800) 11f else if (widthDp < 1000) 13f else 15f
                gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = true
                isClickable = true; maxLines = 1
                val horizontalPadding = dp(if (widthDp < 800) 4 else 9)
                setPadding(horizontalPadding, dp(10), horizontalPadding, dp(10))
                setOnClickListener { if (index != selected) selectTab(index) }
                setOnFocusChangeListener { _, _ -> paint(index) }
            }
            navigation += tab
            header.addView(tab, LayoutParams(0, dp(44), 1f))
        }
        // Keep persisted tab IDs stable while placing search next to discovery.
        val ordered = listOf(0, 1, 7, 2, 3, 4, 5, 6).map { navigation[it] }
        ordered.forEach { header.removeView(it) }
        ordered.forEach { header.addView(it, LayoutParams(0, dp(44), 1f)) }
        ordered.forEachIndexed { index, view ->
            view.nextFocusLeftId = ordered.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusRightId = ordered.getOrNull(index + 1)?.id ?: view.id
            view.nextFocusUpId = view.id
        }
        addView(header)
        addView(View(context).apply { setBackgroundColor(TvStyle.outline) },
            LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(6) })
        addView(body, LayoutParams(-1, 0, 1f))
        select(0)
    }

    fun select(index: Int) {
        selected = index
        navigation.indices.forEach(::paint)
    }

    fun clearBody() {
        body.removeAllViews()
        navigation.forEach { it.nextFocusDownId = View.NO_ID }
    }

    private fun paint(index: Int) {
        val view = navigation[index]
        view.isSelected = index == selected
        view.background = TvVisual.tab(context, view.hasFocus(), view.isSelected)
        view.setTextColor(if (view.isSelected) TvVisual.accent else if (view.hasFocus()) TvVisual.primary else TvStyle.muted)
        view.setTypeface(null, if (view.hasFocus() || view.isSelected) Typeface.BOLD else Typeface.NORMAL)
    }
}
