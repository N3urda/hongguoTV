// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuHighlight
import com.hongguotv.core.DanmakuMode
import com.hongguotv.core.DanmakuRepeat
import com.hongguotv.core.DanmakuSpeed
import com.hongguotv.core.planHighlights
import com.hongguotv.core.repeatGroups

/**
 * 压在播放画面上的弹幕层（**纯文本版**：不含表情图）。
 *
 * 数据来自 [com.hongguotv.core.ContentRepository.danmaku]，就是「原 App」播放时看到的那一份。
 * 这里只负责画：布局（哪一条落在哪一轨、什么时候进画）在 [relayout] 里一次性算好，
 * 每帧只按当前播放位置求 x，不再排序、不再测量，低端设备上也能跟上。
 *
 * 四种显示方式由 [DanmakuMode] 决定：
 * - [DanmakuMode.SINGLE] 只显一行：整屏只有一条轨道，多密也不会上下叠字；
 * - [DanmakuMode.TOP] 上方显示：只在屏幕最上面四分之一内滚动，贴着顶边往下铺；
 * - [DanmakuMode.FULL] 全屏显示：整幅画面从上到下都是弹幕轨道；
 * - [DanmakuMode.HIDDEN] 隐藏弹幕：整层不可见，也不参与绘制。
 *
 * **不叠字的做法**：
 * 1. 同一条轨道上，后来的那条要等前一条的**尾部离开画面右边**才能进画，
 *    因此后一条永远追不上前一条；
 * 2. 所有弹幕**同速**（[DanmakuSpeed] 只改整体速度，不改「谁快谁慢」），宽度不同的两条
 *    之间也不会出现追尾——这是「提高速度不增加碰撞」的关键；
 * 3. 轨道高度按**字体实际高度 + 描边**算，不按字号乘一个系数，大字体下相邻两轨的字也不会碰在一起；
 * 4. 排不下的时候先换轨道（挑最早空出来的那条），所有轨道都排不下才往后延迟；
 *    延迟超过 [MAX_DELAY_MS] 就跳过这一条，不做强行叠加。
 *
 * **重复弹幕聚合**：整集数据先按内容聚合成一组一组（[repeatGroups]），
 * 同一组只画一次、文案带数量标记；其中数量最多的那几组按 [planHighlights] 排好的次序
 * 上屏幕中央突出显示（同一时间只有一条），中央那一条不再进普通轨道。
 *
 * 位置与播放状态都由宿主（MainActivity）通过 [positionMillis] / [playing] 提供，
 * 弹幕层自己不做时钟，暂停、跳转、倍速都不会和播放器脱节。
 */
internal class DanmakuView(context: Context) : View(context) {
    private companion object {
        /**
         * 横穿一屏的基准时长（秒）。
         *
         * 「标准」档按它算速度，其余档位在这个基准上乘系数（见 [DanmakuSpeed.factor]）：
         * 速度 = 屏宽 / 这个基准。屏幕上的弹幕因此跟得上播放进度，同一屏能过更多条。
         */
        const val BASE_SCROLL_SECONDS = 6f
        /** 全屏弹幕：顶部标题条之下才开始铺轨道，弹幕不压剧名。 */
        const val TOP_INSET_DP = 104
        /** 上方弹幕：整层只占屏幕高度的这一份，其余留白。 */
        const val TOP_MODE_SHARE = 0.25f
        /** 上方弹幕 / 只显一行离顶边留的安全边距，躲开电视过扫描。 */
        const val TOP_MODE_INSET_DP = 20
        /** 底部控制层之上收住，弹幕不压进度条。 */
        const val BOTTOM_INSET_DP = 120
        /** 单帧最多画多少条，给低端 GPU 一个上限。 */
        const val MAX_LINES_PER_FRAME = 160
        /** 同一轨道上前后两条之间至少留这么宽（按字号算），字与字不贴着。 */
        const val LANE_GAP_TEXT = 0.9f
        /** 相邻轨道之间至少留这么高（按字号算）：描边算在这一档里。 */
        const val LANE_GAP_VERTICAL = 0.35f
        /** 一条弹幕最多允许比自己的时间点晚多久出现；再晚就跳过，不做强行叠加。 */
        const val MAX_DELAY_MS = 4_000L
        /** 中央突出显示的字号倍率与底板留白（dp）。 */
        const val HIGHLIGHT_SCALE = 1.25f
        const val HIGHLIGHT_PAD_DP = 13
    }

