// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.hongguotv.core.*
import java.util.concurrent.Executors

/** Keeps already verified works visible when the remote directory cannot be searched. */
internal class ProducerWorksDialog(
    private val activity: Activity,
    private val repository: ContentRepository,
    private val producer: Producer,
    private val current: Series,
    private val anchor: View,
    private val loadCover: (ImageView, String) -> Unit,
    private val openSeries: (Series) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scope = RequestScope()
    private val rows = linkedMapOf(current.id to current)
    private var page = 1
    private var closed = false
    private var busy = false
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val status = label("正在读取制作方作品…")
    private val more = Button(activity).apply { text = "正在加载…"; isEnabled = false }
    private val dialog: AlertDialog
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String) = TextView(activity).apply {
        text = value; textSize = 15f; setTextColor(TvStyle.text)
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    init {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(label("制作方 · ${producer.name}").apply { textSize = 21f })
            addView(status)
            addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(more)
        }
        dialog = AlertDialog.Builder(activity).setView(panel).create()
        dialog.setOnDismissListener {
            closed = true
            scope.cancel()
            worker.shutdownNow()
            main.removeCallbacksAndMessages(null)
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        more.setOnClickListener { loadPage() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(TvVisual.panel(activity, modal = true))
        dialog.window?.setLayout(minOf(dp(640), (activity.resources.displayMetrics.widthPixels * .9f).toInt()),
            (activity.resources.displayMetrics.heightPixels * .85f).toInt())
        render(listOf(current))
        list.getChildAt(0)?.requestFocus()
        worker.execute {
            val known = runCatching { scope.run { repository.relatedProducerWorks(producer, current) } }
            main.post {
                if (!closed) {
                    known.getOrNull()?.let(::render)
                    loadPage()
                }
            }
        }
    }

    fun close() = dialog.dismiss()

    private fun render(items: List<Series>) {
        for (series in items) {
            if (list.findViewWithTag<View>(series.id) != null) continue
            rows[series.id] = series
            val card = LinearLayout(activity).apply {
                id = View.generateViewId(); tag = series.id
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isFocusableInTouchMode = true
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = TvStyle.card(activity, false)
                contentDescription = series.title
            }
            val poster = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(poster, LinearLayout.LayoutParams(dp(63), dp(84)))
            card.addView(label(series.title + "\n" + series.badge).apply { maxLines = 3 }, LinearLayout.LayoutParams(0, -2, 1f))
            loadCover(poster, series.cover)
            card.setOnFocusChangeListener { v, focused ->
                v.background = TvStyle.card(activity, focused)
                if (focused) loadCover(poster, series.cover)
            }
            card.setOnClickListener { close(); openSeries(series) }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        status.text = "已确认 ${rows.size} 部作品"
    }

    private fun loadPage() {
        if (closed || busy) return
        busy = true; more.isEnabled = false; more.text = "正在检索…"
        worker.execute {
            val result = runCatching { scope.run { repository.producerWorks(producer, page) } }
            main.post {
                if (closed) return@post
                busy = false
                result.fold({ next ->
                    render(next.items)
                    page++
                    more.isEnabled = next.hasMore
                    more.text = if (next.hasMore) "加载更多作品" else "已显示当前可检索作品"
                    status.text = "已确认 ${rows.size} 部作品 · 以平台当前检索结果为准"
                }, {
                    status.text = "已确认 ${rows.size} 部作品 · 更多作品暂时无法读取"
                    more.text = "重试查找更多作品"; more.isEnabled = true
                })
            }
        }
    }
}
