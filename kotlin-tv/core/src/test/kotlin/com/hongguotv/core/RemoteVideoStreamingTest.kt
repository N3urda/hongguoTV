// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RemoteVideoStreamingTest {
    private val primary = "https://primary.example.test/video"
    private val backup = "https://backup.example.test/video"

    @Test
    fun streamingDisablesWallClockDeadlineButKeepsProbeDeadline() {
        val file = fixture()
        val deadlines = mutableListOf<Long>()
        val http = OkHttpClient.Builder()
            .callTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                deadlines += chain.call().timeout().timeoutNanos()
                val limits = chain.request().header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
                val end = minOf(file.lastIndex, limits[1])
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(206).message("partial")
                    .header("Content-Range", "bytes ${limits[0]}-$end/${file.size}")
                    .body(file.copyOfRange(limits[0], end + 1).toResponseBody()).build()
            }.build()
        RemoteVideo(http, StreamInfo("https://example.test/video", null, "测试")).use { video ->
            video.prepare()
            video.openRead(300 * 1024L, 32).use { input ->
                assertArrayEquals(file.copyOfRange(300 * 1024, 300 * 1024 + 32), input.readBytes())
            }
        }
        assertEquals(listOf(java.util.concurrent.TimeUnit.SECONDS.toNanos(35), 0L), deadlines)
    }

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

    @Test
    fun preparedSourceKeepsLocalBytesAndUsesTheNewTransportAfterThePreviewCloses() {
        val file = fixture()
        val previewRanges = mutableListOf<String>()
        val playerRanges = mutableListOf<String>()
        fun transport(ranges: MutableList<String>) = OkHttpClient.Builder().addInterceptor { chain ->
            val range = chain.request().header("Range")!!
            ranges += range
            val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(206).message("Partial Content")
                .header("Content-Range", "bytes $start-$end/${file.size}")
                .body(file.copyOfRange(start, end + 1).toResponseBody()).build()
        }.build()
        val preview = RemoteVideo(transport(previewRanges), StreamInfo("https://example.test/video", null, "测试"))
        val prepared = preview.use {
            assertNull(it.preparedSource())
            it.prepare()
            checkNotNull(it.preparedSource())
        }
        assertNull(preview.preparedSource())
        prepared.open(transport(playerRanges)).use { player ->
            assertArrayEquals(file.copyOfRange(0, 32), player.read(0, 32))
            assertTrue(playerRanges.isEmpty())
            player.openRead(300 * 1024L, 64).use { input ->
                assertArrayEquals(file.copyOfRange(300 * 1024, 300 * 1024 + 64), input.readBytes())
            }
        }
        assertEquals(listOf("bytes=0-262143"), previewRanges)
        assertEquals(listOf("bytes=307200-307263"), playerRanges)
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

    private fun partial(request: Request, file: ByteArray): Response {
        val (start, requestedEnd) = request.header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
        val end = minOf(file.lastIndex, requestedEnd)
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(206).message("Partial Content").header("Content-Range", "bytes $start-$end/${file.size}")
            .body(file.copyOfRange(start, end + 1).toResponseBody()).build()
    }

    private fun mediaError(request: Request, status: Int, retryAfter: String? = null): Response {
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(status).message("fixture").body("".toResponseBody())
        retryAfter?.let { response.header("Retry-After", it) }
        return response.build()
    }

    @Test
    fun switchesToTheSameRenditionBackupWhenThePrimaryReturnsA5xxAtStartup() {
        val file = fixture()
        val hosts = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hosts += request.url.host
            if (request.url.host == "primary.example.test") mediaError(request, 503)
            else partial(request, file)
        }.build()
        RemoteVideo(http, StreamInfo(primary, null, "720P", backup)).use { video ->
            video.prepare()
            assertEquals(file.size.toLong(), video.total)
            assertEquals("720P", video.info.quality)
            assertEquals(backup, video.info.url)
            assertNull(video.info.backupUrl)
        }
        assertEquals(listOf("primary.example.test", "backup.example.test"), hosts)
    }

    @Test
    fun continuesAnInterruptedRangeFromTheNextByteAfterVerifyingTheBackupIndex() {
        val file = fixture()
        val requests = mutableListOf<Pair<String, String?>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val range = request.header("Range")
            requests += request.url.host to range
            val response = partial(request, file)
            if (request.url.host != "primary.example.test" || range != "bytes=307200-307231")
                return@addInterceptor response
            val complete = checkNotNull(response.body)
            val truncated = object : okio.ForwardingSource(complete.source()) {
                private var readOnce = false

                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    if (readOnce) throw SocketTimeoutException("fixture read timeout")
                    readOnce = true
                    return super.read(sink, minOf(5L, byteCount))
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = complete.contentType()
                override fun contentLength() = complete.contentLength()
                override fun source() = truncated
            }).build()
        }.build()
        RemoteVideo(http, StreamInfo(primary, null, "720P", backup)).use { video ->
            video.prepare()
            video.openRead(307200, 32).use { input ->
                assertArrayEquals(file.copyOfRange(307200, 307232), input.readBytes())
            }
            assertEquals(backup, video.info.url)
            video.openRead(307232, 32).use { input ->
                assertArrayEquals(file.copyOfRange(307232, 307264), input.readBytes())
            }
        }
        assertEquals(
            listOf(
                "primary.example.test" to "bytes=0-262143",
                "primary.example.test" to "bytes=307200-307231",
                "backup.example.test" to "bytes=0-15",
                "backup.example.test" to "bytes=307205-307231",
                "backup.example.test" to "bytes=307232-307263",
            ),
            requests,
        )
    }

    @Test
    fun backupWithADifferentIndexIsDiscardedAndOnlyProbedOnce() {
        val file = fixture()
        val different = file.copyOf().apply { this[15] = 0 }
        val backupRanges = mutableListOf<String?>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val range = request.header("Range")
            when {
                request.url.host == "backup.example.test" -> {
                    backupRanges += range
                    partial(request, different)
                }
                range == "bytes=0-262143" -> partial(request, file)
                else -> mediaError(request, 502)
            }
        }.build()
        RemoteVideo(http, StreamInfo(primary, null, "720P", backup)).use { video ->
            video.prepare()
            val failure = assertThrows(IOException::class.java) { video.openRead(307200, 32) }
            assertEquals("备用视频资源的索引与当前集不一致", failure.message)
            assertEquals(primary, video.info.url)
            assertThrows(IOException::class.java) { video.openRead(307200, 32) }
        }
        assertEquals(listOf("bytes=0-15"), backupRanges)
    }

    @Test
    fun doesNotContactABackupForRejectedOrRateLimitedMediaRequests() {
        for ((status, retryAfter) in listOf(403 to null, 429 to null, 503 to "60")) {
            val hosts = mutableListOf<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                hosts += chain.request().url.host
                mediaError(chain.request(), status, retryAfter)
            }.build()
            RemoteVideo(http, StreamInfo(primary, null, "720P", backup)).use { video ->
                assertThrows(IOException::class.java) { video.prepare() }
            }
            assertEquals(listOf("primary.example.test"), hosts)
        }
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
