// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
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
class PosterCoverFillTest {
    private fun get(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun layout(view: View, width: Int = 960, height: Int = 540) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
    }

    private fun assertPosterAspect(posterWidth: Int, posterHeight: Int, context: String) {
        assertTrue("$context width must be positive ($posterWidth)", posterWidth > 0)
        val expected = (posterWidth / TvStyle.POSTER_ASPECT).roundToInt()
        assertTrue(
            "$context expected poster height ~$expected for width $posterWidth, got $posterHeight",
            abs(posterHeight - expected) <= 1,
        )
    }

    private fun unclippedHeight(view: View, root: View): Int {
        val rect = android.graphics.Rect(0, 0, view.width, view.height)
        var current: View = view
        while (current !== root) {
            val parent = current.parent as? ViewGroup ?: break
            rect.offset(current.left - parent.scrollX, current.top - parent.scrollY)
            val left = if (parent.clipToPadding) parent.paddingLeft else 0
            val top = if (parent.clipToPadding) parent.paddingTop else 0
            val right = parent.width - if (parent.clipToPadding) parent.paddingRight else 0
            val bottom = parent.height - if (parent.clipToPadding) parent.paddingBottom else 0
            if (!rect.intersect(left, top, right, bottom)) return 0
            current = parent
        }
        return if (rect.intersect(0, 0, root.width, root.height)) rect.height() else 0
    }

