// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.*
import android.widget.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TvSocialTest {
    @Test
    fun timedDiagnosticsExposeCountsWithoutIdentityAndSettingsCanScroll() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val social = TvSocial(activity, { null }, { null })
        try {
            val page = JSONObject().put("items", JSONArray(listOf(
                JSONObject().put("id", "test-id-private").put("text", "测试").put("timeMS", 0),
            )))
            val field = social.javaClass.getDeclaredField("pages").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(social) as MutableMap<Long, JSONObject>)[0] = page
            social.settings(Button(activity))
            val settings = ShadowDialog.getLatestDialog()
            assertTrue(settings.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) is ScrollView)
            views(settings.window!!.decorView).single { it.text.toString().startsWith("弹幕排查") }.performClick()
            views(settings.window!!.decorView).single { it.text.toString() == "查看读取诊断" }.performClick()
            val report = views(ShadowDialog.getLatestDialog().window!!.decorView).joinToString { it.text.toString() }
            assertTrue(report.contains("弹幕诊断 D3"))
            assertTrue(report.contains("去重 1 条"))
            assertFalse(report.contains("test-id-private"))
        } finally {
            social.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun commonSettingsKeepFocusAndAdvancedDensityLabelsStayInSync() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val social = TvSocial(activity, { null }, { null })
        try {
            social.settings(Button(activity))
            val dialog = ShadowDialog.getLatestDialog()
            fun row(prefix: String) = views(dialog.window!!.decorView).single { it.text.toString().startsWith(prefix) }
            assertFalse(row("精确行数").isShown)
            assertFalse(row("重新读取弹幕").isShown)
            val density = row("密度：")
            density.requestFocus()
            density.performClick()
            assertEquals("密度：多", density.text.toString())
            assertTrue(density.hasFocus())
            row("更多设置").performClick()
            assertTrue(row("精确行数").isShown)
            assertEquals("精确行数：12 行", row("精确行数").text.toString())
            row("精确行数").performClick()
            assertEquals("密度：标准（自动）", density.text.toString())
            row("精确行数").performClick()
            row("精确行数").performClick()
            assertEquals("密度：自定义 6 行", density.text.toString())
            val page = JSONObject().put("items", JSONArray())
            val field = social.javaClass.getDeclaredField("pages").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val pages = field.get(social) as MutableMap<Long, JSONObject>
            pages[0] = page
            val enabled = row("弹幕：")
            enabled.requestFocus()
            enabled.performClick()
            assertTrue(dialog.isShowing)
            assertTrue(enabled.hasFocus())
            assertEquals(1, pages.size)
            row("弹幕排查").performClick()
            row("重新读取弹幕").performClick()
            assertTrue(dialog.isShowing)
            assertTrue(pages.isEmpty())
            dialog.dismiss()
        } finally {
            social.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun knownEmojiUsesImageAndUnknownLabelIsPreserved() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val text =
                CommentEmoji(controller.get()).render("你好[笑哭]【赞】[未知表情]", 32f)
                    as android.text.Spanned
            assertEquals(
                2,
                text.getSpans(0, text.length, android.text.style.ImageSpan::class.java).size,
            )
            assertTrue(text.toString().contains("[未知表情]"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun commentSignatureWorksOnAndroid6And8() {
        val headers =
            com.hongguotv.core
                .CommentSignature()
                .headers("aid=8662&device_id=123", 1700000000123, ByteArray(11) { it.toByte() })
        assertEquals("840420e100007c44bd0bfe74163888622f1454edf858fca6234c", headers["X-Gorgon"])
        assertEquals("AgMEBVbVr6bCGXEl3RbL1aAkpFGUXoV+4yYWolKDMC0DpDXW", headers["X-Ladon"])
        assertTrue(headers["X-Argus"]!!.length > 100)
    }

    private fun views(v: View): List<TextView> =
        (if (v is TextView) listOf(v) else emptyList()) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    private fun flush(social: TvSocial) {
        val f = social.javaClass.getDeclaredField("worker").apply { isAccessible = true }
        (f.get(social) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        Thread.sleep(80)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun remoteCommentContinuationDeduplicatesAndRestoresFocus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val anchor =
            Button(activity).apply {
                text = "评论"
                isFocusableInTouchMode = true
            }
        root.addView(anchor)
        activity.setContentView(root)
        val calls = mutableListOf<String>()
        val social =
            TvSocial(
                activity,
                { null },
                { "123456" to "234567" },
                { _, _, cursor ->
                    synchronized(calls) { calls.add(cursor) }
                    val rows = JSONArray()
                    for (id in if (cursor.isEmpty()) listOf("1", "2") else listOf("2", "3")) rows
                        .put(
                            JSONObject()
                                .put("id", id)
                                .put("author", "作者$id")
                                .put("content", "评论$id")
                                .put("created", 1700000000)
                                .put("likes", 2)
                        )
                    JSONObject()
                        .put("items", rows)
                        .put("total", 3)
                        .put("hasMore", cursor.isEmpty())
                        .put("nextCursor", if (cursor.isEmpty()) "next" else "")
                },
            )
        try {
            social.attach(root)
            social.showComments(anchor)
            flush(social)
            val d = ShadowDialog.getLatestDialog()
            val decor = d.window!!.decorView
            assertTrue(views(decor).any { it.text.contains("已加载 2 条") })
            views(decor).first { it.text == "加载更多评论" }.performClick()
            flush(social)
            assertEquals(listOf("", "next"), calls)
            assertTrue(views(decor).any { it.text.contains("已加载 3 条") })
            assertEquals(1, views(decor).count { it.text.contains("作者2") })
            views(decor).first { it.text == "关闭" }.performClick()
            ShadowLooper.idleMainLooper()
            assertFalse(d.isShowing)
            assertTrue(anchor.hasFocus())
        } finally {
            social.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun remoteSettingsPersistAndResetClearsOverlay() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controller.get()
        val root = FrameLayout(a)
        val anchor = Button(a)
        root.addView(anchor)
        a.setContentView(root)
        val social = TvSocial(a, { null }, { null })
        try {
            social.attach(root)
            social.settings(anchor)
            val d = ShadowDialog.getLatestDialog()
            val buttons = views(d.window!!.decorView)
            buttons.first { it.text.toString().startsWith("弹幕：") }.performClick()
            assertFalse(a.getSharedPreferences("social", 0).getBoolean("enabled", true))
            buttons.first { it.text.toString().startsWith("字号：") }.performClick()
            assertEquals(28, a.getSharedPreferences("social", 0).getInt("font", 24))
            social.foreground(false)
            social.reset()
            d.dismiss()
        } finally {
            social.destroy()
            controller.pause().stop().destroy()
        }
    }
}
