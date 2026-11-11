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
    fun homepageAndFullscreenUseTheSameLazyAnonymousSession() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val home = PublicFeedBackend(720)
        try {
            val fullscreenRepository = field(controller.get(), "repository")!!
            val homeRepository = field(home, "repository")!!
            assertSame(PlaybackDeviceSession.shared, field(fullscreenRepository, "playbackSession"))
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
