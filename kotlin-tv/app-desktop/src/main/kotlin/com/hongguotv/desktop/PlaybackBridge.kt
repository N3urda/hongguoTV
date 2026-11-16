// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.RemoteVideo
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/** A private, per-session adapter for the same decoded bytes used by Android. */
class PlaybackBridge(private val total: Long, private val read: (Long, Int) -> ByteArray, private val release: () -> Unit) : Closeable {
    constructor(video: RemoteVideo) : this(video.total, video::read, video::close)
    private val executor = Executors.newFixedThreadPool(4) { Thread(it, "desktop-media-range").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val path = "/${UUID.randomUUID()}/video.mp4"
    val url: String get() = "http://127.0.0.1:${server.address.port}$path"
    init {
        require(total > 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestURI.rawPath != path || exchange.requestURI.rawQuery != null) {
                    exchange.sendResponseHeaders(404, -1)
                } else if (exchange.requestMethod !in listOf("GET", "HEAD")) {
                    exchange.responseHeaders.set("Allow", "GET, HEAD")
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    val header = exchange.requestHeaders.getFirst("Range")
                    val range = byteRange(header, total)
                    if (range == null) {
                        exchange.responseHeaders.set("Content-Range", "bytes */$total")
                        exchange.sendResponseHeaders(416, -1)
                    } else {
                        val length = range.last - range.first + 1
                        exchange.responseHeaders.set("Content-Type", "video/mp4")
                        exchange.responseHeaders.set("Accept-Ranges", "bytes")
                        exchange.responseHeaders.set("Cache-Control", "no-store")
                        exchange.responseHeaders.set("Content-Length", length.toString())
                        if (header != null) exchange.responseHeaders.set("Content-Range", "bytes ${range.first}-${range.last}/$total")
                        exchange.sendResponseHeaders(if (header == null) 200 else 206, if (exchange.requestMethod == "HEAD") -1 else length)
                        if (exchange.requestMethod == "GET") {
                            var offset = range.first
                            exchange.responseBody.use { output ->
                                while (offset <= range.last) {
                                    val size = minOf(512 * 1024L, range.last - offset + 1).toInt()
                                    val bytes = read(offset, size)
                                    check(bytes.size == size) { "Incomplete media range" }
                                    output.write(bytes)
                                    offset += bytes.size
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Seeking closes an earlier HTTP request; never log media URLs or credentials.
            } finally { exchange.close() }
        }
        server.start()
    }
    override fun close() { release(); server.stop(0); executor.shutdownNow() }

    companion object {
        internal fun byteRange(header: String?, total: Long): LongRange? {
            if (total <= 0) return null
            if (header == null) return 0 until total
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return null
            val (left, right) = match.destructured
            if (left.isEmpty()) {
                val suffix = right.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return (total - minOf(total, suffix)) until total
            }
            val first = left.toLongOrNull()?.takeIf { it in 0 until total } ?: return null
            val last = if (right.isEmpty()) total - 1 else right.toLongOrNull() ?: return null
            return if (last < first) null else first..minOf(last, total - 1)
        }
    }
}
