// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.*

class DesktopLibrary(private val file: Path = defaultFile()) {
    var preferences = JSONObject(); private set
    fun preference(key: String, fallback: String) = preferences.optString(key, fallback)
    @Synchronized fun preference(key: String, value: Any) { preferences.put(key, value); save() }
    val later = linkedMapOf<String, Series>()
    val favorites = linkedMapOf<String, Series>()
    val history = linkedMapOf<String, BackupProgress>()
    var warning: String? = null; private set
    init {
        if (Files.exists(file)) runCatching {
            require(Files.size(file) <= 2 * 1024 * 1024)
            val root = JSONObject(Files.readString(file))
            require(root.getInt("version") == 1)
            val loadedFavorites = linkedMapOf<String, Series>()
            val loadedHistory = linkedMapOf<String, BackupProgress>()
            val rows = root.getJSONArray("favorites")
            for (i in 0 until rows.length()) {
                val series = Series.fromJson(rows.getJSONObject(i)); loadedFavorites[series.id] = series
            }
            val progress = root.getJSONArray("history")
            for (i in 0 until progress.length()) {
                val row = progress.getJSONObject(i)
                val series = Series.fromJson(row.getJSONObject("series"))
                loadedHistory[series.id] = BackupProgress(series, row.getString("episodeId"), row.getInt("episodeIndex"), row.getLong("position"), row.getLong("duration"), row.getBoolean("completed"), row.getLong("updatedAt"))
            }
            val laterRows = root.optJSONArray("later") ?: JSONArray()
            for (i in 0 until laterRows.length()) { val series = Series.fromJson(laterRows.getJSONObject(i)); later[series.id] = series }
            favorites.putAll(loadedFavorites); history.putAll(loadedHistory); preferences = root.optJSONObject("preferences") ?: JSONObject()
        }.onFailure { warning = "本地记录无法读取，原文件已保留，请先备份后修复。" }
    }
    @Synchronized fun toggle(series: Series) {
        if (favorites.containsKey(series.id)) favorites.remove(series.id) else favorites[series.id] = series
        save()
    }
    @Synchronized fun remember(progress: BackupProgress) {
        history.remove(progress.series.id); history[progress.series.id] = progress
        while (history.size > 200) history.remove(history.keys.first())
        save()
    }
    @Synchronized fun toggleLater(series: Series) {
        if (later.containsKey(series.id)) later.remove(series.id) else { later[series.id] = series; while (later.size > 100) later.remove(later.keys.first()) }
        save()
    }
    private fun portable() = BackupData(favorites.values.toList(), history.values.toList(), history.values.filter { it.completed }.map { it.series.id }.toSet(), emptyList(),
        BackupSettings(quality = PlaybackQuality.normalize(preference("quality", "1080").toIntOrNull() ?: 1080), speed = PlaybackSpeed.normalize(preference("speed", "1").toFloatOrNull() ?: 1f), autoNext = preference("autoNext", "true").toBoolean(), frame = VideoFrameMode.fromStored(preference("frame", "FIT"))), later.values.toList())
    @Synchronized fun exportBackup(): String = LibraryBackup.encode(portable())
    @Synchronized fun importBackup(raw: String) {
        check(warning == null) { "请先修复本机数据文件" }
        val merged = LibraryBackup.merge(portable(), LibraryBackup.decode(raw), false)
        favorites.clear(); favorites.putAll(merged.favorites.associateBy { it.id })
        history.clear(); history.putAll(merged.history.sortedBy { it.updatedAt }.associateBy { it.series.id })
        later.clear(); later.putAll(merged.later.associateBy { it.id })
        save()
        check(warning == null) { "保存失败" }
    }
    private fun save() {
        // A corrupt existing file is never silently replaced with an empty library.
        if (warning != null) return
        try {
            Files.createDirectories(file.parent)
            val root = JSONObject().put("version", 1).put("later", JSONArray(later.values.map { it.toJson() })).put("preferences", preferences).put("favorites", JSONArray(favorites.values.map { it.toJson() })).put("history", JSONArray(history.values.map { it.json() }))
            val temporary = Files.createTempFile(file.parent, "library-", ".tmp")
            try {
                Files.writeString(temporary, root.toString())
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        } catch (_: Exception) { warning = "本地记录保存失败，请检查目录权限和磁盘空间。" }
    }
    companion object {
        fun defaultFile(): Path {
            System.getProperty("hongguo.dataDir")?.let { return Path.of(it, "library.json") }
            val home = System.getProperty("user.home")
            val base = when {
                System.getProperty("os.name").startsWith("Mac") -> Path.of(home, "Library", "Application Support", "HongguoDesktop")
                System.getProperty("os.name").startsWith("Windows") -> Path.of(System.getenv("LOCALAPPDATA") ?: home, "HongguoDesktop")
                else -> Path.of(home, ".local", "share", "hongguo-desktop")
            }
            return base.resolve("library.json")
        }
    }
}