    /** 一条已经排好轨道的普通弹幕：起点（毫秒）、横穿时长（毫秒）、整条宽度、所在轨道、所属聚合组。 */
    private class Line(val startMs: Long, val durationMs: Long, val width: Float, val lane: Int, val group: DanmakuRepeat)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3000000.toInt()
        style = Paint.Style.STROKE
        typeface = Typeface.DEFAULT_BOLD
        strokeJoin = Paint.Join.ROUND
    }
    /** 中央突出显示的两支笔：字号比普通弹幕大一档，其余与普通弹幕同一套。 */
    private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hlEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        typeface = Typeface.DEFAULT_BOLD
        strokeJoin = Paint.Join.ROUND
    }
    /** 中央那一块的底板：弹幕密集时后面的字幕会从它下面穿过，给一层半透明底才读得清。 */
    private val plate = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }

    private var items: List<Danmaku> = emptyList()
    private var groups: List<DanmakuRepeat> = emptyList()
    private var highlights: List<com.hongguotv.core.DanmakuHighlightRound> = emptyList()
    private val lines = mutableListOf<Line>()
    private val laneFreeAt = mutableListOf<Long>()
    private var laneCount = 0
    private var laneHeight = 0f
    private var laidOutFor = ""

    var mode: DanmakuMode = DanmakuMode.FULL
    var speed: DanmakuSpeed = DanmakuSpeed.NORMAL
    var textScale: Float = 1f
    var textAlpha: Float = 1f
    /** 宿主提供的当前播放位置（毫秒）；每帧取一次，seek/倍速天然跟手。 */
    var positionMillis: () -> Long = { 0L }
    /** 宿主提供的播放状态：暂停时画面停住但不消失，动画循环也随之停。 */
    var playing: () -> Boolean = { false }

    init {
        // 弹幕层只是压在画面上的一层，不吃点击、不抢焦点。
        isClickable = false
        isFocusable = false
        setOnTouchListener { _, _ -> false }
    }

    /** 换一整集数据：重新聚合、重新排轨道。 */
    fun setDanmaku(next: List<Danmaku>) {
        items = next
        groups = repeatGroups(next)
        highlights = planHighlights(groups)
        laidOutFor = ""
        requestLayout()
        invalidate()
    }

    /** seek / 换集后调用：轨道与当前位置脱节时重排。 */
    fun resetCursor() {
        laidOutFor = ""
        invalidate()
    }

    /** 字号 / 透明度 / 速度 / 显示方式改了之后调用。 */
    fun restyle() {
        laidOutFor = ""
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        laidOutFor = ""
    }

    private fun baseTextSize(): Float {
        val density = resources.displayMetrics.density
        return 15f * density * textScale.coerceIn(DanmakuStyleRange.MIN, DanmakuStyleRange.MAX)
    }

    private fun relayout(width: Int, height: Int) {
        val signature = "$width:$height:${mode.storedValue}:${speed.storedValue}:$textScale:${groups.size}"
        if (signature == laidOutFor) return
        laidOutFor = signature
        lines.clear()
        if (groups.isEmpty() || width <= 0 || height <= 0 || mode == DanmakuMode.HIDDEN) {
            laneCount = 0
            return
        }
        val textSize = baseTextSize()
        fill.textSize = textSize
        edge.textSize = textSize
        edge.strokeWidth = (textSize / 11f).coerceAtLeast(1f)
        val density = resources.displayMetrics.density
        // 顶部留白：全屏模式躲开标题条；上方 / 单行模式只留电视过扫描的安全边距。
        val topInset = when (mode) {
            DanmakuMode.FULL -> TOP_INSET_DP * density
            else -> TOP_MODE_INSET_DP * density
        }
        // 可用高度：上方模式只占上面的四分之一，其余模式到底部控制层之上收住。
        val usable = when (mode) {
            DanmakuMode.TOP -> height * TOP_MODE_SHARE
            DanmakuMode.SINGLE -> height - topInset - BOTTOM_INSET_DP * density
            else -> height - topInset - BOTTOM_INSET_DP * density
        }.coerceAtLeast(textSize)
        laneHeight = textSize * (1f + LANE_GAP_VERTICAL)
        laneCount = when (mode) {
            DanmakuMode.SINGLE -> 1
            else -> (usable / laneHeight).toInt().coerceAtLeast(1)
        }
        laneFreeAt.clear()
        repeat(laneCount) { laneFreeAt += Long.MIN_VALUE }
        // 所有弹幕同速：每毫秒走多少像素只由屏宽与档位决定，与弹幕本身无关。
        val travelSeconds = BASE_SCROLL_SECONDS / speed.factor
        val pxPerMs = width.toFloat() / (travelSeconds * 1000f)
        // 中央高亮那一组不进普通轨道。
        val highlighted = highlights.map { it.group.key }.toSet()
        groups.filter { it.key !in highlighted }.sortedBy { it.firstMs }.forEach { group ->
            val widthPx = fill.measureText(group.label)
            val durationMs = ((width + widthPx) / pxPerMs).toLong().coerceAtLeast(1L)
            // 找一条此刻已空出来的轨道；没有就把这一条推迟到最早空出来的时刻。
            var lane = laneFreeAt.indexOfFirst { it <= group.firstMs }
            var start = group.firstMs
            if (lane < 0) {
                lane = laneFreeAt.indices.maxByOrNull { laneFreeAt[it] } ?: return@forEach
                val earliest = laneFreeAt[lane]
                val wait = earliest - group.firstMs
                if (wait > MAX_DELAY_MS) return@forEach
                start = earliest
            }
            laneFreeAt[lane] = start + durationMs
            lines += Line(start, durationMs, widthPx, lane, group)
        }
        lines.sortBy { it.startMs }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == DanmakuMode.HIDDEN || items.isEmpty()) return
        relayout(width, height)
        if (laneCount <= 0) return
        val now = positionMillis()
        val density = resources.displayMetrics.density
        val topInset = when (mode) {
            DanmakuMode.FULL -> TOP_INSET_DP * density
            else -> TOP_MODE_INSET_DP * density
        }
        fill.alpha = (textAlpha.coerceIn(0f, 1f) * 255).toInt()
        edge.alpha = fill.alpha
        var drawn = 0
        for (line in lines) {
            if (drawn >= MAX_LINES_PER_FRAME) break
            if (now < line.startMs) continue
            val elapsed = now - line.startMs
            if (elapsed > line.durationMs) continue
            val x = width - (elapsed.toFloat() / line.durationMs.toFloat()) * (width + line.width)
            // 尾部已经离开画面右边就不再画。
            if (x > width) continue
            if (x + line.width < 0) continue
            val y = topInset + line.lane * laneHeight
            canvas.drawText(line.group.label, x, y, edge)
            canvas.drawText(line.group.label, x, y, fill)
            drawn++
        }
        drawHighlight(canvas, now)
        // 自驱动画循环：只要在播、可见、挂在窗口上，就按 vsync 排下一帧；暂停/隐藏时循环自然停。
        if (playing() && visibility == VISIBLE && isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** 中央突出显示：同一时间只有一条，数量涨了只更新标记、不切走。 */
    private fun drawHighlight(canvas: Canvas, now: Long) {
        val round = highlights.firstOrNull { now >= it.startMs && now < it.endMs } ?: return
        val textSize = baseTextSize() * HIGHLIGHT_SCALE
        hlFill.textSize = textSize
        hlEdge.textSize = textSize
        hlEdge.strokeWidth = (textSize / 11f).coerceAtLeast(1f)
        hlFill.alpha = (textAlpha.coerceIn(0f, 1f) * 255).toInt()
        hlEdge.alpha = hlFill.alpha
        val label = round.group.label
        val textWidth = hlFill.measureText(label)
        val pad = HIGHLIGHT_PAD_DP * resources.displayMetrics.density
        val cx = width / 2f
        val cy = height * 0.32f
        val left = cx - textWidth / 2f - pad
        val right = cx + textWidth / 2f + pad
        val top = cy - textSize
        val bottom = cy + textSize * 0.35f
        canvas.drawRoundRect(android.graphics.RectF(left, top, right, bottom),
            (bottom - top) / 2f, (bottom - top) / 2f, plate)
        canvas.drawText(label, cx - textWidth / 2f, cy, hlEdge)
        canvas.drawText(label, cx - textWidth / 2f, cy, hlFill)
    }
}

/** 字号缩放的合法范围（与 [com.hongguotv.core.DanmakuStyle] 同一套，避免循环依赖单独放一份）。 */
private object DanmakuStyleRange {
    const val MIN = 0.6f
    const val MAX = 2f
}
