// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
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
class SearchSuggestionsViewTest {
    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList())

    private fun finish(view: SearchSuggestionsView) {
        (view.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(view)
                as? Future<*>)
            ?.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun typingDebouncesAndChipsSearchChineseTermsWithExplicitFocusRoutes() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val input = EditText(activity).apply { id = View.generateViewId() }
        val result =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
            }
        val button =
            TextView(activity).apply {
                id = View.generateViewId()
                isFocusable = true
            }
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        var searched = ""
        val hints =
            SearchSuggestionsView(
                activity,
                input,
                listOf(button),
                { result },
                {
                    calls += it
                    listOf("大佬，你该出手了", "大佬快醒醒")
                },
                { searched = it },
            )
        try {
            activity.setContentView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(input)
                    addView(button)
                    addView(hints)
                    addView(result)
                }
            )
            input.setText("da")
            input.setText("dalao")
            ShadowLooper.idleMainLooper(349, TimeUnit.MILLISECONDS)
            assertTrue(calls.isEmpty())
            ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
            finish(hints)
            assertEquals(listOf("dalao"), calls)
            val first = texts(hints).first()
            assertEquals(first.id, input.nextFocusDownId)
            assertEquals(first.id, button.nextFocusDownId)
            assertEquals(input.id, first.nextFocusUpId)
            assertEquals(result.id, first.nextFocusDownId)
            first.performClick()
            assertEquals("大佬，你该出手了", searched)
            input.setText("")
            assertEquals(View.GONE, hints.visibility)
            assertEquals(result.id, input.nextFocusDownId)
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            assertEquals(1, calls.size)
        } finally {
            hints.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun staleResponsesAndClosedViewsCannotPublishSuggestions() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val input = EditText(activity).apply { id = View.generateViewId() }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hints =
            SearchSuggestionsView(
                activity,
                input,
                emptyList(),
                { input },
                { keyword ->
                    if (keyword == "old") {
                        started.countDown()
                        // Simulate a source that finishes even after cancellation.
                        while (release.count > 0) try {
                            release.await(5, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {}
                    }
                    listOf(keyword)
                },
                {},
            )
        try {
            activity.setContentView(hints)
            input.setText("old")
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            input.setText("new")
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            release.countDown()
            finish(hints)
            assertEquals(listOf("new"), texts(hints).map { it.text.toString() })
            input.setText("closed")
            hints.close()
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
            assertEquals(View.GONE, hints.visibility)
            assertTrue(texts(hints).isEmpty())
        } finally {
            release.countDown()
            hints.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun failedSuggestionsKeepDirectSearchAndRestoreDownNavigation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val input = EditText(activity).apply { id = View.generateViewId() }
        val result = TextView(activity).apply { id = View.generateViewId() }
        val hints =
            SearchSuggestionsView(
                activity,
                input,
                emptyList(),
                { result },
                { throw java.io.IOException("offline") },
                { fail("automatic search") },
            )
        try {
            input.setText("测试")
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            finish(hints)
            assertTrue(texts(hints).single().text.contains("可直接按搜索"))
            assertEquals(result.id, input.nextFocusDownId)
        } finally {
            hints.close()
            controller.pause().stop().destroy()
        }
    }
}
