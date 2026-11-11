// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hongguotv.core.RelatedWork
import kotlin.math.roundToInt

/** A remote-friendly, independently loaded strip below the episode grid. */
internal class RelatedWorksView(
    context: Context,
    private val upper: List<View>,
    private val loadCover: (ImageView, String) -> Unit,
    private val reserveLabel: (RelatedWork) -> String,
    private val open: (RelatedWork, View) -> Unit,
    private val reload: () -> Unit,
) : LinearLayout(context) {
    private var allDialog: AlertDialog? = null
    private var refreshButton: TextView? = null
    private val labels = mutableListOf<Pair<RelatedWork, TextView>>()

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 15f) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }

    init {
        orientation = VERTICAL
        setPadding(0, dp(14), 0, dp(12))
        loading()
    }

    private fun reset(status: String) {
        removeAllViews()
        labels.clear()
        val heading =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        heading.addView(label("相关作品", 22f), LayoutParams(0, -2, 1f))
        val refresh =
            label("刷新", 15f).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = TvStyle.card(context, false)
                setOnFocusChangeListener { view, focused ->
                    view.background = TvStyle.card(context, focused)
                }
                setOnClickListener { reload() }
                nextFocusUpId = upper.lastOrNull()?.id ?: id
            }
        refreshButton = refresh
        heading.addView(refresh)
        addView(heading)
        if (status.isNotBlank())
            addView(
                label(status, 13f).apply {
                    setTextColor(Color.LTGRAY)
                    setPadding(0, dp(8), 0, dp(8))
                }
            )
        upper.takeLast(10).forEach { it.nextFocusDownId = refresh.id }
    }

    fun loading() {
        reset("正在加载相关作品…")
    }

    fun failed() {
        reset("相关作品暂时无法加载，可按刷新重试；当前剧集仍可正常观看。")
    }

    fun show(rows: List<RelatedWork>) {
        reset(if (rows.isEmpty()) "暂无相关剧集" else "左右选择其他季；待上线作品可预约")
        if (rows.isEmpty()) return
        val allButton = label("全部季 / 相关作品 · ${rows.size} 项 ›").apply {
            id = View.generateViewId()
            isFocusable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = TvStyle.card(context, false)
            setOnFocusChangeListener { v, focused -> v.background = TvStyle.card(context, focused) }
            setOnClickListener { showAll(rows, this) }
        }
        addView(allButton)
        val scroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
            }
        val line = LinearLayout(context).apply { orientation = HORIZONTAL }
        scroll.addView(line)
        addView(scroll)
        val cards =
            rows.map { work ->
                val card =
                    LinearLayout(context).apply {
                        orientation = VERTICAL
                        id = View.generateViewId()
                        tag = work.series.id
                        isFocusable = true
                        isFocusableInTouchMode = true
                        contentDescription = work.series.title + "，" + work.label
                        setPadding(dp(5), dp(5), dp(5), dp(9))
                        background = TvStyle.card(context, false)
                    }
                val image =
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                val posterHeight = ((dp(142) - dp(10)) / TvStyle.POSTER_ASPECT).roundToInt()
                card.addView(image, LayoutParams(-1, posterHeight))
                loadCover(image, work.series.cover)
                card.addView(
                    label(work.series.title, 14f).apply {
                        maxLines = 2
                        minLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    LayoutParams(-1, -2),
                )
                card.addView(label(work.season, 12f))
                val status = label(if (work.upcoming) reserveLabel(work) else "已上线 · 查看剧集", 12f)
                card.addView(status)
                labels += work to status
                card.setOnClickListener { open(work, card) }
                card.setOnFocusChangeListener { view, focused ->
                    view.background = TvStyle.card(context, focused)
                    if (focused) {
                        loadCover(image, work.series.cover)
                        view.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, view.width, view.height),
                            false,
                        )
                    }
                }
                line.addView(card, LayoutParams(dp(142), -2).apply { rightMargin = dp(12) })
                card
            }
        cards.forEachIndexed { index, card ->
            card.nextFocusLeftId = cards.getOrNull(index - 1)?.id ?: card.id
            card.nextFocusRightId = cards.getOrNull(index + 1)?.id ?: card.id
            card.nextFocusUpId = allButton.id
            card.nextFocusDownId = card.id
        }
        refreshButton?.nextFocusDownId = allButton.id
        allButton.nextFocusUpId = refreshButton?.id ?: allButton.id
        allButton.nextFocusDownId = cards.first().id
        upper.takeLast(10).forEachIndexed { index, view ->
            view.nextFocusDownId = cards[minOf(index, cards.lastIndex)].id
        }
    }

    private fun showAll(rows: List<RelatedWork>, anchor: View) {
        allDialog?.dismiss()
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = TvVisual.panel(context, modal = true)
        }
        panel.addView(label("全部季 / 相关作品 · ${rows.size} 项", 20f))
        val grid = GridLayout(context).apply { columnCount = 4 }
        val cells = rows.map { work ->
            LinearLayout(context).apply {
                orientation = VERTICAL
                id = View.generateViewId()
                isFocusable = true
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = TvStyle.card(context, false)
                contentDescription = work.series.title + "，" + work.label
                tag = "all-${work.series.id}"
                val poster = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(poster, LayoutParams(-1, dp(164)))
                loadCover(poster, work.series.cover)
                addView(label(work.series.title, 13f).apply {
                    maxLines = 2
                    minLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(label(if (work.upcoming) reserveLabel(work) else work.label, 12f))
                setOnFocusChangeListener { v, focused ->
                    v.background = TvStyle.card(context, focused)
                    if (focused) loadCover(poster, work.series.cover)
                }
                setOnClickListener { allDialog?.dismiss(); open(work, anchor) }
            }.also { cell -> grid.addView(cell, GridLayout.LayoutParams().apply {
                width = dp(148); height = -2
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }) }
        }
        cells.forEachIndexed { i, cell ->
            cell.nextFocusLeftId = if (i % 4 > 0) cells[i - 1].id else cell.id
            cell.nextFocusRightId = if (i % 4 < 3) cells.getOrNull(i + 1)?.id ?: cell.id else cell.id
            cell.nextFocusUpId = cells.getOrNull(i - 4)?.id ?: cell.id
            cell.nextFocusDownId = cells.getOrNull(i + 4)?.id ?: cell.id
        }
        panel.addView(ScrollView(context).apply { addView(grid) }, LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(context).setView(panel).create()
        allDialog = dialog
        dialog.setOnDismissListener {
            if (allDialog === dialog) allDialog = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(dp(680), minOf(dp(420), (resources.displayMetrics.heightPixels * .85f).toInt()))
        cells.firstOrNull()?.requestFocus()
    }

    override fun onDetachedFromWindow() {
        allDialog?.dismiss()
        allDialog = null
        super.onDetachedFromWindow()
    }

    fun refreshLabels() {
        labels.forEach { (work, view) -> if (work.upcoming) view.text = reserveLabel(work) }
    }
}
