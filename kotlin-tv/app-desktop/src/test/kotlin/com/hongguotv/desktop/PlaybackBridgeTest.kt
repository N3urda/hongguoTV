package com.hongguotv.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

class PlaybackBridgeTest {
    @Test fun rangesSupportSeekSuffixAndRejectMalformedOrUnsatisfiedRequests() {
        assertEquals(0L..99L, PlaybackBridge.byteRange(null, 100))
        assertEquals(20L..99L, PlaybackBridge.byteRange("bytes=20-", 100))
        assertEquals(20L..40L, PlaybackBridge.byteRange("bytes=20-40", 100))
        assertEquals(90L..99L, PlaybackBridge.byteRange("bytes=-10", 100))
        assertEquals(0L..99L, PlaybackBridge.byteRange("bytes=-200", 100))
        for (range in listOf("bytes=100-", "bytes=50-20", "bytes=0-2,5-9", "bytes=-", "bytes=999999999999999999999999-")) assertNull(PlaybackBridge.byteRange(range, 100))
    }
    @Test fun serverReturnsExactBytesAndProtectsOtherPaths() {
        val bytes = ByteArray(1500000) { (it % 251).toByte() }
        var released = false
        PlaybackBridge(bytes.size.toLong(), { start, count -> bytes.copyOfRange(start.toInt(), start.toInt() + count) }, { released = true }).use { bridge ->
            val client = HttpClient.newHttpClient()
            fun request(url: String = bridge.url, range: String? = null, method: String = "GET"): HttpResponse<ByteArray> {
                val builder = HttpRequest.newBuilder(URI(url)).method(method, HttpRequest.BodyPublishers.noBody())
                range?.let { builder.header("Range", it) }
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            val full = request()
            assertEquals(200, full.statusCode()); assertContentEquals(bytes, full.body())
            val slice = request(range = "bytes=200000-900000")
            assertEquals(206, slice.statusCode()); assertContentEquals(bytes.copyOfRange(200000, 900001), slice.body())
            assertEquals("bytes 200000-900000/1500000", slice.headers().firstValue("Content-Range").get())
            assertEquals(416, request(range = "bytes=1500000-").statusCode())
            assertEquals(404, request(bridge.url + "x").statusCode())
            val head = request(method = "HEAD")
            assertEquals(200, head.statusCode()); assertTrue(head.body().isEmpty())
            assertEquals("1500000", head.headers().firstValue("Content-Length").get())
        }
        assertTrue(released)
    }
}
