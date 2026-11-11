// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RemoteVideoStreamingTest {
    @Test
    fun smallStartupProbeFetchesOnlyTheCompleteIndexBeforeHandOff() {
        val file = fixture(48 * 1024)
        val ranges = mutableListOf<String>()
        var delivered = 0L
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val range = chain.request().header("Range")!!
                    ranges += range
                    val limits = range.removePrefix("bytes=").split('-').map(String::toInt)
                    val end = minOf(file.lastIndex, limits[1])
                    val raw = okio.Buffer().write(file, limits[0], end - limits[0] + 1)
                    val monitored =
                        object : okio.ForwardingSource(raw) {
                                override fun read(sink: okio.Buffer, byteCount: Long): Long =
                                    super.read(sink, byteCount).also { if (it > 0) delivered += it }
                            }
                            .buffer()
                    val body =
                        object : ResponseBody() {
                            override fun contentType(): MediaType? = null

                            override fun contentLength() = (end - limits[0] + 1).toLong()

                            override fun source() = monitored
                        }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("partial")
                        .header("Content-Range", "bytes ${limits[0]}-$end/${file.size}")
                        .body(body)
                        .build()
                }
                .build()
        RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).use { video ->
            video.prepare(32 * 1024)
            assertEquals(listOf("bytes=0-262143"), ranges)
            assertEquals(48 * 1024, video.cachedBytes(0))
            assertEquals(48 * 1024L, delivered)
            assertArrayEquals(file.copyOfRange(40 * 1024, 44 * 1024), video.read(40 * 1024L, 4096))
            assertEquals(1, ranges.size)
        }
    }

    private fun fixture(headerSize: Int = 16): ByteArray =
        ByteArray(1024 * 1024) { (it % 239).toByte() }
            .also {
                java.nio.ByteBuffer.wrap(it)
                    .putInt(8)
                    .put("ftyp".toByteArray())
                    .putInt(headerSize - 8)
                    .put("moov".toByteArray())
            }

    @Test
    fun completeLargeHeaderIsRetainedInsteadOfDownloadedAgainByPlayer() {
        val file = fixture(300 * 1024)
        var requests = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests++
                    val limits =
                        chain
                            .request()
                            .header("Range")!!
                            .removePrefix("bytes=")
                            .split('-')
                            .map(String::toInt)
                    val end = minOf(file.lastIndex, limits[1])
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("partial")
                        .header("Content-Range", "bytes ${limits[0]}-$end/${file.size}")
                        .body(file.copyOfRange(limits[0], end + 1).toResponseBody())
                        .build()
                }
                .build()
        RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).use { video ->
            video.prepare()
            assertEquals(2, requests)
            assertEquals(300 * 1024, video.cachedBytes(0))
            assertArrayEquals(
                file.copyOfRange(260 * 1024, 290 * 1024),
                video.read(260 * 1024L, 30 * 1024),
            )
            assertEquals(2, requests)
        }
    }

    @Test
    fun streamedRangesRejectChangedResourcesWrongRangesAndUnexpectedEof() {
        val file = fixture()
        var mode = "prepare"
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val limits =
                        chain
                            .request()
                            .header("Range")!!
                            .removePrefix("bytes=")
                            .split('-')
                            .map(String::toInt)
                    val end = minOf(file.lastIndex, limits[1])
                    val header =
                        when (mode) {
                            "changed" -> "bytes ${limits[0]}-$end/${file.size+1}"
                            "wrong" -> "bytes ${limits[0]+1}-$end/${file.size}"
                            else -> "bytes ${limits[0]}-$end/${file.size}"
                        }
                    val body =
                        if (mode == "truncated") byteArrayOf(1, 2, 3)
                        else file.copyOfRange(limits[0], end + 1)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (mode == "full") 200 else 206)
                        .message("test")
                        .header("Content-Range", header)
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val video =
            RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).prepare()
        try {
            for (invalid in listOf("changed", "wrong", "full")) {
                mode = invalid
                assertThrows(IOException::class.java) { video.openRead(300 * 1024L, 32) }
            }
            mode = "truncated"
            video.openRead(300 * 1024L, 32).use { input ->
                assertEquals(3, input.read(ByteArray(32)))
                assertThrows(java.io.EOFException::class.java) { input.read(ByteArray(32)) }
            }
            mode = "valid"
            val reader = video.openRead(300 * 1024L, 32)
            video.close()
            assertThrows(IOException::class.java) { reader.read() }
            reader.close()
            assertThrows(IOException::class.java) { video.openRead(300 * 1024L, 32) }
        } finally {
            video.close()
        }
    }
}