    @Test
    fun discoveryPostersKeepFullCoverAspectBeforeAndDuringScroll() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val upper = TextView(activity).apply { id = View.generateViewId(); isFocusable = true }
        val groups =
            listOf(
                DiscoveryGroup("genre", "类型", listOf(DiscoveryFilter("short_play", "真人剧"))),
                DiscoveryGroup("category_dim_role", "设定", (1..8).map { DiscoveryFilter("r$it", "设定$it") }),
                DiscoveryGroup("category_dim_epoch", "背景", (1..8).map { DiscoveryFilter("e$it", "背景$it") }),
                DiscoveryGroup("category_dim_theme", "主题", (1..8).map { DiscoveryFilter("t$it", "主题$it") }),
                DiscoveryGroup("sort", "推荐", listOf(DiscoveryFilter("hot_score", "最高热度"))),
                DiscoveryGroup("gender", "受众", listOf(DiscoveryFilter("0", "女频"))),
            )
        val state =
            DiscoveryBrowserState().apply {
                loaded = true
                this.groups = groups
                expanded = true // This case verifies scrolling with the filter header expanded.
                items = (1..18).map { Series("$it", "找剧作品$it", badge = "全80集") }
            }
        val screen =
            DiscoveryScreen(activity, state, upper, { _, _ -> }, {}, { _, _ -> }) { _, _, _ -> }
        try {
            activity.setContentView(screen)
            screen.start(false)
            layout(screen, 864, 400)
            layout(screen, 864, 400)
            assertEquals(0, screen.scrollY)
            val browser = descendants(screen).filterIsInstance<ContinuousCatalogView>().single()
            val unscrolledCard = browser.grid.getChildAt(0) as CatalogPosterCard
            assertEquals(ImageView.ScaleType.CENTER_CROP, unscrolledCard.image.scaleType)
            assertPosterAspect(
                unscrolledCard.poster.width,
                unscrolledCard.poster.height,
                "Unscrolled DiscoveryScreen card",
            )
            val unscrolledHeight = unscrolledCard.poster.height

            browser.enter()
            browser.grid.setSelection(6)
            layout(screen, 864, 400)
            layout(screen, 864, 400)
            assertTrue(screen.scrollY > 0)
            val scrolledCard = browser.grid.getChildAt(0) as CatalogPosterCard
            assertEquals(unscrolledHeight, scrolledCard.poster.height)
            assertPosterAspect(
                scrolledCard.poster.width,
                scrolledCard.poster.height,
                "Scrolled DiscoveryScreen card",
            )
        } finally {
            screen.close()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun rankingAndMultiColumnSearchCardsMatchColumnPosterAspect() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val nav = (0..6).map { TextView(activity).apply { id = View.generateViewId(); isFocusable = true } }
            val items = (1..12).map { Series("$it", "榜单作品$it", badge = "热播") }
            val positions =
                items.mapIndexed { idx, s -> s.id to RankPosition(idx + 1, "${100 - idx}万") }.toMap()
            val rankState =
                RankBrowserState().apply {
                    endRefresh(
                        RankBoard.ALL,
                        RankSnapshot(
                            RankBoard.ALL,
                            items,
                            ComicRanking(positions, 1, "今日更新"),
                        ),
                    )
                }
            val ranking =
                RankingScreen(
                    activity,
                    rankState,
                    nav,
                    TvTools(activity),
                    { _, _ -> },
                    {},
                    { _, _ -> },
                ) { _, _ -> }
            activity.setContentView(
                FrameLayout(activity).apply {
                    addView(ranking, FrameLayout.LayoutParams(864, 427))
                }
            )
            layout(activity.window.decorView, 960, 540)
            val rankBrowser = descendants(ranking).filterIsInstance<ContinuousCatalogView>().single()
            rankBrowser.enter()
            layout(activity.window.decorView, 960, 540)
            layout(activity.window.decorView, 960, 540)
            val rankCard = rankBrowser.grid.getChildAt(0) as CatalogPosterCard
            assertEquals(ImageView.ScaleType.CENTER_CROP, rankCard.image.scaleType)
            assertPosterAspect(rankCard.poster.width, rankCard.poster.height, "RankingScreen card")
            assertEquals(rankCard.title.height, unclippedHeight(rankCard.title, ranking))
            assertEquals(rankCard.badge.height, unclippedHeight(rankCard.badge, ranking))

            for (columns in listOf(2, 3, 4)) {
                val upper = TextView(activity).apply { id = View.generateViewId() }
                val searchBrowser =
                    ContinuousCatalogView(
                        activity,
                        columns,
                        upper,
                        { item, recycled ->
                            (recycled as? CatalogPosterCard ?: CatalogPosterCard(activity)).apply {
                                show(item)
                            }
                        },
                        {},
                        {},
                        { _, _ -> },
                        {},
                    )
                activity.setContentView(searchBrowser)
                searchBrowser.show(items, false)
                layout(searchBrowser, 460, 320)
                val card = searchBrowser.grid.getChildAt(0) as CatalogPosterCard
                assertEquals(ImageView.ScaleType.CENTER_CROP, card.image.scaleType)
                assertPosterAspect(
                    card.poster.width,
                    card.poster.height,
                    "Search $columns-column card",
                )
                searchBrowser.close()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [26], qualifiers = "w960dp-h540dp-land-xhdpi")
    fun favoritesHistoryAndReservationsDoNotShrinkPostersAndKeepTitleAndBadgeVisible() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = controller.get()
        try {
            val library = get(activity, "library") as Library
            (get(library, "loader") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            val account = get(activity, "cloudAccount") as AccountController
            (get(account, "worker") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()

            val series = Series("fav-1", "万妖图录传第十四季收藏测试", badge = "全60集")
            val local = BackupProgress(series, "ep-1", 0, 5_000, 60_000, false, 1_000)
            Library::class
                .java
                .getDeclaredField("index")
                .apply { isAccessible = true }
                .set(
                    library,
                    LibraryIndex(
                        BackupData(
                            listOf(series),
                            listOf(local),
                            emptySet(),
                            emptyList(),
                            BackupSettings(),
                        )
                    ),
                )
            account.onHistoryChanged = null
            set(account, "credentials", AccountCredentials("test-only"))
            set(account, "busy", true)
            set(
                account,
                "reservations",
                mapOf(
                    true to
                        ReservationState(
                            listOf(CloudReservation(series, true, 1L)),
                            loaded = true,
                            loading = true,
                        ),
                    false to ReservationState(emptyList(), loaded = true, loading = true),
                ),
            )

            val switchTab =
                MainActivity::class
                    .java
                    .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            val decor = activity.window.decorView
            for (tabIndex in listOf(3, 4, 5)) {
                switchTab.invoke(activity, tabIndex)
                layout(decor, 1920, 1080)
                layout(decor, 1920, 1080)
                @Suppress("UNCHECKED_CAST")
                val cards = get(activity, "catalogCards") as Map<String, View>
                val card = cards.getValue("fav-1") as LinearLayout
                card.requestFocus()
                layout(decor, 1920, 1080)
                val artwork = card.getChildAt(0) as FrameLayout
                val title = card.getChildAt(1) as TextView
                val badge = card.getChildAt(2) as TextView
                val image = artwork.getChildAt(0) as ImageView
                assertEquals(ImageView.ScaleType.CENTER_CROP, image.scaleType)
                assertPosterAspect(artwork.width, artwork.height, "MainActivity tab $tabIndex card")
                assertEquals(
                    "Tab $tabIndex card title must be unclipped in 1920x1080@320dpi",
                    title.height,
                    unclippedHeight(title, decor),
                )
                assertEquals(
                    "Tab $tabIndex card badge must be unclipped in 1920x1080@320dpi",
                    badge.height,
                    unclippedHeight(badge, decor),
                )
            }
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun homeShelfAndRelatedWorksKeepFullPosterAspectInCompactHeight() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val upper = TextView(activity).apply { id = View.generateViewId(); isFocusable = true }
            val home =
                HomeScreen(activity, upper, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
            activity.setContentView(home)
            home.render(
                listOf(
                    HomeScreen.Shelf(
                        "hot",
                        "热门推荐",
                        listOf(HomeScreen.Entry(Series("h1", "首页测试剧"), "全50集")),
                    )
                ),
                focus = "",
                requestFocus = false,
                more = null,
            )
            layout(home, 864, 240)
            layout(home, 864, 240)
            val homeImage = descendants(home).filterIsInstance<ImageView>().single()
            assertEquals(ImageView.ScaleType.CENTER_CROP, homeImage.scaleType)
            assertPosterAspect(homeImage.width, homeImage.height, "HomeScreen shelf card")

            val related =
                RelatedWorksView(activity, emptyList(), { _, _ -> }, { "预约" }, { _, _ -> }, {})
            activity.setContentView(related)
            related.show(listOf(RelatedWork(Series("r1", "相关第二季"), "第2季", false, 2)))
            layout(related, 864, 360)
            val relatedImage = descendants(related).filterIsInstance<ImageView>().single()
            assertEquals(ImageView.ScaleType.CENTER_CROP, relatedImage.scaleType)
            assertPosterAspect(relatedImage.width, relatedImage.height, "RelatedWorksView card")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun artworkLoaderDecodesCoverDimensionsForCenterCropWithoutUndersizing() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val loader = ArtworkLoader(activity)
        try {
            val source = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            val bytes =
                ByteArrayOutputStream()
                    .also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
            source.recycle()
            val cache = ArtworkCache(File(activity.applicationContext.cacheDir, "artwork-v1"))
            val url = "https://example.invalid/cover-3x4.png"
            cache.put(url, bytes)

            val view =
                ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            activity.setContentView(
                FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(140, 200)) }
            )
            layout(activity.window.decorView, 960, 540)
            loader.load(view, url, ArtworkLoader.PRIORITY_FOCUSED)

            val deadline = System.currentTimeMillis() + 5_000
            while (view.drawable == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                ShadowLooper.idleMainLooper()
            }
            val decoded = (view.drawable as? BitmapDrawable)?.bitmap
            assertNotNull("Expected decoded cover bitmap", decoded)
            assertTrue("Decoded width ${decoded!!.width} must cover 140px", decoded.width >= 140)
            assertTrue("Decoded height ${decoded.height} must cover 200px", decoded.height >= 200)
        } finally {
            loader.close()
            controller.pause().stop().destroy()
        }
    }
}
