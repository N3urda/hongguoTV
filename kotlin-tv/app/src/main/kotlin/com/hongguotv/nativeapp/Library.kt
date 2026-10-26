// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private var index: LibraryIndex? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Only remove legacy cache after the separate cache has been committed.
                if (oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                Triple(loaded, cached, stamps)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (loaded, cached, stamps) ->
                    index = loaded; homes.putAll(cached); homeSavedAt.putAll(stamps)
                    accountCookieValue = loaded.accountCookie
                    aspect16Ids = loaded.aspect16; aspect916Ids = loaded.aspect916
                    kindRawJson = runCatching { JSONObject(loaded.kindRaw) }.getOrDefault(JSONObject())
                    manualKindJson = runCatching { JSONObject(loaded.manualKind) }.getOrDefault(JSONObject())
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false) {
        if (state().observeFavorite(id, count, now, acknowledge)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var contentType: ContentType
        get() = state().settings.type
        set(value) { if (value != contentType) { state().settings = state().settings.copy(type = value); put("contentType", value.storedValue) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    /**
     * 播放界面的按键绑定（上 / 下 / 左 / 右 / 菜单 / 确定）。
     *
     * 只存改过的键（见 [KeyMap.encode]），因此默认状态下磁盘上就是一个空串；
     * 改一次就落一次盘，下一部剧、下一次开应用都还是这一套手感。
     */
    var keyMap: KeyMap
        get() = KeyMap.decode(state().settings.keys)
        set(value) {
            val next = value.encode()
            if (next == keyMap.encode()) return
            state().settings = state().settings.copy(keys = next)
            put("keymap", next)
        }
    /** 弹幕显示方式（滚动 / 上方 / 单行 / 隐藏）。隐藏档整层不画也不发请求。 */
    var danmakuMode: DanmakuMode
        get() = state().settings.danmakuMode
        set(value) { if (value != danmakuMode) { state().settings = state().settings.copy(danmakuMode = value); put("danmakuMode", value.storedValue) } }
    var danmakuSpeed: DanmakuSpeed
        get() = state().settings.danmakuSpeed
        set(value) { if (value != danmakuSpeed) { state().settings = state().settings.copy(danmakuSpeed = value); put("danmakuSpeed", value.storedValue) } }
    /** 弹幕字号缩放（0.6–2.0，0.1 一档）。 */
    var danmakuScale: Float
        get() = state().settings.danmakuScale
        set(value) { val next = DanmakuStyle.scale(value); if (next != danmakuScale) { state().settings = state().settings.copy(danmakuScale = next); put("danmakuScale", next) } }
    /** 弹幕透明度（0.2–1.0，0.1 一档）。 */
    var danmakuAlpha: Float
        get() = state().settings.danmakuAlpha
        set(value) { val next = DanmakuStyle.alpha(value); if (next != danmakuAlpha) { state().settings = state().settings.copy(danmakuAlpha = next); put("danmakuAlpha", next) } }
    /**
     * 导入的账号登录态（`Cookie:` 整段串）。只在本地索引里、**不进 BackupSettings**，
     * 因此「备份二维码」不会把登录态带去别的设备。空串 = 匿名设备。
     */
    private var accountCookieValue = ""
    var accountCookie: String
        get() = accountCookieValue
        set(value) { accountCookieValue = value; put("accountCookie", value) }
    var accountExpiresAt: Long
        get() = index?.accountExpiresAt ?: 0L
        set(value) { put("accountExpiresAt", value) }
    /** 本机实测画幅（横版 16:9 / 竖版 9:16）：播放时按视频真实宽高记录，搜索标注优先用它。 */
    private var aspect16Ids: Set<String> = emptySet()
    private var aspect916Ids: Set<String> = emptySet()
    fun aspectMap(): Map<String,String> {
        val map=HashMap<String,String>()
        aspect16Ids.forEach { map[it]="16:9" }
        aspect916Ids.forEach { map[it]="9:16" }
        return map
    }
    fun aspectOf(id: String): String? = when(id) {
        in aspect16Ids -> "16:9"
        in aspect916Ids -> "9:16"
        else -> null
    }
    fun recordAspect(id: String,aspect: String) {
        if(id.isBlank() || (aspect!="16:9" && aspect!="9:16")) return
        if(aspectOf(id)==aspect) return
        if(aspect=="16:9") { aspect16Ids=aspect16Ids+id; aspect916Ids=aspect916Ids-id; put("aspect16",aspect16Ids); put("aspect916",aspect916Ids) }
        else { aspect916Ids=aspect916Ids+id; aspect16Ids=aspect16Ids-id; put("aspect916",aspect916Ids); put("aspect16",aspect16Ids) }
    }
    /** 本机实测类型原始值（播放响应的类型字段）：播放一次即回写，搜索标注按它纠正。 */
    private var kindRawJson = JSONObject()
    fun kindMap(): Map<String,String> {
        val map=HashMap<String,String>()
        val keys=kindRawJson.keys()
        while(keys.hasNext()) { val id=keys.next(); kindFromTypeRaw(kindRawJson.optString(id))?.let { map[id]=it } }
        // 用户手动纠正最高优先级（覆盖播放实测）。
        val manualKeys=manualKindJson.keys()
        while(manualKeys.hasNext()) { val id=manualKeys.next(); map[id]=manualKindJson.optString(id) }
        return map
    }
    fun recordKindRaw(id: String,raw: String) {
        if(id.isBlank() || raw.isBlank() || kindRawJson.optString(id)==raw) return
        kindRawJson.put(id,raw); put("kindRaw",kindRawJson.toString())
    }
    /** 用户手动纠正的类型（最高优先级）：详情页「纠正分类」写入，永久生效。 */
    private var manualKindJson = JSONObject()
    fun setManualKind(id: String,kind: String?) {
        if(id.isBlank()) return
        if(kind==null) { if(manualKindJson.has(id)) { manualKindJson.remove(id); put("manualKind",manualKindJson.toString()) }; return }
        if(kind !in listOf("comic","short","ai") || manualKindJson.optString(id)==kind) return
        manualKindJson.put(id,kind); put("manualKind",manualKindJson.toString())
    }
    fun manualKindOf(id: String): String? = manualKindJson.optString(id).takeIf { it.isNotEmpty() }
    fun snapshot() = state().snapshot()
    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        val merged = LibraryBackup.merge(snapshot(), incoming, restoreSettings)
        index = LibraryIndex(merged, state().updates(), state().accountCookie, state().accountExpiresAt, state().aspect16, state().aspect916, state().kindRaw, state().manualKind)
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates")
        writes.submit(mapOf("quality" to { merged.settings.quality }, "playbackSpeed" to { merged.settings.speed },
            "autoNext" to { merged.settings.autoNext }, "contentType" to { merged.settings.type.storedValue }, "frameMode" to { merged.settings.frame.name },
            "keymap" to { merged.settings.keys }, "danmakuMode" to { merged.settings.danmakuMode.storedValue },
            "danmakuSpeed" to { merged.settings.danmakuSpeed.storedValue }, "danmakuScale" to { merged.settings.danmakuScale },
            "danmakuAlpha" to { merged.settings.danmakuAlpha }))
        flush()
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() { writes.flush(); homeWrites.flush() }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
