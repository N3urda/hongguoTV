// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes; no blur, bitmap backgrounds or focus animation. */
object TvStyle {
    // Phone (touch) layouts need noticeably larger type and hit targets than the TV remote UI,
    // which is tuned for 720p/1080p at living-room viewing distance. TV sizing stays untouched,
    // so the two flavors keep their own carefully-tuned geometry.
    val phoneUi = !BuildConfig.IS_TV
    /** Font size in sp, enlarged for touch layouts. 只喂给 textSize（sp 单位由 TextView 换算）。 */
    fun sp(value: Float) = if (phoneUi) value * 1.25f else value
    /**
     * Layout dimension in **pixels**, enlarged for touch layouts.
     *
     * 返回值直接喂给 LayoutParams / padding / margin / minHeight 这些**像素**口径的 API。
     * 之前返回的是无单位的「dp 数」，被当像素用之后 45dp 变 45px（输入框成细线）、
     * 42dp 变 42px（选集标签被 15sp 文字撑爆）——这次把换算收进函数里，调用点不用各自 dp()。
     */
    fun touch(value: Int,context: Context? = null): Int {
        val density = (context ?: appContext)?.resources?.displayMetrics?.density
            ?: throw IllegalStateException("TvStyle.init(context) must run before any touch() sizing")
        val scaled = if (phoneUi) value * 1.35f else value.toFloat()
        return (scaled * density).toInt()
    }
    /** App 上下文：touch() 需要密度换算，又不想在每个调用点传 Context。 */
    @Volatile private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    val background=Color.rgb(11,15,21)
    val surface=Color.rgb(23,30,40)
    val raised=Color.rgb(32,42,54)
    val accent=Color.rgb(255,137,100)
    val text=Color.rgb(243,244,246)
    val muted=Color.rgb(147,160,178)
    val outline=Color.rgb(43,55,70)
    const val POSTER_ASPECT=.7f
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=10)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,if(focused) raised else surface,if(focused) accent else Color.TRANSPARENT)
}
