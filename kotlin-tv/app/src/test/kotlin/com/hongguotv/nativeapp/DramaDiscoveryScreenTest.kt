package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.hongguotv.core.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
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
class DramaDiscoveryScreenTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    private fun pump(activity: MainActivity) {
        (get(activity, "workJob") as? Future<*>)?.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
        layout(activity)
    }

    private fun layout(activity: MainActivity) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 960, 540)
        ShadowLooper.idleMainLooper()
    }

    private fun body(request: Request): JSONObject {
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun views(view: View): List<View> =
        listOf(view) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList())

    private fun response(request: Request): String {
        if (request.url.encodedPath == "/incent_resource/suggestion")
            return """{"suggest_list":[{"name":"大佬，你该出手了"}]}"""
        if (request.url.encodedPath == "/api/search/result")
            return """{"hasMore":false,"searchList":[{"series_id":"900","series_name":"搜索结果剧"}]}"""
        val input = body(request)
        val offset = input.getInt("offset")
        val real =
            input.getJSONObject("select_items").getJSONArray("genre").optString(0) == "short_play"
        val rows = JSONArray()
        fun row(type: String, vararg options: Pair<String, String>) {
            rows.put(
                JSONObject()
                    .put("type", type)
                    .put(
                        "items",
                        JSONArray().apply {
                            options.forEach { (id, name) ->
                                put(JSONObject().put("selector_item_id", id).put("show_name", name))
                            }
                        },
                    )
            )
        }
        row("genre", "short_play" to "真人剧", "comic_series" to "漫剧", "ai_series" to "AI剧")
        row("category_dim_theme", (if (real) "cate_5000" else "cate_7") to if (real) "爱情" else "玄幻")
        row("category_dim_role", "cate_36" to "重生")
        row("category_dim_epoch", "cate_1" to "都市")
        row("sort", "hot_score" to "最高热度")
        row("gender", "0" to "女频", "1" to "男频")
        row("online_time", "days_7" to "7天内上新")
        val data =
            JSONObject()
                .put(
                    "video_data",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("series_id", "${101+offset}")
                                .put("title", "分类第${offset+1}页")
                        ),
                )
                .put("has_more", offset == 0)
                .put("next_offset", offset + 1)
                .put("session_id", "test-cursor")
        if (input.getBoolean("need_selector_panel")) data.put("selector_rows", rows)
        return JSONObject().put("code", 0).put("data", data).toString()
    }

    @Test
    fun combinedFiltersPagingSearchAndReturnKeepServerConditionsAndSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = get(activity, "cloudAccount") as AccountController
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
            val failNext = java.util.concurrent.atomic.AtomicBoolean(false)
            set(
                activity,
                "repository",
                ContentRepository(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val request = chain.request()
                            requests += request
                            if (failNext.compareAndSet(true, false))
                                throw java.io.IOException("测试断网")
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(response(request).toResponseBody())
                                .build()
                        }
                        .build()
                ),
            )
            set(activity, "foreground", true)
            MainActivity::class
                .java
                .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, 1)
            pump(activity)
            val root = activity.findViewById<View>(android.R.id.content)
            assertNotNull(
                root.findViewById<View>((get(activity, "searchInput") as EditText).nextFocusDownId)
            )
            for (label in listOf("主题", "设定", "背景", "推荐", "受众", "时间")) assertTrue(
                label,
                texts(root).any { it.text.toString() == label },
            )
            texts(root).single { it.text.toString() == "重生" }.performClick()
            pump(activity)
            texts(root).single { it.text.toString() == "都市" }.performClick()
            pump(activity)
            val selected = body(requests.last()).getJSONObject("select_items")
            assertEquals("cate_36", selected.getJSONArray("category_dim_role").getString(0))
            assertEquals("cate_1", selected.getJSONArray("category_dim_epoch").getString(0))
            assertTrue(texts(root).single { it.text.toString() == "都市" }.hasFocus())
            val browser = views(root).filterIsInstance<ContinuousCatalogView>().single()
            failNext.set(true)
            browser.next.performClick()
            pump(activity)
            assertEquals(1, browser.grid.adapter.count)
            assertEquals("重试加载", browser.next.text.toString())
            browser.next.performClick()
            pump(activity)
            assertEquals("test-cursor", body(requests.last()).getString("session_id"))
            assertEquals(1, body(requests.last()).getInt("offset"))
            assertEquals(2, browser.grid.adapter.count)
            browser.grid.setSelection(1)
            browser.enter()
            layout(activity)
            assertEquals("102", browser.capture()?.id)
            val state = get(activity, "discoveryBrowser") as DiscoveryBrowserState
            assertTrue(state.expanded)
            val input = get(activity, "searchInput") as EditText
            input.setText("大佬")
            texts(root).single { it.text.toString() == "搜索" }.performClick()
            pump(activity)
            assertTrue(texts(root).any { it.text.toString() == "搜索结果剧" })
            val count = requests.size
            texts(root).single { it.text.toString() == "返回分类" }.performClick()
            layout(activity)
            assertEquals(count, requests.size)
            var restored = views(root).filterIsInstance<ContinuousCatalogView>().single()
            assertEquals("102", restored.capture()?.id)
            assertEquals(2, restored.grid.adapter.count)
            assertEquals("cate_36", state.selection.values["category_dim_role"])
            for (label in listOf("重生", "都市")) {
                val chip = texts(root).single { it.text.toString() == label }
                assertTrue("Restored filter $label", chip.isSelected)
            }
            val savedSelection = state.selection
            set(activity, "detail", Detail(Series("102", "分类第2页"), listOf("1001")))
            MainActivity::class
                .java
                .getDeclaredMethod("showDetail", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, false)
            pump(activity)
            MainActivity::class
                .java
                .getDeclaredMethod("goBack")
                .apply { isAccessible = true }
                .invoke(activity)
            layout(activity)
            restored = views(root).filterIsInstance<ContinuousCatalogView>().single()
            assertEquals("102", restored.capture()?.id)
            assertEquals(savedSelection, state.selection)
            for (label in listOf("重生", "都市")) assertTrue(
                texts(root).single { it.text.toString() == label }.isSelected
            )
            MainActivity::class
                .java
                .getDeclaredMethod("goBack")
                .apply { isAccessible = true }
                .invoke(activity)
            layout(activity)
            assertEquals("101", restored.capture()?.id)
            assertTrue(texts(root).single { it.text.toString() == "找剧" }.hasFocus())
            assertEquals(0, (get(activity, "discoveryScreen") as DiscoveryScreen).scrollY)
            texts(root).single { it.text.toString() == "真人剧" }.performClick()
            pump(activity)
            assertEquals(mapOf("genre" to "short_play"), state.selection.values)
            assertTrue(texts(root).any { it.text.toString() == "爱情" })
            assertFalse(texts(root).any { it.text.toString() == "玄幻" })
        } finally {
            controller.destroy()
        }
    }
}
