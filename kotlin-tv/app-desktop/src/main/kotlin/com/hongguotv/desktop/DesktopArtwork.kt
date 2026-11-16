// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DesktopArtwork {
    private val http = DesktopNetwork.client().newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val cache = linkedMapOf<String, ByteArray>()
    private var cacheBytes = 0
    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        try {
            val bytes = synchronized(cache) { cache[url] } ?: http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful)
                val body = response.body ?: error("Empty cover")
                check(body.contentLength() <= 4 * 1024 * 1024)
                val result = body.byteStream().readNBytes(4 * 1024 * 1024 + 1)
                check(result.size <= 4 * 1024 * 1024)
                synchronized(cache) {
                    if (!cache.containsKey(url)) {
                        while (cache.isNotEmpty() && cacheBytes + result.size > 8 * 1024 * 1024) cacheBytes -= cache.remove(cache.keys.first())!!.size
                        cache[url] = result; cacheBytes += result.size
                    }
                }
                result
            }
            ensureActive()
            org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
                check(image.width.toLong() * image.height <= 4_000_000)
                image.toComposeImageBitmap()
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
