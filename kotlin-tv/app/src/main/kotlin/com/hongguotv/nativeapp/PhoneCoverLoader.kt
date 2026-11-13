// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/** Session-local thumbnails, read on a phone worker, never on the television UI thread. */
internal class PhoneCoverLoader(private val http: OkHttpClient) {
    @Volatile private var closed = false
    private val cache = linkedMapOf<String, String>()

    fun load(url: String): String {
        check(!closed)
        if (url.isBlank()) return ""
        synchronized(cache) { cache[url] }?.let { return it }
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "封面读取失败" }
            val body = response.body ?: error("封面为空")
            require(body.contentLength() <= 4 * 1024 * 1024) { "封面过大" }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= 4 * 1024 * 1024) { "封面过大" }
                    out.write(buffer, 0, n)
                }
            }
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "封面格式无法识别" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 480) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("封面解码失败")
        val out = ByteArrayOutputStream()
        try { check(decoded.compress(Bitmap.CompressFormat.JPEG, 80, out)) }
        finally { decoded.recycle() }
        require(out.size() <= 160 * 1024) { "缩略图过大" }
        val image = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        synchronized(cache) {
            check(!closed)
            cache[url] = image
            while (cache.size > 16) cache.remove(cache.keys.first())
        }
        return image
    }

    fun close() {
        closed = true
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        synchronized(cache) { cache.clear() }
    }
}
