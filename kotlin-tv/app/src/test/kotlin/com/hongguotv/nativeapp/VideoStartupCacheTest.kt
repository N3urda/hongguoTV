// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.datasource.DataSpec
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoStartupCacheTest {
    @Test
    fun boundedReadsCrossCacheAndHttpRangesAndCanReopenAtASeekPosition() {
        val file = ByteArray(1024 * 1024) { (it % 251).toByte() }
        java.nio.ByteBuffer.wrap(file)
            .putInt(8)
            .put("ftyp".toByteArray())
            .putInt(8)
            .put("moov".toByteArray())
        val ranges = mutableListOf<String>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val header = chain.request().header("Range")!!
                    ranges += header
                    val limits = header.removePrefix("bytes=").split('-').map(String::toInt)
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
        val video =
            RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).prepare()
        val source = VideoDataSource(video)
        try {
            val start = 256 * 1024 - 8
            val size = 600 * 1024
            assertEquals(
                size.toLong(),
                source.open(
                    DataSpec.Builder()
                        .setUri("https://example.test/video")
                        .setPosition(start.toLong())
                        .setLength(size.toLong())
                        .build()
                ),
            )
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32775)
            while (true) {
                val n = source.read(buffer, 7, 32768)
                if (n == -1) break
                out.write(buffer, 7, n)
            }
            assertArrayEquals(file.copyOfRange(start, start + size), out.toByteArray())
            assertEquals(
                listOf("bytes=0-262143", "bytes=262144-786431", "bytes=786432-${start+size-1}"),
                ranges,
            )
            source.close()
            source.open(
                DataSpec.Builder()
                    .setUri("https://example.test/video")
                    .setPosition(file.size - 3L)
                    .build()
            )
            val last = ByteArray(8)
            assertEquals(3, source.read(last, 2, 6))
            assertEquals(-1, source.read(last, 0, 8))
            assertArrayEquals(file.copyOfRange(file.size - 3, file.size), last.copyOfRange(2, 5))
        } finally {
            source.close()
            video.close()
        }
    }

    @Test
    fun uncachedPlayerReadReturnsBeforeTheRestOfTheHttpRangeArrives() {
        val file = ByteArray(1024 * 1024) { (it % 251).toByte() }
        java.nio.ByteBuffer.wrap(file)
            .putInt(8)
            .put("ftyp".toByteArray())
            .putInt(8)
            .put("moov".toByteArray())
        val tail = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    val range =
                        chain
                            .request()
                            .header("Range")!!
                            .removePrefix("bytes=")
                            .split('-')
                            .map(String::toInt)
                    var at = range[0]
                    val end = minOf(range[1], file.lastIndex)
                    val source =
                        object : okio.Source {
                            override fun timeout() = okio.Timeout.NONE

                            override fun close() {
                                if (range[0] >= 256 * 1024) tail.countDown()
                            }

                            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                if (at > end) return -1L
                                if (at >= range[0] + 8192 && range[0] >= 256 * 1024)
                                    check(tail.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                val n = minOf(byteCount.toInt(), 8192, end - at + 1)
                                sink.write(file, at, n)
                                at += n
                                return n.toLong()
                            }
                        }
                    val body =
                        object : ResponseBody() {
                            override fun contentType(): MediaType? = null

                            override fun contentLength() = (end - range[0] + 1).toLong()

                            private val buffered = source.buffer()

                            override fun source() = buffered
                        }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("partial")
                        .header("Content-Range", "bytes ${range[0]}-$end/${file.size}")
                        .body(body)
                        .build()
                }
                .build()
        val video =
            RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).prepare()
        val source = VideoDataSource(video)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            source.open(
                DataSpec.Builder()
                    .setUri("https://example.test/video")
                    .setPosition(256 * 1024L)
                    .build()
            )
            val bytes = ByteArray(16)
            val read = worker.submit<Int> { source.read(bytes, 0, bytes.size) }
            assertEquals(16, read.get(2, java.util.concurrent.TimeUnit.SECONDS).toInt())
            assertEquals(1L, tail.count)
            assertEquals(2, requests.get())
            assertArrayEquals(file.copyOfRange(256 * 1024, 256 * 1024 + 16), bytes)
        } finally {
            tail.countDown()
            source.close()
            video.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun firstPlayerReadUsesPreparedPrefixWithoutAnotherHttpRoundTrip() {
        val file = ByteArray(1024 * 1024)
        java.nio.ByteBuffer.wrap(file)
            .putInt(8)
            .put("ftyp".toByteArray())
            .putInt(8)
            .put("moov".toByteArray())
        var requests = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests++
                    val limits =
                        chain.request().header("Range")!!.removePrefix("bytes=").split('-').map {
                            it.toInt()
                        }
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
        val video =
            RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).prepare()
        val source = VideoDataSource(video)
        try {
            source.open(DataSpec.Builder().setUri("https://example.test/video").build())
            val result = ByteArray(16)
            assertEquals(16, source.read(result, 0, 16))
            assertArrayEquals(file.copyOf(16), result)
            assertEquals(1, requests)
        } finally {
            source.close()
            video.close()
        }
    }
}
