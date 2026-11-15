package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemeUsabilityTest {
    @Test fun informationDialogsUseReadableTextInEveryTheme() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val tools = TvTools(activity)
        try {
            TvStyle.themes.forEach { theme ->
                TvStyle.select(activity, theme)
                tools.info("提示", "这是一条重要提示", null)
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                assertEquals(theme.label, theme.text,
                    dialog.findViewById<TextView>(android.R.id.message).currentTextColor)
                tools.close()
            }
        } finally {
            TvStyle.select(activity, TvStyle.themes.first())
            tools.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test fun savedThemeRestoresAndUnknownThemeFallsBack() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            TvStyle.themes.forEach { theme ->
                TvStyle.select(activity, theme)
                TvStyle.load(activity)
                assertEquals(theme, TvStyle.current)
                assertEquals(theme.text, TvVisual.primary)
            }
            activity.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .edit().putString("theme", "removed-theme").commit()
            TvStyle.load(activity)
            assertEquals("dark", TvStyle.current.id)
        } finally {
            TvStyle.select(activity, TvStyle.themes.first())
            controller.pause().stop().destroy()
        }
    }
}
