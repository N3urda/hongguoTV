package com.hongguotv.nativeapp

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.Series
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@LooperMode(LooperMode.Mode.PAUSED)
class ChildInteractionTest {
    private fun views(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    @Test fun remoteDeleteIsSinglePressAndVerificationLocksEveryInput() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        listOf("child-mode-v1.json", "child-mode-v1.json.bak", "child-mode-v1.json.new").forEach { File(activity.noBackupFilesDir, it).delete() }
        val store = ChildModeStore(activity)
        store.setup("123456", "123456")
        val ui = ChildModeUi(activity, store)
        val worker = ui.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(ui) as ExecutorService
        val latch = CountDownLatch(1)
        worker.execute { latch.await(5, TimeUnit.SECONDS) }
        try {
            ui.show()
            views(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<Button>()
                .first { it.text == "管理儿童片单" }.performClick()
            val dialog = ShadowDialog.getLatestDialog()
            val all = views(dialog.window!!.decorView)
            val display = all.first { it.contentDescription?.startsWith("已输入") == true } as TextView
            fun key(code: Int) {
                dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            key(KeyEvent.KEYCODE_1); key(KeyEvent.KEYCODE_2); key(KeyEvent.KEYCODE_DEL)
            assertEquals("●", display.text.toString())
            key(KeyEvent.KEYCODE_FORWARD_DEL)
            assertEquals("", display.text.toString())
            (1..6).forEach { key(KeyEvent.KEYCODE_0 + it) }
            all.filterIsInstance<Button>().first { it.text == "确认" }.performClick()
            assertEquals("●●●●●●", display.text.toString())
            assertFalse(all.filterIsInstance<ImageButton>().single().isEnabled)
            assertFalse(all.filterIsInstance<Button>().first { it.text == "1" }.isEnabled)
            key(KeyEvent.KEYCODE_DEL); key(KeyEvent.KEYCODE_FORWARD_DEL); key(KeyEvent.KEYCODE_9)
            all.filterIsInstance<ImageButton>().single().performClick()
            assertEquals("●●●●●●", display.text.toString())
            dialog.dismiss()
            latch.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertFalse(ui.showing)
        } finally { latch.countDown(); ui.destroy(); controller.pause().stop().destroy() }
    }

    @Test fun childCardsReuseSearchStyleAndSeparateRecentFromNameOrder() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val first = Series("1", "测试剧第一季")
        val second = Series("2", "测试剧第二季")
        val hidden = Series("3", "其他剧")
        val home = ChildHomeView(activity, listOf(second, first), listOf(
            WatchProgress(first, "10", 0, 1000, 10000, false, 100),
            WatchProgress(hidden, "30", 0, 1000, 10000, false, 300),
            WatchProgress(second, "20", 1, 2000, 10000, false, 200)
        ), Button(activity), { _, _ -> }, { _, _ -> })
        try {
            val list = home.getChildAt(0) as ListView
            val cards = (0 until list.adapter.count).flatMap { index ->
                views(list.adapter.getView(index, null, list)).filterIsInstance<SearchResultCard>().filter { it.visibility == View.VISIBLE }
            }
            assertEquals(listOf("recent:2", "recent:1", "allowed:1", "allowed:2"), cards.map { it.tag })
            assertTrue(cards.first().contentDescription.contains("第 2 集"))
        } finally { controller.pause().stop().destroy() }
    }
}
