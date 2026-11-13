// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.CatalogPage
import com.hongguotv.core.FeedStartupSnapshot

/** One account scope, three sources; feed workers read pages, selected-work snapshots update memory immediately and persist asynchronously. */
internal class FeedStartupCache(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val prefs by lazy {
        app.getSharedPreferences("native-feed-startup-v1", Context.MODE_PRIVATE)
    }

    companion object {
        private val lock = Any()
    }

    private fun owner(identity: String) {
        if (prefs.getString("owner", null) != identity)
            prefs.edit().clear().putString("owner", identity).apply()
    }

    fun load(identity: String, source: String): CatalogPage? =
        runCatching {
                synchronized(lock) {
                    owner(identity)
                    val raw = prefs.getString(source, null) ?: return@synchronized null
                    FeedStartupSnapshot.decode(raw, clock()).also {
                        if (it == null) prefs.edit().remove(source).apply()
                    }
                }
            }
            .getOrNull()

    /** 首页「接着上次刷」的游标快照；与首屏缓存共用同一份身份隔离。 */
    fun loadCursor(identity: String, source: String): String? =
        runCatching {
                synchronized(lock) {
                    owner(identity)
                    prefs.getString("cursor:$source", null)?.takeIf { it.length <= 64_000 }
                }
            }
            .getOrNull()

    fun saveCursor(identity: String, source: String, snapshot: String) {
        runCatching {
            synchronized(lock) {
                owner(identity)
                // apply updates the shared in-memory snapshot immediately, then serializes disk writes
                // off the UI thread. A subsequent backend can read it without waiting for fsync.
                if (snapshot.length <= 64_000)
                    prefs.edit().putString("cursor:$source", snapshot).apply()
            }
        }
    }

    /** 用户主动重建当前来源时同时移除游标和首屏，避免下一次读取又恢复旧状态。 */
    fun clearSource(identity: String, source: String) {
        synchronized(lock) {
            owner(identity)
            if (!prefs.edit().remove(source).remove("cursor:$source").commit())
                throw java.io.IOException("推荐记录重置失败，请重试")
        }
    }

    fun save(identity: String, source: String, page: CatalogPage) {
        runCatching {
            val value = FeedStartupSnapshot.encode(page, clock())
            synchronized(lock) {
                owner(identity)
                if (value.length <= 200_000) prefs.edit().putString(source, value).apply()
            }
        }
    }
}
