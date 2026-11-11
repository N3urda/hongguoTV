package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.widget.*
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RelatedWorksViewTest {
    private fun descendants(v: View): List<View> =
        listOf(v) +
            if (v is android.view.ViewGroup)
                (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) }
            else emptyList()

    @Test
    fun allSeasonsGridCanOpenFortiethSeasonAndClosesOnDetach() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var selected: RelatedWork? = null
        val strip = RelatedWorksView(activity, emptyList(), { _, _ -> }, { "预约" }, { work, _ -> selected = work }, {})
        try {
            activity.setContentView(strip)
            val rows = (1..40).map { RelatedWork(Series("$it", "长剧第${it}季"), "第${it}季", false, it) }
            strip.show(rows)
            val all = descendants(strip).filterIsInstance<TextView>().single { it.text.toString().startsWith("全部季 /") }
            all.performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(40, descendants(dialog.window!!.decorView).filter { it.tag?.toString()?.startsWith("all-") == true }
                .sumOf { descendants(it).filterIsInstance<ImageView>().size })
            descendants(dialog.window!!.decorView).single { it.tag == "all-40" }.performClick()
            assertEquals("40", selected?.series?.id)
            assertFalse(dialog.isShowing)
            all.performClick()
            val second = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            activity.setContentView(TextView(activity))
            assertFalse(second.isShowing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun upcomingCardsUseReservationActionAndRemoteCanReachStripFromEpisodes() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val episode = TextView(activity.get()).apply { id = View.generateViewId() }
            val current = RelatedWork(Series("123", "第二季"), "第2季", false)
            val upcoming = RelatedWork(Series("456", "第三季"), "第3季", true)
            var selected: RelatedWork? = null
            var label = "登录后预约"
            var reloads = 0
            val strip =
                RelatedWorksView(
                    activity.get(),
                    listOf(episode),
                    { _, _ -> },
                    { label },
                    { work, _ -> selected = work },
                    { reloads++ },
                )
            activity.get().setContentView(strip)
            strip.show(listOf(current, upcoming))
            val views = descendants(strip)
            val released = views.single { it.tag == "123" }
            val future = views.single { it.tag == "456" }
            assertEquals(released.id, episode.nextFocusDownId)
            assertEquals(future.id, released.nextFocusRightId)
            val refresh = views.filterIsInstance<TextView>().single { it.text.toString() == "刷新" }
            assertEquals(views.filterIsInstance<TextView>().single { it.text.toString().startsWith("全部季 /") }.id, future.nextFocusUpId)
            assertEquals(episode.id, refresh.nextFocusUpId)
            future.performClick()
            assertEquals(upcoming, selected)
            assertTrue(selected!!.upcoming)
            label = "已预约"
            strip.refreshLabels()
            assertTrue(
                descendants(strip).filterIsInstance<TextView>().any { it.text.toString() == "已预约" }
            )
            strip.failed()
            descendants(strip)
                .filterIsInstance<TextView>()
                .single { it.text.toString() == "刷新" }
                .performClick()
            assertEquals(1, reloads)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
