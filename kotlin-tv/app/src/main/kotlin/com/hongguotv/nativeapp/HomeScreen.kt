// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** Stable selection information: moving focus never starts a request or changes the layout. */
class SeriesPreview(context: Context): LinearLayout(context) {
    private fun label(size: Float,color: Int)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val title=label(22f,Color.WHITE).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }
    private val meta=label(13f,Color.rgb(255,140,110)).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    private val description=label(13f,Color.rgb(180,190,205)).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END }
    init {
        orientation=VERTICAL
        val pad=(8*resources.displayMetrics.density).toInt(); setPadding(0,pad,0,pad)
        addView(title); addView(meta); addView(description)
        show(null,"")
    }
    fun show(series: Series?,status: String) {
        title.text=series?.title ?: "选一部，慢慢看"
        meta.text=if(series==null) "确认查看详情 · 长按确认 / 菜单键快捷操作" else listOf(status,series.badge,series.tags).flatMap { it.split('·') }.map(String::trim).filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  ·  ")
        description.text=series?.description?.ifBlank { "暂无简介，按确认查看集数与播放选项。" } ?: "上方切换内容，下方选择剧集；稍后想看的剧可从快捷操作加入首页。"
    }
}

/** Horizontal shelves with explicit D-pad neighbours and stable section/id focus keys. */
class HomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit
): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>)
    private val accent=Color.rgb(255,99,76)
    private val surface=Color.rgb(31,36,47)
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(focus: Boolean)=GradientDrawable().apply { setColor(if(focus) Color.rgb(66,43,43) else surface); cornerRadius=dp(9).toFloat(); setStroke(dp(2),if(focus) accent else Color.TRANSPARENT) }
    private fun label(value: String,size: Float,color: Int=Color.WHITE)=TextView(context).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private val scroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
    private val contents=LinearLayout(context).apply { orientation=VERTICAL }
    private val rows=mutableListOf<List<View>>()
    private val blocks=mutableListOf<LinearLayout>()
    private val coverLoads=mutableListOf<List<()->Unit>>()
    private val cards=linkedMapOf<String,View>()
    val refresh=label("正在更新热门…",13f).apply { id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f) } }
    init { orientation=VERTICAL; scroll.addView(contents); addView(scroll,LayoutParams(-1,0,1f)) }
    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?) {
        contents.removeAllViews(); rows.clear(); blocks.clear(); coverLoads.clear(); cards.clear()
        shelves.filter { it.entries.isNotEmpty() }.forEachIndexed { shelfIndex,shelf ->
            val block=LinearLayout(context).apply { orientation=VERTICAL }
            block.addView(label(shelf.title,18f).apply { setPadding(dp(3),dp(8),0,dp(7)); setTypeface(null,Typeface.BOLD) })
            val horizontal=HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false; setPadding(dp(2),dp(2),dp(2),dp(2)) }
            val line=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.TOP }
            horizontal.addView(line); block.addView(horizontal)
            val row=mutableListOf<View>(); val loaders=mutableListOf<()->Unit>()
            shelf.entries.forEachIndexed { columnIndex,entry ->
                val series=entry.series; val key="${shelf.key}:${series.id}"
                val card=LinearLayout(context).apply {
                    orientation=VERTICAL; id=View.generateViewId(); tag=key; isFocusable=true; isFocusableInTouchMode=true
                    setPadding(dp(5),dp(5),dp(5),dp(7)); background=shape(false)
                    contentDescription="${shelf.title}，${series.title}，${entry.label}"
                }
                val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
                card.addView(image,LayoutParams(-1,dp(101)))
                var loaded=false
                loaders+={ if(!loaded) { loaded=true; loadCover(image,series.cover) } }
                card.addView(label(series.title,14f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) })
                card.addView(label(entry.label,12f,Color.rgb(255,160,130)).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) })
                entry.progress?.let { value -> card.addView(ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=value; progressTintList=android.content.res.ColorStateList.valueOf(accent) },LayoutParams(-1,dp(3)).apply { topMargin=dp(4) }) }
                card.setOnClickListener { remember(key); open(series,entry.resume) }
                card.setOnLongClickListener { remember(key); actions(series,card); true }
                card.setOnKeyListener { _,code,event -> if(code==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) actions(series,card); true } else false }
                card.setOnFocusChangeListener { _,focused ->
                    card.background=shape(focused)
                    if(focused) {
                        remember(key); selected(series,entry.label); loadNearby(shelfIndex,columnIndex)
                        scroll.post { scroll.smoothScrollTo(0,block.top.coerceAtLeast(0)) }
                    }
                }
                val width=((resources.displayMetrics.widthPixels*.9f-dp(48))/5).toInt().coerceAtLeast(dp(130))
                line.addView(card,LayoutParams(width,-1).apply { rightMargin=dp(10) }); row+=card; cards[key]=card
            }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=dp(7) }); rows+=row; blocks+=block; coverLoads+=loaders
        }
        if(cards.isEmpty()) contents.addView(label("还没有本机记录，热门剧加载后可选择观看。",15f).apply { setPadding(0,dp(16),0,dp(16)) })
        contents.addView(refresh,LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val footer=mutableListOf<View>(refresh)
        if(more!=null) {
            val next=label("更多热门  ›",15f).apply { id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(12),dp(12),dp(12),dp(12)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f) }; setOnClickListener { more() } }
            contents.addView(next,LayoutParams(-1,-2).apply { topMargin=dp(8) }); footer+=next
        }
        rows.forEachIndexed { i,row -> row.forEachIndexed { j,card ->
            card.nextFocusLeftId=row[(j-1).coerceAtLeast(0)].id; card.nextFocusRightId=row[(j+1).coerceAtMost(row.lastIndex)].id
            card.nextFocusUpId=if(i==0) top.id else rows[i-1][j.coerceAtMost(rows[i-1].lastIndex)].id
            card.nextFocusDownId=if(i==rows.lastIndex) refresh.id else rows[i+1][j.coerceAtMost(rows[i+1].lastIndex)].id
        } }
        footer.forEachIndexed { i,v -> v.nextFocusUpId=if(i==0) rows.lastOrNull()?.firstOrNull()?.id ?: top.id else footer[i-1].id; v.nextFocusDownId=footer.getOrNull(i+1)?.id ?: v.id }
        top.nextFocusDownId=rows.firstOrNull()?.firstOrNull()?.id ?: refresh.id
        if(requestFocus) (cards[focus] ?: cards.values.firstOrNull() ?: refresh).requestFocus()
        if(!requestFocus) loadNearby(0,0)
        post { fitArtwork() }
    }
    private fun loadNearby(row: Int,column: Int) {
        coverLoads.getOrNull(row)?.let { loads -> for(index in (column-2).coerceAtLeast(0)..(column+5).coerceAtMost(loads.lastIndex)) loads[index]() }
        coverLoads.getOrNull(row+1)?.take(5)?.forEach { it() }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); post { fitArtwork() } }
    private fun fitArtwork() {
        if(height<=0) return
        rows.forEachIndexed { index,row ->
            val columns=row.map { it as LinearLayout }
            val textHeight=columns.maxOf { card -> card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { child ->
                val view=card.getChildAt(child); val params=view.layoutParams as LayoutParams
                view.measuredHeight+params.topMargin+params.bottomMargin
            } }
            val imageHeight=(height-blocks[index].getChildAt(0).measuredHeight-textHeight-dp(8)).coerceIn(dp(24),dp(101))
            columns.forEach { card -> card.getChildAt(0).let { image -> if(image.layoutParams.height!=imageHeight) image.layoutParams=image.layoutParams.apply { height=imageHeight } } }
        }
    }
    fun firstId()=rows.firstOrNull()?.firstOrNull()?.id ?: refresh.id
}
