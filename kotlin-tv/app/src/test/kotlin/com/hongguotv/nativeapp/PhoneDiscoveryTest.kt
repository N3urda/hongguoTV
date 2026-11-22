// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoveryFilter
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.FilteredDiscoveryPage
import com.hongguotv.core.Series
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w320dp-h640dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneDiscoveryTest {
    private val groups = listOf(
        DiscoveryGroup("genre", "类型", listOf(DiscoveryFilter("comic_series", "漫剧"))),
        DiscoveryGroup("category_dim_theme", "主题", (1..30).map { DiscoveryFilter("theme_$it", "主题 $it") }),
        DiscoveryGroup("gender", "受众", listOf(DiscoveryFilter("0", "女频"), DiscoveryFilter("1", "男频"))),
    )

    @Test
    fun wrappingExposesEveryOptionAndKeepsTheChosenOptionVisible() = fixture { screen, state, requests ->
        requests.single().complete(page("1"), null)
        assertFalse(nodes(screen).any { it is HorizontalScrollView })
        assertNull(nodes(screen).filterIsInstance<TextView>().firstOrNull { it.text == "主题 30" })
        click(screen, "展开主题选项")
        assertNotNull(control(screen, "收起主题选项"))
        assertNotNull(control(screen, "主题 30"))
        click(screen, "收起主题选项")
        click(screen, "展开主题选项")
        click(screen, "主题 30")

        assertEquals(mapOf("category_dim_theme" to "theme_30"), requests.last().selection.values)
        assertTrue(control(screen, "主题 30").isSelected)
        assertNotNull(control(screen, "展开主题选项"))
        assertNull(state.openGroup)
        click(screen, "女频")
        assertEquals(mapOf("category_dim_theme" to "theme_30", "gender" to "0"), requests.last().selection.values)
        click(screen, "漫剧")
        assertEquals(mapOf("genre" to "comic_series"), requests.last().selection.values)
    }

    @Test
    fun combinedFiltersIgnoreOldResponsesAndKeepResultsWhenPagingFails() = fixture { screen, state, requests ->
        requests.single().complete(page("1"), null)
        click(screen, "女频")
        click(screen, "男频")
        requests[1].complete(page("2"), null)
        assertTrue(state.items.isEmpty())
        val cursor = DiscoveryCursor(24, "test-session", 2)
        requests[2].complete(page("3", cursor), null)
        assertEquals(listOf("3"), state.items.map(Series::id))
        assertEquals(mapOf("gender" to "1"), state.selection.values)

        click(screen, "加载更多")
        assertEquals(cursor, requests[3].cursor)
        requests[3].complete(null, IllegalStateException("fixture failure"))
        assertEquals(listOf("3"), state.items.map(Series::id))
        click(screen, "重试加载更多")
        requests[4].complete(FilteredDiscoveryPage(listOf(Series("3", "重复"), Series("4", "新剧")), emptyList(), null), null)
        assertEquals(listOf("3", "4"), state.items.map(Series::id))
        assertTrue(control(screen, "男频").isSelected)
    }

    private fun page(id: String, cursor: DiscoveryCursor? = null) =
        FilteredDiscoveryPage(listOf(Series(id, "作品$id")), groups, cursor)

    private data class Request(
        val selection: DiscoverySelection,
        val cursor: DiscoveryCursor?,
        val complete: (FilteredDiscoveryPage?, Throwable?) -> Unit,
    )

    private fun fixture(test: (PhoneDiscovery, PhoneDiscovery.State, MutableList<Request>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val state = PhoneDiscovery.State()
        val scroller = ScrollView(activity)
        val requests = mutableListOf<Request>()
        val screen = PhoneDiscovery(
            activity, state, scroller, 0,
            { selection, cursor, done -> requests += Request(selection, cursor, done) },
            { label, _, run ->
                Button(activity).apply {
                    text = label
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(8, 0, 8, 0)
                    setOnClickListener { run() }
                }
            },
            { parent, items -> items.forEach { item -> parent.addView(TextView(activity).apply { text = item.title }) } },
        )
        scroller.addView(screen, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(scroller)
        screen.start()
        scroller.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        scroller.layout(0, 0, 320, 640)
        ShadowLooper.idleMainLooper()
        try {
            test(screen, state, requests)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun click(view: View, label: String) = control(view, label).performClick().also { assertTrue(it) }
    private fun control(view: View, label: String): View = nodes(view).first {
        it.contentDescription == label || (it is TextView && it.text.toString() == label)
    }
    private fun nodes(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
