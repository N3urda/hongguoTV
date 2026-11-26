// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import java.io.File
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class OfflineDownloadsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.filesDir, "offline")
    private val downloads get() = OfflineDownloads(context, OkHttpClient())

    private fun cached(id: String, size: Int = 4096): File =
        File(directory, "$id.mp4").apply {
            directory.mkdirs()
            writeBytes(ByteArray(size) { (it % 251).toByte() })
        }

    @Test
    fun unfinishedDownloadIsNeitherPlayableNorReportedAsDone() {
        File(directory, "ep-1.part").apply {
            directory.mkdirs()
            writeBytes(ByteArray(1024))
        }
        val offline = downloads
        assertNull(offline.file("ep-1"))
        assertEquals(OfflineDownloads.State.IDLE, offline.status("ep-1").state)
    }

    @Test
    fun finishedFileIsPlayableAndReportedAsDone() {
        val target = cached("ep-2")
        val offline = downloads
        assertEquals(target, offline.file("ep-2"))
        val status = offline.status("ep-2")
        assertEquals(OfflineDownloads.State.DONE, status.state)
        assertEquals(target.length(), status.total)
        assertEquals(100, status.percent)
    }

    @Test
    fun removeDeletesEveryFileOfTheEpisode() {
        cached("ep-3")
        File(directory, "ep-3.part").writeBytes(ByteArray(16))
        File(directory, "ep-3.size").writeText("4096")
        val offline = downloads
        assertNotNull(offline.file("ep-3"))
        offline.remove("ep-3")
        assertNull(offline.file("ep-3"))
        assertEquals(OfflineDownloads.State.IDLE, offline.status("ep-3").state)
        assertEquals(emptyList<String>(), directory.list()!!.filter { it.startsWith("ep-3") })
    }

    @Test
    fun cachedEpisodeFindsOnlyFinishedEpisodesOfThatSeries() {
        cached("ep-5")
        File(directory, "ep-5.json").writeText("""{"seriesId":"series-a","episode":"5"}""")
        cached("ep-6")
        File(directory, "ep-6.json").writeText("""{"seriesId":"series-b","episode":"1"}""")
        File(directory, "ep-7.part").writeBytes(ByteArray(16))
        File(directory, "ep-7.json").writeText("""{"seriesId":"series-c","episode":"2"}""")
        val offline = downloads
        assertEquals("ep-5", offline.cachedEpisode("series-a"))
        assertEquals("ep-6", offline.cachedEpisode("series-b"))
        assertNull(offline.cachedEpisode("series-c"))
        assertNull(offline.cachedEpisode("series-missing"))
    }

    @Test
    fun startingWithoutAPreparedStreamFailsInsteadOfDownloading() {
        val offline = downloads
        offline.start(
            "ep-4",
            RemoteVideo(OkHttpClient(), StreamInfo("https://example.invalid/ep-4.mp4", null, "720P")),
        )
        val status = offline.status("ep-4")
        assertEquals(OfflineDownloads.State.FAILED, status.state)
        assertEquals("视频尚未就绪", status.message)
        assertNull(offline.file("ep-4"))
    }
}
