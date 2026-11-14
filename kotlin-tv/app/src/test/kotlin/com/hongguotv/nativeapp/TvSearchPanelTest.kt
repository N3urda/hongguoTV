// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TvSearchPanelTest {
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun compactLayoutKeepsResultsWideAndScrollsTheKeyboardInsteadOfHidingControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val panel =
            TvSearchPanel(
                activity,
                "",
                emptyList(),
                TextView(activity).apply { id = View.generateViewId() },
                { emptyList() },
                {},
            )
        try {
            activity.setContentView(panel)
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            panel.layout(0, 0, 640, 300)
            assertFalse(panel.showingResults)
            val scroll = (panel.getChildAt(0) as ViewGroup).getChildAt(0) as ScrollView
            assertTrue(scroll.getChildAt(0).measuredHeight > scroll.height)
            assertFalse(texts(panel).any { it.text == "返回分类" })
        } finally {
            panel.suggestions.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun candidatesUseSpatialGridAndResultsReturnToSelectedWordAndKeyboard() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val panel = TvSearchPanel(activity, "", listOf("一", "二", "三", "四"),
            TextView(activity).apply { id = View.generateViewId() }, { emptyList() }, {})
        try {
            activity.setContentView(panel)
            val key = texts(panel).first { it.text == "R" }
            key.requestFocus()
            val first = texts(panel).first { it.text == "一" }
            val third = texts(panel).first { it.text == "三" }
            assertEquals(third.id, first.nextFocusDownId)
            assertEquals(key.id, first.nextFocusLeftId)
            third.requestFocus()
            val state = panel.capture()
            panel.showResults()
            assertTrue(panel.showingResults)
            assertTrue(panel.back())
            assertTrue(third.hasFocus())
            panel.restore(state)
            panel.showResults()
            panel.back()
            assertTrue(third.hasFocus())
            assertFalse(panel.back())
        } finally {
            panel.suggestions.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun longSuggestionsKeepEqualColumnsAndRestoreDetachedFocus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val panel = TvSearchPanel(activity, "", emptyList(), TextView(activity).apply { id = View.generateViewId() }, { emptyList() }, {})
        try {
            val state = TvSearchPanel.EditState("cs", listOf("重生螳螂觉醒无限吞噬进化", "重生", "重生归来", "另一个特别长的剧名"), 1, -1, 17)
            panel.restore(state)
            panel.showResults()
            activity.setContentView(panel)
            panel.back()
            panel.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, 960, 520)
            assertTrue(panel.suggestions.viewAt(1)!!.hasFocus())
            assertTrue(kotlin.math.abs(panel.suggestions.viewAt(0)!!.width - panel.suggestions.viewAt(1)!!.width) <= 1)
            assertTrue(panel.suggestions.viewAt(0)!!.width > 100)
        } finally { panel.suggestions.close(); controller.pause().stop().destroy() }
    }

    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun remoteKeysEditSelectionDeleteWholeUnicodeCharacterAndKeepSearchExplicit() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val upper = TextView(activity).apply { id = View.generateViewId() }
        var searched = ""
        val panel =
            TvSearchPanel(
                activity,
                "",
                listOf("历史剧名"),
                upper,
                { emptyList() },
                { searched = it },
            )
        try {
            activity.setContentView(panel)
            fun click(label: String) =
                texts(panel).first { it.isClickable && it.text.toString() == label }.performClick()
            click("A")
            click("B")
            assertEquals("ab", panel.input.text.toString())
            assertEquals("", searched)
            panel.input.setSelection(1)
            click("C")
            assertEquals("acb", panel.input.text.toString())
            panel.input.setText("剧😀")
            panel.input.setSelection(3)
            click("删除")
            assertEquals("剧", panel.input.text.toString())
            panel.input.setText("  测试剧名  ")
            click("搜索")
            assertEquals("测试剧名", searched)
            click("历史剧名")
            assertEquals("历史剧名", searched)
            click("清空")
            assertTrue(panel.input.text.isEmpty())
            click("打开输入法")
            assertTrue(panel.input.showSoftInputOnFocus)
            assertTrue(panel.input.hasFocus())
            assertFalse(texts(panel).any { it.text == "中文输入" || it.text == "手机控制台" })
            assertFalse(texts(panel).any { it.text == "返回分类" })
            panel.input.setText("a".repeat(80))
            panel.input.setSelection(80)
            click("B")
            assertEquals(80, panel.input.length())
        } finally {
            panel.suggestions.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun twoColumnsFitTelevisionAndRemoteCanMoveFromKeyboardToWordsAndResults() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val upper = TextView(activity).apply { id = View.generateViewId() }
        var searched = ""
        val panel =
            TvSearchPanel(
                activity,
                "",
                listOf("最近剧名"),
                upper,
                { listOf("大佬，该出手了") },
                { searched = it },
            )
        val result =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
                text = "结果作品"
            }
        panel.results.addView(result)
        panel.resultEntry = { result }
        try {
            activity.setContentView(panel)
            for (width in listOf(800, 960, 1280)) {
                panel.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                )
                panel.layout(0, 0, width, 480)
                assertFalse(panel.showingResults)
                assertEquals(width, panel.width)
            }
            panel.input.nextFocusDownId =
                result.id // Result rendering wires its generic search entry first.
            panel.updateNavigation()
            assertEquals(panel.entry().id, panel.input.nextFocusDownId)
            val key = texts(panel).first { it.text == "F" }
            assertEquals(panel.middleEntry()?.id, key.nextFocusRightId)
            panel.input.setText("dalao")
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            (panel.suggestions.javaClass
                    .getDeclaredField("job")
                    .apply { isAccessible = true }
                    .get(panel.suggestions) as Future<*>)
                .get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val word = panel.suggestions.entry()!!
            assertEquals(word.id, key.nextFocusRightId)
            assertEquals(word.id, word.nextFocusRightId)
            assertEquals("", searched)
            word.performClick()
            assertEquals("大佬，该出手了", searched)
        } finally {
            panel.suggestions.close()
            controller.pause().stop().destroy()
        }
    }
}
