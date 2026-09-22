// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.Series
import com.hongguotv.core.ContentType
import com.hongguotv.core.PlaybackSpeed
import com.hongguotv.core.SearchHistory
import com.hongguotv.core.RecentSearch
import com.hongguotv.core.FavoriteUpdate
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.BackupData
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.BackupSettings
import com.hongguotv.core.LibraryBackup
import com.hongguotv.core.VideoFrameMode
import org.json.JSONArray
import org.json.JSONObject

data class WatchProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long) {
    fun json() = JSONObject().put("series",series.toJson()).put("episodeId",episodeId).put("episodeIndex",episodeIndex).put("position",position).put("duration",duration).put("completed",completed).put("updatedAt",updatedAt)
}
class Library(context: Context) {
    private val prefs=context.getSharedPreferences("native-library-v1",Context.MODE_PRIVATE)
    private fun read(key: String) = runCatching { JSONArray(prefs.getString(key,"[]")) }.getOrDefault(JSONArray())
    fun favorites(): List<Series> { val rows=read("favorites"); return (0 until rows.length()).mapNotNull { runCatching { Series.fromJson(rows.getJSONObject(it)) }.getOrNull() } }
    fun favorite(id: String) = favorites().any { it.id==id }
    fun toggle(series: Series): Boolean {
        val rows=favorites().toMutableList(); val found=rows.removeAll { it.id==series.id }; if(!found) rows.add(0,series)
        val retained=rows.take(500)
        val updates=updatesJson(); updates.keys().asSequence().toList().filter { id -> retained.none { it.id==id } }.forEach { updates.remove(it) }
        prefs.edit().putString("favorites",JSONArray(retained.map { it.toJson() }).toString()).putString("favoriteUpdates",updates.toString()).apply(); return !found
    }
    fun history(): List<WatchProgress> { val rows=read("progress"); return (0 until rows.length()).mapNotNull { i -> runCatching { val o=rows.getJSONObject(i); WatchProgress(Series.fromJson(o.getJSONObject("series")),o.getString("episodeId"),o.getInt("episodeIndex"),o.getLong("position"),o.getLong("duration"),o.optBoolean("completed"),o.getLong("updatedAt")) }.getOrNull() }.sortedByDescending { it.updatedAt } }
    fun progress(id: String) = history().firstOrNull { it.series.id==id }
    fun save(progress: WatchProgress) { val rows=(listOf(progress)+history().filter { it.series.id!=progress.series.id }).take(200); prefs.edit().putString("progress",JSONArray(rows.map { it.json() }).toString()).apply() }
    fun searches(): List<RecentSearch> = SearchHistory.decode(prefs.getString("searches","[]") ?: "[]")
    fun rememberSearch(query: String,type: ContentType) { prefs.edit().putString("searches",SearchHistory.encode(SearchHistory.remember(searches(),query,type))).apply() }
    fun clearSearches() { prefs.edit().remove("searches").apply() }
    fun watched(id: String) = id in prefs.getStringSet("watched",emptySet()).orEmpty()
    fun setWatched(id: String,value: Boolean) {
        val ids=prefs.getStringSet("watched",emptySet()).orEmpty().toMutableSet()
        if(value) ids.add(id) else ids.remove(id)
        prefs.edit().putStringSet("watched",ids).apply()
    }
    fun removeHistory(id: String) {
        val ids=prefs.getStringSet("watched",emptySet()).orEmpty().toMutableSet().apply { remove(id) }
        prefs.edit().putString("progress",JSONArray(history().filterNot { it.series.id==id }.map { it.json() }).toString()).putStringSet("watched",ids).apply()
    }
    fun clearHistory() { prefs.edit().remove("progress").remove("watched").apply() }
    private fun updatesJson()=runCatching { JSONObject(prefs.getString("favoriteUpdates","{}") ?: "{}") }.getOrDefault(JSONObject())
    fun favoriteUpdate(id: String)=FavoriteUpdate.decode(updatesJson().optJSONObject(id))
    fun observeFavorite(id: String,count: Int,now: Long,acknowledge: Boolean=false) {
        if(!favorite(id)) return
        val updates=updatesJson()
        val next=favoriteUpdate(id)?.observe(count,now,acknowledge) ?: FavoriteUpdate.first(count,now)
        updates.put(id,next.json()); prefs.edit().putString("favoriteUpdates",updates.toString()).apply()
    }
    fun updatedFavorites()=favorites().count { (favoriteUpdate(it.id)?.added ?: 0)>0 }
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        if(it.added>0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
    } ?: "尚未检查更新"
    var maxQuality: Int
        get()=PlaybackQuality.normalize(prefs.getInt("quality",1080))
        set(value) { prefs.edit().putInt("quality",PlaybackQuality.normalize(value)).apply() }
    var autoNext: Boolean
        get()=prefs.getBoolean("autoNext",true)
        set(value) { prefs.edit().putBoolean("autoNext",value).apply() }
    var contentType: ContentType
        get()=ContentType.fromStored(prefs.getString("contentType",null))
        set(value) { prefs.edit().putString("contentType",value.storedValue).apply() }
    var playbackSpeed: Float
        get()=PlaybackSpeed.normalize(prefs.getFloat("playbackSpeed",1f))
        set(value) { prefs.edit().putFloat("playbackSpeed",PlaybackSpeed.normalize(value)).apply() }
    var frameMode: VideoFrameMode
        get()=VideoFrameMode.fromStored(prefs.getString("frameMode",null))
        set(value) { prefs.edit().putString("frameMode",value.name).apply() }
    fun snapshot()=BackupData(favorites(),history().map { BackupProgress(it.series,it.episodeId,it.episodeIndex,it.position,it.duration,it.completed,it.updatedAt) },
        prefs.getStringSet("watched",emptySet()).orEmpty().toSet(),searches(),BackupSettings(maxQuality,playbackSpeed,autoNext,contentType,frameMode))
    fun restore(incoming: BackupData,restoreSettings: Boolean) {
        val merged=LibraryBackup.merge(snapshot(),incoming,restoreSettings)
        val updates=updatesJson(); updates.keys().asSequence().toList().filter { id -> merged.favorites.none { it.id==id } }.forEach { updates.remove(it) }
        prefs.edit().putString("favorites",JSONArray(merged.favorites.map { it.toJson() }).toString())
            .putString("progress",JSONArray(merged.history.map { it.json() }).toString())
            .putStringSet("watched",merged.watched).putString("searches",SearchHistory.encode(merged.searches))
            .putString("favoriteUpdates",updates.toString()).putInt("quality",merged.settings.quality)
            .putFloat("playbackSpeed",merged.settings.speed).putBoolean("autoNext",merged.settings.autoNext)
            .putString("contentType",merged.settings.type.storedValue).putString("frameMode",merged.settings.frame.name).apply()
    }
}
