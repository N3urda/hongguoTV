package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 26, 33], qualifiers = "mdpi")
class BrandResourcesTest {
    @Test fun manifestIconKeepsTransparentRgbaTvBodyWithoutColoredBackground() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getApplicationInfo(app.packageName, 0)
        val icon = app.resources.getDrawable(info.icon, app.theme)
        assertTrue("Expected transparent RGBA BitmapDrawable without opaque AdaptiveIconDrawable backdrop", icon is BitmapDrawable)
        assertEquals(80, icon.intrinsicWidth)
        assertEquals(80, icon.intrinsicHeight)
        val banner = app.resources.getDrawable(info.banner, app.theme)
        assertTrue(banner is BitmapDrawable)
        assertEquals(160, banner.intrinsicWidth)
        assertEquals(90, banner.intrinsicHeight)
        for (size in listOf(24, 48, 160)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        for (resId in listOf(info.icon, R.drawable.app_icon)) {
            val (w, h, rgba) = decodeRgbaPng(app.resources.openRawResource(resId).use { it.readBytes() })
            fun alphaAt(x: Int, y: Int): Int = rgba[(y * w + x) * 4 + 3].toInt() and 0xFF
            assertEquals(0, alphaAt(0, 0))
            assertEquals(0, alphaAt(w - 1, 0))
            assertEquals(0, alphaAt(0, h - 1))
            assertEquals(0, alphaAt(w - 1, h - 1))
            assertEquals(0, alphaAt(w / 2, 0))
            assertEquals(0, alphaAt(w / 2, h - 1))
            assertTrue("Expected opaque TV body at center, got ${alphaAt(w / 2, h / 2)}", alphaAt(w / 2, h / 2) >= 240)
            val cx = w / 2.0
            val cy = h / 2.0
            val maxRadius = w / 2.0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (alphaAt(x, y) > 0) {
                        val cornerDist = maxOf(
                            hypot(x - cx, y - cy),
                            hypot(x + 1 - cx, y - cy),
                            hypot(x - cx, y + 1 - cy),
                            hypot(x + 1 - cx, y + 1 - cy),
                        )
                        assertTrue("Visible TV pixel ($x,$y) exceeds circular mask radius $maxRadius", cornerDist <= maxRadius)
                    }
                }
            }
        }
    }

    private fun decodeRgbaPng(bytes: ByteArray): Triple<Int, Int, ByteArray> {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertArrayEquals(sig, bytes.copyOfRange(0, 8))
        val buf = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        while (buf.hasRemaining()) {
            val len = buf.int
            val typeBytes = ByteArray(4).also { buf.get(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val data = ByteArray(len).also { buf.get(it) }
            buf.int // CRC
            when (type) {
                "IHDR" -> {
                    val hdr = ByteBuffer.wrap(data)
                    width = hdr.int
                    height = hdr.int
                    assertEquals(8, hdr.get().toInt() and 0xFF)
                    assertEquals("Expected 8-bit RGBA PNG (colorType=6)", 6, hdr.get().toInt() and 0xFF)
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { it.readBytes() }
        val stride = width * 4
        assertEquals(height * (1 + stride), raw.size)
        val out = ByteArray(height * stride)
        for (y in 0 until height) {
            val filter = raw[y * (1 + stride)].toInt() and 0xFF
            val rowIn = y * (1 + stride) + 1
            val rowOut = y * stride
            for (i in 0 until stride) {
                val xByte = raw[rowIn + i].toInt() and 0xFF
                val a = if (i >= 4) out[rowOut + i - 4].toInt() and 0xFF else 0
                val b = if (y > 0) out[rowOut - stride + i].toInt() and 0xFF else 0
                val c = if (i >= 4 && y > 0) out[rowOut - stride + i - 4].toInt() and 0xFF else 0
                val recon = when (filter) {
                    0 -> xByte
                    1 -> xByte + a
                    2 -> xByte + b
                    3 -> xByte + ((a + b) ushr 1)
                    4 -> {
                        val p = a + b - c
                        val pa = abs(p - a)
                        val pb = abs(p - b)
                        val pc = abs(p - c)
                        val pr = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        xByte + pr
                    }
                    else -> fail("Unsupported PNG filter $filter") as Nothing
                }
                out[rowOut + i] = (recon and 0xFF).toByte()
            }
        }
        return Triple(width, height, out)
    }
}
