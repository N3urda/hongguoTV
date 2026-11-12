package com.hongguotv.nativeapp

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 26, 33], qualifiers = "mdpi")
class BrandResourcesTest {
    @Test fun manifestIconAndBannerResolveOnLegacyAndAdaptiveDevices() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getApplicationInfo(app.packageName, 0)
        val icon = app.resources.getDrawable(info.icon, app.theme)
        if (Build.VERSION.SDK_INT >= 26) {
            assertTrue(icon is AdaptiveIconDrawable)
        } else {
            assertTrue(icon is BitmapDrawable)
        }
        val banner = app.resources.getDrawable(info.banner, app.theme)
        assertTrue(banner is BitmapDrawable)
        assertEquals(160, banner.intrinsicWidth)
        assertEquals(90, banner.intrinsicHeight)
        // Inflate via the manifest, then exercise drawing at launcher-selected sizes.
        for (size in listOf(24, 48, 160)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            bitmap.recycle()
        }
    }
}
