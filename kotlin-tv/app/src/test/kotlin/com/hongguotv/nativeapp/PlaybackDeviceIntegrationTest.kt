package com.hongguotv.nativeapp

import com.hongguotv.core.PlaybackDeviceRegistrationException
import com.hongguotv.core.PlaybackDeviceSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
class PlaybackDeviceIntegrationTest {
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test
    fun homepageAndFullscreenShareThePersistentDeviceAndAccountProvider() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val session = field(activity, "playbackSession") as PlaybackDeviceSession
        val contexts = field(activity, "playbackContexts") as com.hongguotv.core.PlaybackContextProvider
        val home = PublicFeedBackend(720, playbackSession = session, playbackContexts = contexts)
        try {
            val fullscreenRepository = field(controller.get(), "repository")!!
            val homeRepository = field(home, "repository")!!
            assertSame(session, field(fullscreenRepository, "playbackSession"))
            assertSame(contexts, field(fullscreenRepository, "playbackContexts"))
            assertSame(contexts, field(homeRepository, "playbackContexts"))
            assertFalse(contexts.accountPlaybackEnabled)
            assertSame(
                field(fullscreenRepository, "playbackSession"),
                field(homeRepository, "playbackSession"),
            )
        } finally {
            home.close()
            controller.destroy()
        }
    }

    @Test
    fun registrationFailureHasItsOwnSafeCategory() {
        val error =
            PlaybackDeviceRegistrationException(java.io.IOException("test-only private response"))
        assertEquals("播放设备注册失败", playbackFailureSummary(error))
        assertFalse(playbackFailureSummary(error).contains("private response"))
    }
}
