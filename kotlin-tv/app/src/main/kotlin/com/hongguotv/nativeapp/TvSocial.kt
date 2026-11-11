// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.media3.common.Player
import com.hongguotv.core.*
import java.util.concurrent.Executors
import org.json.JSONObject

/** Owns only anonymous social reads. Playback and account writes are independent. */
class TvSocial(
    private val activity: Activity,
    private val player: () -> Player?,
    private val identity: () -> Pair<String, String>?,
    private val readComments: (String, String, String) -> JSONObject = AppCommentPages()::read,
    private val readReviews: (String, String) -> JSONObject =
        PublicComments(okhttp3.OkHttpClient())::read,
) {
    private val emoji = CommentEmoji(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val timed = TimedComments()
    private val prefs = activity.getSharedPreferences("social", 0)
    private var active = false
    private var key: Pair<String, String>? = null
    private var scope: RequestScope? = null
    private var commentScope: RequestScope? = null
    private var generation = 0
    private var dialog: Dialog? = null
    private var overlay: BulletView? = null
    private var loading = false
    private var failedAt = 0L
    private var timedStatus = "尚未读取"
    private val pages = linkedMapOf<Long, JSONObject>()

    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()

    private val tick =
        object : Runnable {
            override fun run() {
                if (!active) return
                val next = identity()
                if (next != key) {
                    reset()
                    key = next
                }
                val p = player()
                overlay?.running = p?.isPlaying == true
                overlay?.invalidate()
                if (
                    p != null &&
                        next != null &&
                        prefs.getBoolean("enabled", true) &&
                        !loading &&
                        p.duration in 1..86400000 &&
                        p.currentPosition < p.duration &&
                        (failedAt == 0L ||
                            android.os.SystemClock.elapsedRealtime() - failedAt > 15000)
                ) {
                    TimedCommentWindows.next(pages.values.toList(), p.currentPosition, p.duration)
                        ?.let { loadTimed(next, it, p.duration) }
                }
                main.postDelayed(this, 500)
            }
        }

    fun attach(root: android.widget.FrameLayout) {
        overlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        overlay = BulletView().also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    fun foreground(value: Boolean) {
        active = value
        main.removeCallbacks(tick)
        if (value) main.post(tick)
        else {
            reset()
            overlay?.running = false
            overlay?.invalidate()
        }
    }

    fun reset() {
        generation++
        scope?.cancel()
        scope = null
        commentScope?.cancel()
        commentScope = null
        loading = false
        failedAt = 0
        pages.clear()
        timedStatus = "尚未读取"
        overlay?.setRows(emptyList())
        dialog?.dismiss()
        dialog = null
        key = null
    }

    fun destroy() {
        foreground(false)
        worker.shutdownNow()
    }

    private fun loadTimed(ids: Pair<String, String>, start: Long, duration: Long) {
        loading = true
        val ticket = generation
        val request = RequestScope()
        scope = request
        // 在主线程取相邻区间游标，工作线程不读取正在变化的页面缓存。
        val cursor = pages.values.filter { it.optLong("nextMS") == start }
            .maxByOrNull { it.optLong("startMS") }?.optString("nextCursor").orEmpty()
        worker.submit {
            try {
                val page = request.run { timed.read(ids.first, ids.second, start, duration, cursor) }
                main.post {
                    if (ticket != generation || !active) return@post
                    loading = false
                    pages[start] = page
                    timedStatus = "最近区间 ${start / 1000}–${page.getLong("nextMS") / 1000} 秒：接口 ${page.optInt("rawCount", -1)} 条，有效 ${page.getJSONArray("items").length()} 条"
                    while (pages.size > 128) pages.remove(
                        pages.keys.maxBy { kotlin.math.abs(it - start) }
                    )
                    val rows =
                        pages.values.flatMap { value ->
                            val a = value.getJSONArray("items")
                            (0 until a.length()).map { a.getJSONObject(it) }
                        }
                    overlay?.setRows(rows)
                }
            } catch (_: Exception) {
                main.post {
                    if (ticket == generation) {
                        loading = false
                        failedAt = android.os.SystemClock.elapsedRealtime()
                        timedStatus = "读取失败，等待重试"
                    }
                }
            }
        }
    }

    private fun label(text: String, size: Float = 18f) =
        TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(TvStyle.text)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }

    private fun button(text: String, action: () -> Unit) =
        label(text).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            minHeight = dp(48)
            background = TvStyle.card(activity, false)
            setOnFocusChangeListener { _, focus -> background = TvStyle.card(activity, focus) }
            setOnClickListener { action() }
        }

    fun settings(anchor: View) {
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = TvVisual.panel(activity)
            }
        body.addView(label("弹幕设置", 22f))
        val refreshers = mutableListOf<() -> Unit>()
        fun option(title: () -> String, parent: LinearLayout = body, action: () -> Unit) {
            lateinit var b: TextView
            b =
                button(title()) {
                    action()
                    refreshers.forEach { it() }
                    overlay?.setRows(
                        pages.values.flatMap {
                            val a = it.getJSONArray("items")
                            (0 until a.length()).map { i -> a.getJSONObject(i) }
                        }
                    )
                    overlay?.invalidate()
                }
            refreshers += { b.text = title() }
            parent.addView(b)
        }
        fun section(title: String): LinearLayout {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(10), 0, 0, 0)
            }
            lateinit var opener: TextView
            opener = button("$title ▸") {
                val expanded = content.visibility != View.VISIBLE
                content.visibility = if (expanded) View.VISIBLE else View.GONE
                opener.text = "$title ${if (expanded) "▾" else "▸"}"
            }
            body.addView(opener)
            body.addView(content)
            return content
        }
        option({ "弹幕：${if(prefs.getBoolean("enabled",true)) "开" else "关"}" }) {
            prefs.edit().putBoolean("enabled", !prefs.getBoolean("enabled", true)).apply()
        }
        option({ "字号：${prefs.getInt("font",24)}" }) {
            val n = prefs.getInt("font", 24)
            prefs.edit().putInt("font", if (n >= 32) 20 else n + 4).apply()
        }
        option({
            val lanes = prefs.getInt("lanes", 0)
            "密度：${when (lanes) { 4 -> "少"; 0 -> "标准（自动）"; 12 -> "多"; else -> "自定义 $lanes 行" }}"
        }) {
            val steps = listOf(4, 0, 12)
            val current = steps.indexOf(prefs.getInt("lanes", 0))
            prefs.edit().putInt("lanes", steps[(current + 1) % steps.size]).apply()
        }
        option({ "区域：${when (prefs.getString("area", "top")) { "middle" -> "中部"; "bottom" -> "下部"; "full" -> "全屏"; else -> "上部" }}" }) {
            val order = listOf("top", "middle", "bottom", "full")
            val next = order[(order.indexOf(prefs.getString("area", "top")) + 1) % order.size]
            prefs.edit().putString("area", next).apply()
        }
        option({ "相同弹幕合并：${if (prefs.getBoolean("merge", true)) "开" else "关"}" }) {
            prefs.edit().putBoolean("merge", !prefs.getBoolean("merge", true)).apply()
        }
        val advanced = section("更多设置")
        option({ "精确行数：${if (prefs.getInt("lanes", 0) > 0) "${prefs.getInt("lanes", 0)} 行" else "自动"}" }, advanced) {
            // 0 表示按屏幕高度自动计算；其余为固定值，避免旧版本只有 1–4 行可选。
            val steps = listOf(0, 4, 6, 8, 10, 12)
            val index = steps.indexOf(prefs.getInt("lanes", 0)).let { if (it >= 0) it else 0 }
            prefs.edit().putInt("lanes", steps[(index + 1) % steps.size]).apply()
        }
        option({ "不透明度：${prefs.getInt("alpha", 85)}%" }, advanced) {
            val steps = listOf(60, 75, 85, 100)
            val next = steps[(steps.indexOf(prefs.getInt("alpha", 85)) + 1) % steps.size]
            prefs.edit().putInt("alpha", next).apply()
        }
        option({ "速度：${if(prefs.getInt("travel",12000)==12000) "慢速（12 秒）" else "标准（9 秒）"}" }, advanced) {
            prefs
                .edit()
                .putInt("travel", if (prefs.getInt("travel", 12000) == 12000) 9000 else 12000)
                .apply()
        }
        val troubleshooting = section("弹幕排查")
        troubleshooting.addView(
            button("重新读取弹幕") {
                timed.clearCache()
                reset()
            }
        )
        troubleshooting.addView(button("查看读取诊断") {
            val counts = pages.values.flatMap { page ->
                val rows = page.getJSONArray("items")
                (0 until rows.length()).map { rows.getJSONObject(it).optString("id") }
            }.distinct().size
            val report = "弹幕诊断 D3\n$timedStatus\n缓存 ${pages.size} 段，去重 $counts 条\n" +
                (overlay?.diagnostics() ?: "画布尚未创建")
            android.app.AlertDialog.Builder(activity).setTitle("弹幕读取诊断")
                .setMessage(report).setPositiveButton("关闭", null).show()
        })
        body.addView(label("真实时间轴弹幕；无内容时不使用普通评论代替。", 14f))
        body.addView(button("关闭") { d.dismiss() })
        d.setContentView(ScrollView(activity).apply { addView(body) })
        d.setOnDismissListener { if (anchor.isAttachedToWindow) anchor.requestFocus() }
        d.show()
        d.window?.setLayout(dp(440), -2)
        body.getChildAt(1).requestFocus()
    }

    fun showComments(anchor: View, target: Pair<String, String>? = null) {
        val ids = target ?: identity() ?: return
        dialog?.dismiss()
        commentScope?.cancel()
        key = ids
        val ticket = ++generation
        // The timed read belongs to the old generation as well.
        scope?.cancel()
        loading = false
        val d = Dialog(activity)
        dialog = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var cursor = ""
        var more = true
        var busy = false
        var reviewMode = false
        var disposed = false
        var pageGeneration = 0
        val seen = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = TvVisual.panel(activity)
            }
        body.addView(label("评论与剧评", 22f))
        val tabs = LinearLayout(activity)
        body.addView(tabs)
        val status = label("匿名读取 · 点赞数量只读", 14f)
        body.addView(status)
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        lateinit var load: TextView
        lateinit var read: () -> Unit
        fun addRow(row: JSONObject) {
            val created = row.optLong("created")
            val date =
                if (created > 0)
                    java.text
                        .SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                        .format(java.util.Date(created * 1000))
                else ""
            val author = row.optString("author").ifBlank { "红果用户" }
            val content = row.optString("content")
            val card =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(dp(12), dp(10), dp(12), dp(12))
                    background = TvStyle.card(activity, false)
                    contentDescription = "$author，$content"
                    setOnFocusChangeListener { _, focused ->
                        background = TvStyle.card(activity, focused)
                    }
                }
            card.addView(
                label("$author  ·  赞 ${row.optLong("likes")}  ·  $date", 13f).apply {
                    setTextColor(TvStyle.muted)
                    setPadding(0, 0, 0, dp(8))
                }
            )
            card.addView(
                label("", 20f).apply {
                    text = emoji.render(content, textSize * 1.2f)
                    setPadding(0, 0, 0, 0)
                    setLineSpacing(dp(6).toFloat(), 1f)
                }
            )
            card.setOnClickListener {
                val expanded =
                    android.app.AlertDialog.Builder(activity)
                        .setTitle(author)
                        .setMessage(emoji.render(content, dp(24).toFloat()))
                        .setPositiveButton("关闭", null)
                        .create()
                expanded.setOnDismissListener { if (card.isAttachedToWindow) card.requestFocus() }
                expanded.show()
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        read = {
            if (!busy && more && !disposed) {
                busy = true
                load.text = "正在读取…"
                val sent = cursor
                val mode = reviewMode
                val batch = pageGeneration
                val request = RequestScope()
                commentScope = request
                worker.submit {
                    try {
                        val page =
                            request.run {
                                if (mode)
                                    readReviews(ids.first, ids.second).getJSONObject("reviews")
                                else readComments(ids.first, ids.second, sent)
                            }
                        main.post {
                            if (
                                disposed ||
                                    ticket != generation ||
                                    batch != pageGeneration ||
                                    mode != reviewMode
                            )
                                return@post
                            busy = false
                            val previousScroll = scroll.scrollY
                            val rows = page.getJSONArray("items")
                            for (i in 0 until rows.length()) {
                                val row = rows.getJSONObject(i)
                                if (seen.add(row.getString("id"))) addRow(row)
                            }
                            scroll.post { if (!disposed) scroll.scrollTo(0, previousScroll) }
                            more = !mode && page.getBoolean("hasMore")
                            if (more) {
                                val next = page.getString("nextCursor")
                                if (!cursors.add(next)) {
                                    more = false
                                    status.text = "游标重复，停止自动加载；可重新打开重试。"
                                }
                                cursor = next
                            }
                            status.text =
                                if (mode) "官网节选 ${seen.size} 条 / 共 ${page.optLong("total")} 条"
                                else "已加载 ${seen.size} 条 / 共 ${page.optLong("total")} 条"
                            load.text = if (more) "加载更多评论" else if (mode) "官网剧评节选已读完" else "已到末尾"
                        }
                    } catch (_: Exception) {
                        main.post {
                            if (
                                !disposed &&
                                    ticket == generation &&
                                    batch == pageGeneration &&
                                    mode == reviewMode
                            ) {
                                busy = false
                                load.text = "加载失败，按确认重试"
                                status.text = "保留已有评论，重试使用原游标"
                            }
                        }
                    }
                }
            }
        }
        fun select(mode: Boolean) {
            pageGeneration++
            commentScope?.cancel()
            reviewMode = mode
            busy = false
            cursor = ""
            more = true
            seen.clear()
            cursors.clear()
            list.removeAllViews()
            read()
        }
        tabs.addView(button("本集评论") { select(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(button("整剧剧评") { select(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        load = button("加载更多评论") { read() }
        body.addView(load)
        body.addView(label("上下浏览，到底继续加载。发送、回复未接入。", 13f))
        body.addView(button("关闭") { d.dismiss() })
        scroll.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            if (!reviewMode && y > 0 && y + scroll.height >= list.height - dp(80)) read()
        }
        d.setContentView(body)
        d.setOnDismissListener {
            disposed = true
            commentScope?.cancel()
            if (dialog === d) dialog = null
            if (anchor.isAttachedToWindow) anchor.requestFocus()
        }
        d.show()
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END)
            setDimAmount(0f)
            setLayout(
                minOf(dp(500), (activity.resources.displayMetrics.widthPixels * .65).toInt()),
                -1,
            )
        }
        tabs.getChildAt(0).requestFocus()
        read()
    }

    private inner class BulletView : View(activity) {
        var running = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }


        /** 轨道与文本在 setRows 时算好；绘制只做坐标换算，避免每帧重新分配。 */
        private var scheduled: List<ScheduledDanmaku> = emptyList()

        private fun areaTop(): Int =
            when (prefs.getString("area", "top")) {
                "middle" -> dp(220)
                "bottom" -> dp(420)
                "full" -> dp(65)
                else -> dp(65)
            }

        private fun areaHeight(): Int {
            val top = areaTop()
            val full = height - top
            return when (prefs.getString("area", "top")) {
                "middle", "bottom" -> (full * 0.6f).toInt()
                else -> full
            }
        }

        fun setRows(values: List<JSONObject>) {
            val travel = prefs.getInt("travel", 12000)
            val textSize = dp(prefs.getInt("font", 24))
            val configured = prefs.getInt("lanes", 0)
            val lanes =
                if (configured > 0) configured
                else DanmakuLayout.laneCountFor(areaHeight(), textSize, 0)
            val items =
                values.mapNotNull {
                    val text = it.optString("text").trim()
                    val at = it.optLong("timeMS", -1)
                    if (text.isEmpty() || at < 0) null
                    else DanmakuItem(it.optString("id"), text, at)
                }
            scheduled =
                DanmakuLayout.assign(
                    items = items,
                    lanes = lanes,
                    travelMs = travel,
                    merge = prefs.getBoolean("merge", true),
                )
            invalidate()
        }

        fun diagnostics(): String {
            val now = player()?.currentPosition ?: 0
            val visible = scheduled.count { now >= it.startMs && now < it.endMs }
            return "合并排程 ${scheduled.size} 条，当前时间范围内 $visible 条\n播放 ${now / 1000} 秒，画布 ${width}×${height}"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val p = player() ?: return
            if (!active || !prefs.getBoolean("enabled", true)) return
            val now = p.currentPosition
            val travel = prefs.getInt("travel", 12000).coerceAtLeast(1)
            paint.textSize = dp(prefs.getInt("font", 24)).toFloat()
            val alpha = (prefs.getInt("alpha", 85).coerceIn(20, 100) * 255 / 100)
            val lineHeight = (paint.textSize + dp(12)).toInt()
            val top = areaTop()
            // 只绘制当前有效的弹幕，不因缓存总量大而按索引丢弃。
            for (item in scheduled) {
                if (now < item.startMs || now >= item.endMs) continue
                val x =
                    width -
                        (width + paint.measureText(item.text)) *
                            (now - item.startMs).toFloat() / travel
                val y = (top + item.lane * lineHeight).toFloat()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.BLACK
                paint.alpha = alpha
                canvas.drawText(item.text, x, y, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.alpha = alpha
                canvas.drawText(item.text, x, y, paint)
            }
            if (running && isAttachedToWindow) postInvalidateOnAnimation()
        }
    }
}
