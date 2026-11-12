// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateSource
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdaterBackgroundTest {
    @Test
    fun automaticCheckDoesNotOpenDialogOrStealBrowsingFocus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val called = CountDownLatch(1)
        val source = object : AppUpdateSource {
            override fun latest(): AppUpdate {
                called.countDown()
                throw java.io.IOException("test offline")
            }
            override fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val updater = AppUpdater(activity, source) { true }
        try {
            activity.getSharedPreferences("app_updates", 0).edit().clear().putBoolean("automatic", true).commit()
            updater.resume()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            assertTrue(called.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(updater.showing)
            updater.show(null)
            assertTrue(updater.showing)
        } finally {
            updater.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun returningWithInstallResultDoesNotOpenUpdateDialog() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val updater = AppUpdater(activity) { false }
        try {
            activity.getSharedPreferences("update_install_session", 0).edit()
                .putString("result_text", "安装已取消").commit()
            updater.resume()
            assertFalse(updater.showing)
            assertFalse(activity.getSharedPreferences("update_install_session", 0).contains("result_text"))
        } finally {
            updater.destroy()
            controller.pause().stop().destroy()
        }
    }
}
