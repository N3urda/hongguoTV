// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FeedStartupCacheTest {
    private fun session() = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }

    private val page = CatalogPage(listOf(Series("101", "缓存作品")), true)

    private fun feedResponse(ids: List<String>): String {
        val rows = org.json.JSONArray()
        ids.forEach {
            rows.put(org.json.JSONObject().put("series_id", it).put("series_title", "推荐$it"))
        }
        val card = org.json.JSONObject().put("video_data", rows)
        val cell = org.json.JSONObject().put("cell_data", org.json.JSONArray().put(card))
        val tab = org.json.JSONObject()
            .put("tab_type", 16)
            .put("session_id", "test-session")
            .put("cell_data", org.json.JSONArray().put(cell))
        return org.json.JSONObject()
            .put("code", 0)
            .put("data", org.json.JSONObject().put("tab_item", org.json.JSONArray().put(tab)))
            .toString()
    }

    @Test
    fun explicitRecoveryRemovesStuckCursorAndStarterBeforeFetchingFresh() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(activity.get())
        cache.save("guest", "recommend", page)
        cache.saveCursor("guest", "recommend", """{"session":"stuck","offset":500,"seen":["101"]}""")
        cache.saveCursor("guest", DramaCategory.COMIC.route, "comic-saved")
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(feedResponse(listOf("101")).toResponseBody()).build()
        }.build()
        val backend = PublicFeedBackend(720, http = http, startupCache = cache, playbackSession = session())
        try {
            assertThrows(NoNewRecommendationsException::class.java) {
                backend.recommendations(1, DramaFeedState().recommendationCursor)
            }
            assertEquals(3, requests)
            backend.resetRecommendations(null)
            assertNull(cache.loadCursor("guest", "recommend"))
            assertNull(cache.load("guest", "recommend"))
            assertEquals("comic-saved", cache.loadCursor("guest", DramaCategory.COMIC.route))
            val fresh = backend.recommendations(1, DramaFeedState().recommendationCursor)
            assertEquals("101", fresh.items.single().id)
            assertEquals(4, requests)
        } finally { backend.close(); activity.pause().stop().destroy() }
    }

    @Test
    fun cacheDisplayAndImmediateCloseDoNotReplayTheSameFirstWorkOnRestart() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        cache.save("guest", "recommend", page)
        var requests = 0
        fun transport() = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val offset = chain.request().url.queryParameter("offset")?.toInt() ?: 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(feedResponse(if(offset == 0) listOf("101", "202") else listOf("303")).toResponseBody()).build()
        }.build()
        try {
            val displayed = mutableListOf<String>()
            repeat(3) {
                val backend = PublicFeedBackend(720, http = transport(), startupCache = cache, playbackSession = session())
                try {
                    val cursor = DramaFeedState().recommendationCursor
                    val current = backend.recommendations(1, cursor).items.first()
                    displayed += current.id
                    backend.noteDisplayed(current, cursor)
                } finally {
                    backend.close()
                }
            }
            assertEquals(listOf("101", "202", "303"), displayed)
            // 首次使用本地首屏；换注册身份后重读入口，第三次跳过已看首批再取新内容。
            assertEquals(3, requests)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun displayedWorkIsSavedToItsOwnCursorAndSourceEvenAfterSwitchingBack() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        val http = OkHttpClient.Builder().addInterceptor { error("仅保存本地展示记录") }.build()
        val backend = PublicFeedBackend(720, http = http, startupCache = cache, playbackSession = session())
        try {
            val state = DramaFeedState()
            val recommend = state.recommendationCursor
            state.append(CatalogPage(listOf(Series("101", "推荐")), true), 1)
            backend.noteDisplayed(Series("101", "推荐"), recommend)
            val savedRecommend = cache.loadCursor("guest", "recommend")
            state.select(DramaCategory.COMIC)
            val comic = state.recommendationCursor
            state.append(CatalogPage(listOf(Series("202", "漫剧")), true), 1)
            backend.noteDisplayed(Series("202", "漫剧"), comic, DramaCategory.COMIC)
            assertEquals(savedRecommend, cache.loadCursor("guest", "recommend"))
            val savedComic = cache.loadCursor("guest", DramaCategory.COMIC.route)
            assertTrue(org.json.JSONObject(savedComic!!).getJSONArray("seen").toString().contains("202"))
            state.recommend()
            assertSame(recommend, state.recommendationCursor)
            backend.noteDisplayed(Series("303", "返回推荐"), state.recommendationCursor)
            assertEquals(savedComic, cache.loadCursor("guest", DramaCategory.COMIC.route))
            val saved = org.json.JSONObject(cache.loadCursor("guest", "recommend")!!).getJSONArray("seen").toString()
            assertTrue(saved.contains("101") && saved.contains("303"))
            assertFalse(saved.contains("202"))
            assertNull(cache.loadCursor("different-account", "recommend"))
        } finally {
            backend.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun starterCacheHitStillPersistsDisplayedWorkBeforeBackendCloses() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        cache.save("guest", "recommend", page)
        val http = OkHttpClient.Builder().addInterceptor { error("缓存命中不应请求网络") }.build()
        val backend = PublicFeedBackend(720, http = http, startupCache = cache, playbackSession = session())
        try {
            val cursor = DramaFeedState().recommendationCursor
            val result = backend.recommendations(1, cursor)
            backend.noteDisplayed(result.items.single(), cursor)
            backend.close()
            val saved = cache.loadCursor("guest", "recommend")
            assertNotNull("缓存首屏展示记录也必须能跨进程恢复", saved)
            assertTrue(org.json.JSONObject(saved!!).getJSONArray("seen").toString().contains("101"))
        } finally {
            backend.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun sourcesAreSeparateAndAccountChangeOrExpiryCannotReuseOldStarter() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var now = 1000L
        val cache = FeedStartupCache(controller.get()) { now }
        try {
            cache.save("test-account-a", "recommend", page)
            assertNotNull(cache.load("test-account-a", "recommend"))
            assertNull(cache.load("test-account-a", DramaCategory.COMIC.route))
            now += 600001
            assertNull(cache.load("test-account-a", "recommend"))
            cache.save("test-account-a", "recommend", page)
            assertNull(cache.load("test-account-b", "recommend"))
            assertNull(cache.load("test-account-a", "recommend"))
            cache.save("test-account-a", "recommend", page)
            controller
                .get()
                .getSharedPreferences(
                    "native-feed-startup-v1",
                    android.content.Context.MODE_PRIVATE,
                )
                .edit()
                .putInt("recommend", 42)
                .apply()
            assertNull(cache.load("test-account-a", "recommend"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun verifiedFirstEpisodeSurvivesBackendRecreationWithoutFeedOrDetailRequest() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cache = FeedStartupCache(controller.get()) { 1000L }
        cache.save("guest", "recommend", page)
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val file = ByteArray(1024 * 1024)
        java.nio.ByteBuffer.wrap(file)
            .putInt(8)
            .put("ftyp".toByteArray())
            .putInt(8)
            .put("moov".toByteArray())
        fun transport() =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    // 播放模型先读 video_tab 新接口；本用例只统计旧接口与视频分段请求。
                    if (req.url.encodedPath.startsWith("/reading/bookapi/video_tab/"))
                        return@addInterceptor Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("test")
                            .body("""{"code":100103,"message":"PARAM_INVALID","data":null}""".toResponseBody())
                            .build()
                    paths += req.url.encodedPath
                    val builder =
                        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("test")
                    if (req.header("Range") != null) {
                        val header = req.header("Range")!!
                        ranges += header
                        val limits = header.removePrefix("bytes=").split('-').map(String::toInt)
                        builder
                            .code(206)
                            .header("Content-Range", "bytes ${limits[0]}-${limits[1]}/${file.size}")
                            .body(file.copyOfRange(limits[0], limits[1] + 1).toResponseBody())
                            .build()
                    } else {
                        val json =
                            when (req.url.encodedPath) {
                                "/detail" ->
                                    """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"101","series_title":"缓存作品","vid_list":["10101","10102"]}}}};</script>"""
                                "/novel/player/multi_video_model/v1/" ->
                                    """{"data":{"10101":{"video_model":{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}}}}"""
                                else -> error("Unexpected request")
                            }
                        builder.code(200).body(json.toResponseBody()).build()
                    }
                }
                .build()
        try {
            repeat(2) {
                val backend =
                    PublicFeedBackend(
                        720,
                        http = transport(),
                        startupCache = cache,
                        playbackSession = testPlaybackDeviceSession(),
                    )
                try {
                    val result = backend.recommendations(1, DramaFeedState().recommendationCursor)
                    backend.trial(result.items.single()).use { clip ->
                        assertEquals("10101", clip.episodeId)
                        assertEquals(2, clip.episodeCount)
                        assertEquals(32 * 1024, clip.video.cachedBytes(0))
                    }
                } finally {
                    backend.close()
                }
            }
            assertEquals(1, paths.count { it == "/detail" })
            assertEquals(2, paths.count { it == "/novel/player/multi_video_model/v1/" })
            assertEquals(listOf("bytes=0-262143", "bytes=0-262143"), ranges)
            assertEquals(5, paths.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
