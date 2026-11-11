package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ProducerWorksDialogTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun relatedChoicesBindPostersAndStillDispatchSelectedIndex() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val tools = TvTools(activity.get())
        try {
            val loaded = mutableListOf<String>()
            var selected = -1
            tools.choose("相关剧集", listOf("第二季 · 预约", "第三季 · 预约"), null, { selected = it },
                covers = listOf("https://example.com/2.jpg", "https://example.com/3.jpg"),
                loadCover = { _, url -> loaded += url })
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(2, views(dialog.window!!.decorView).filter { it.tag?.toString()?.startsWith("choice-") == true }
                .sumOf { views(it).filterIsInstance<ImageView>().size })
            assertEquals(listOf("https://example.com/2.jpg", "https://example.com/3.jpg"), loaded)
            views(dialog.window!!.decorView).single { it.tag == "choice-1" }.performClick()
            assertEquals(1, selected)
            assertFalse(dialog.isShowing)
        } finally { tools.destroy(); activity.pause().stop().destroy() }
    }

    @Test fun failedSearchPreservesVerifiedWorksAndOffersRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val anchor = TextView(activity.get())
        activity.get().setContentView(anchor)
        val repo = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val answer = when {
                "plan" in path -> """{"code":0,"data":[{"show_type":547,"video_data":[]}]}"""
                "multi-detail" in path -> """{"code":0,"data":[{"book_id":"123","media_id":"456","author":"制作方甲"}]}"""
                else -> """{"code":"search_rejected"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.toResponseBody()).build()
        }.build())
        var selected: Series? = null
        val current = Series("123", "当前作品", "https://example.com/cover.jpg")
        val controller = ProducerWorksDialog(activity.get(), repo, Producer("456", "制作方甲"), current,
            anchor, { _, _ -> }, { selected = it })
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            fun retry() = views(dialog.window!!.decorView).filterIsInstance<TextView>()
                .firstOrNull { it.text.toString() == "重试查找更多作品" }
            for (attempt in 0..200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (retry() != null) break
                Thread.sleep(10)
            }
            assertNotNull(retry())
            assertTrue(retry()!!.isEnabled)
            views(dialog.window!!.decorView).single { it.tag == "123" }.performClick()
            assertEquals(current, selected)
            assertFalse(dialog.isShowing)
        } finally { controller.close(); activity.pause().stop().destroy() }
    }
}
