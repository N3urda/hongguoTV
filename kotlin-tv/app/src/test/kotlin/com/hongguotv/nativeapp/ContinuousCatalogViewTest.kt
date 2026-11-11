package com.hongguotv.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Series
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ContinuousCatalogViewTest {
    @Test
    fun remoteNavigationLoadsWithoutFooterFocusAndShowsOnlyFailureRetry() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var calls = 0
        val upper =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
            }
        val browser =
            ContinuousCatalogView(
                activity,
                6,
                upper,
                { item, recycled ->
                    (recycled as? TextView ?: TextView(activity)).apply {
                        text = item.title
                        minHeight = 80
                    }
                },
                {},
                {},
                { _, _ -> },
                { calls++ },
            )
        try {
            activity.setContentView(browser)
            browser.useRemoteNavigation()
            browser.show(rows(1, 60), true)
            browser.enter()
            layout(browser)
            assertEquals(View.GONE, browser.top.visibility)
            assertEquals(View.GONE, browser.next.visibility)
            browser.grid.setSelection(54)
            layout(browser)
            browser.grid.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            )
            assertEquals(1, calls)
            assertTrue(browser.grid.hasFocus())
            browser.fail()
            assertEquals(View.VISIBLE, browser.next.visibility)
            browser.next.requestFocus()
            browser.next.performClick()
            assertEquals(2, calls)
            browser.append(rows(61, 84), false)
            layout(browser)
            assertEquals(View.GONE, browser.next.visibility)
            assertTrue(browser.grid.hasFocus())
            browser.resetToTop()
            upper.requestFocus()
            layout(browser)
            assertEquals("1", browser.capture()?.id)
        } finally {
            browser.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun fullSelectedTitleWrapsAndEmptyFilteredPageCanLoadMore() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var calls = 0
        val upper = TextView(activity).apply { id = View.generateViewId() }
        val browser = ContinuousCatalogView(activity, 3, upper,
            { item, _ -> TextView(activity).apply { text = item.title } },
            {}, {}, { _, _ -> }, { calls++ })
        try {
            activity.setContentView(browser)
            browser.useRemoteNavigation()
            val title = "谁说没有灵根就不能修仙之无灵证道第十三季"
            browser.show(listOf(Series("1", title)), false)
            layout(browser)
            val fullTitle = browser.getChildAt(0) as TextView
            assertEquals(title, fullTitle.text.toString())
            assertNull(fullTitle.ellipsize)
            assertTrue(fullTitle.maxLines > 2)
            browser.show(emptyList(), true)
            layout(browser)
            assertEquals("", fullTitle.text.toString())
            assertEquals(View.VISIBLE, browser.next.visibility)
            browser.enter()
            assertTrue(browser.next.hasFocus())
            browser.next.performClick()
            assertEquals(1, calls)
        } finally {
            browser.close()
            controller.pause().stop().destroy()
        }
    }

    private fun rows(from: Int, to: Int) = (from..to).map { Series("$it", "作品$it") }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 960, 420)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun appendPreservesSelectionDeduplicatesAndDoesNotFetchUntilNavigation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            var calls = 0
            var opened = ""
            var managed = ""
            val upper =
                TextView(activity).apply {
                    id = View.generateViewId()
                    isFocusable = true
                }
            val view =
                ContinuousCatalogView(
                    activity,
                    6,
                    upper,
                    { item, recycled ->
                        (recycled as? TextView ?: TextView(activity)).apply {
                            text = item.title
                            minHeight = 80
                        }
                    },
                    {},
                    { opened = it.id },
                    { item, _ -> managed = item.id },
                    { calls++ },
                )
            activity.setContentView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(upper)
                    addView(view)
                }
            )
            view.show(rows(1, 60), true)
            view.enter()
            layout(view)
            assertEquals(0, calls)
            assertTrue(view.grid.childCount < 60) // Native recycling bounds actual views.
            view.grid.setSelection(25)
            layout(view)
            val selected = view.capture()!!.id
            view.requestMore(false)
            view.requestMore(false)
            assertEquals(1, calls)
            view.append(rows(55, 90), true)
            layout(view)
            assertEquals(90, view.grid.adapter.count)
            assertEquals(selected, view.capture()?.id)
            assertTrue(view.grid.hasFocus())
            val index = view.grid.selectedItemPosition
            view.grid.performItemClick(
                view.grid.selectedView,
                index,
                view.grid.adapter.getItemId(index),
            )
            assertEquals(selected, opened)
            view.grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
            assertEquals(selected, managed)
            assertEquals(1, calls) // Appending does not recursively fetch every page.
            view.fail(com.hongguotv.core.ContentHttpException(502))
            view.requestMore(false)
            assertEquals(1, calls)
            val footer = view.getChildAt(view.childCount - 1) as LinearLayout
            assertTrue((footer.getChildAt(0) as TextView).text.contains("HTTP 502"))
            assertEquals(90, view.grid.adapter.count)
            view.next.performClick()
            assertEquals(2, calls)
            view.close()
            view.append(rows(91, 100), false)
            assertEquals(90, view.grid.adapter.count)
            view.requestMore(true)
            assertEquals(2, calls)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun approachingLastRowsPrefetchesWithoutMovingFocusToFooter() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            var calls = 0
            val upper =
                TextView(activity).apply {
                    id = View.generateViewId()
                    isFocusable = true
                }
            val view =
                ContinuousCatalogView(
                    activity,
                    6,
                    upper,
                    { item, recycled ->
                        (recycled as? TextView ?: TextView(activity)).apply {
                            text = item.title
                            minHeight = 80
                        }
                    },
                    {},
                    {},
                    { _, _ -> },
                    { calls++ },
                )
            activity.setContentView(view)
            view.show(rows(1, 60), true)
            view.enter()
            layout(view)
            view.grid.setSelection(42)
            layout(view)
            assertEquals(0, calls)
            view.grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            layout(view)
            assertEquals(1, calls)
            assertTrue(view.grid.hasFocus())
            val focus = view.capture()?.id
            view.append(rows(61, 84), true)
            layout(view)
            assertEquals(focus, view.capture()?.id)
            assertEquals(1, calls)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun downAtEndLoadsOnceAndEntersAddedItemsUnlessUserMovesAway() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            var calls = 0
            val upper =
                TextView(activity).apply {
                    id = View.generateViewId()
                    isFocusable = true
                }
            val view =
                ContinuousCatalogView(
                    activity,
                    6,
                    upper,
                    { item, recycled ->
                        (recycled as? TextView ?: TextView(activity)).apply {
                            text = item.title
                            minHeight = 80
                        }
                    },
                    {},
                    {},
                    { _, _ -> },
                    { calls++ },
                )
            activity.setContentView(view)
            view.show(rows(1, 60), true)
            view.enter()
            layout(view)
            view.grid.setSelection(54)
            layout(view)
            assertEquals(0, calls)
            view.grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            assertEquals(1, calls)
            assertTrue(view.next.hasFocus())
            view.next.performClick()
            assertEquals(1, calls)
            view.append(rows(61, 90), true)
            layout(view)
            assertTrue(view.grid.hasFocus())
            assertEquals("61", view.capture()?.id)
            view.next.requestFocus()
            view.next.performClick()
            assertEquals(2, calls)
            view.top.requestFocus()
            view.append(rows(91, 100), false)
            layout(view)
            assertTrue(view.top.hasFocus()) // A background result must never steal focus.
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun restoringPositionAndReturningToTopKeepFocusInGrid() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val upper =
                TextView(activity).apply {
                    id = View.generateViewId()
                    isFocusable = true
                }
            val view =
                ContinuousCatalogView(
                    activity,
                    6,
                    upper,
                    { item, recycled ->
                        (recycled as? TextView ?: TextView(activity)).apply {
                            text = item.title
                            minHeight = 80
                        }
                    },
                    {},
                    {},
                    { _, _ -> },
                    { fail("Restore must not load pages") },
                )
            activity.setContentView(view)
            view.show(rows(1, 120), false, ContinuousCatalogView.Position("80", 0))
            view.enter()
            layout(view)
            assertEquals("80", view.capture()?.id)
            view.top.performClick()
            layout(view)
            assertEquals("1", view.capture()?.id)
            assertTrue(view.grid.hasFocus())
            assertEquals(View.GONE, view.next.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
