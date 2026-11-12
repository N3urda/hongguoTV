package com.hongguotv.nativeapp

import android.app.Activity
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 26], qualifiers = "w960dp-h540dp-land-mdpi")
class CatalogShellTest {
    @Test fun switchingBodyPreservesNavigationIdentityFocusAndSharedSelectionStyle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var selected = -1
        val shell = CatalogShell(activity.get()) { selected = it }
        try {
            activity.get().setContentView(shell)
            shell.body.addView(TextView(activity.get()).apply { text = "首页内容" })
            val top = shell.getChildAt(0)
            assertEquals(shell.navigation[7].id, shell.navigation[1].nextFocusRightId)
            assertEquals(shell.navigation[2].id, shell.navigation[7].nextFocusRightId)
            val tab = shell.navigation[2]
            val ids = shell.navigation.map { it.id }
            tab.requestFocus(); tab.performClick()
            assertEquals(2, selected)
            shell.clearBody(); shell.select(2)
            shell.body.addView(TextView(activity.get()).apply { text = "排行榜内容" })
            assertSame(top, shell.getChildAt(0))
            assertSame(tab, shell.navigation[2])
            assertEquals(ids, shell.navigation.map { it.id })
            assertTrue(tab.hasFocus())
            assertTrue(tab.isSelected)
            assertEquals(TvVisual.accent, tab.currentTextColor)
            assertFalse(shell.navigation[0].isSelected)
        } finally { activity.pause().stop().destroy() }
    }
}
