package com.hongguotv.nativeapp

import android.graphics.*
import android.graphics.drawable.Drawable

/** Small monochrome navigation glyphs, drawn at device resolution. */
internal class TvGlyph(private val kind: Int, private val size: Int, var ink: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun draw(c: Canvas) {
        c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat()); c.scale(bounds.width() / 24f, bounds.height() / 24f)
        pen.color = ink
        fun line(vararg xy: Float) { val p = Path(); p.moveTo(xy[0], xy[1]); for(i in 2 until xy.size step 2) p.lineTo(xy[i], xy[i+1]); c.drawPath(p, pen) }
        when(kind) {
            0 -> { line(3f,11f,12f,3f,21f,11f); line(5f,10f,5f,21f,10f,21f,10f,15f,14f,15f,14f,21f,19f,21f,19f,10f) }
            1 -> { for(x in listOf(4f,14f)) for(y in listOf(4f,14f)) c.drawRoundRect(x,y,x+6,y+6,1f,1f,pen) }
            8 -> { c.drawCircle(12f,12f,9f,pen); line(10f,8f,16f,12f,10f,16f,10f,8f) }
            9 -> {
                for ((x,y) in listOf(3f to 3f, 14f to 3f, 3f to 14f)) {
                    c.drawRect(x,y,x+7,y+7,pen)
                    c.drawPoint(x+3.5f,y+3.5f,pen)
                }
                line(15f,14f,15f,18f,21f,18f,21f,21f)
                c.drawPoint(20f,14f,pen); c.drawPoint(15f,21f,pen)
            }
            7 -> { c.drawCircle(10.5f,10.5f,6.5f,pen); line(15.5f,15.5f,21f,21f) }
            2 -> { line(5f,20f,5f,12f); line(12f,20f,12f,4f); line(19f,20f,19f,8f) }
            3 -> { c.drawCircle(12f,8f,4f,pen); c.drawArc(4f,14f,20f,28f,180f,180f,false,pen) }
            else -> { c.drawCircle(12f,12f,7f,pen); c.drawCircle(12f,12f,2.5f,pen); for(i in 0..7) { c.save(); c.rotate(i*45f,12f,12f); line(12f,2f,12f,5f); c.restore() } }
        }
        c.restore()
    }
    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { pen.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
