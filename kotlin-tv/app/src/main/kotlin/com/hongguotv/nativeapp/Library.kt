// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.Series
import com.hongguotv.core.ContentType
import com.hongguotv.core.PlaybackSpeed
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
        prefs.edit().putString("favorites",JSONArray(rows.take(500).map { it.toJson() }).toString()).apply(); return !found
    }
    fun history(): List<WatchProgress> { val rows=read("progress"); return (0 until rows.length()).mapNotNull { i -> runCatching { val o=rows.getJSONObject(i); WatchProgress(Series.fromJson(o.getJSONObject("series")),o.getString("episodeId"),o.getInt("episodeIndex"),o.getLong("position"),o.getLong("duration"),o.optBoolean("completed"),o.getLong("updatedAt")) }.getOrNull() }.sortedByDescending { it.updatedAt } }
    fun progress(id: String) = history().firstOrNull { it.series.id==id }
    fun save(progress: WatchProgress) { val rows=(listOf(progress)+history().filter { it.series.id!=progress.series.id }).take(200); prefs.edit().putString("progress",JSONArray(rows.map { it.json() }).toString()).apply() }
    var maxQuality: Int
        get()=prefs.getInt("quality",1080)
        set(value) { prefs.edit().putInt("quality",value).apply() }
    var autoNext: Boolean
        get()=prefs.getBoolean("autoNext",true)
        set(value) { prefs.edit().putBoolean("autoNext",value).apply() }
    var contentType: ContentType
        get()=ContentType.fromStored(prefs.getString("contentType",null))
        set(value) { prefs.edit().putString("contentType",value.storedValue).apply() }
    var playbackSpeed: Float
        get()=PlaybackSpeed.normalize(prefs.getFloat("playbackSpeed",1f))
        set(value) { prefs.edit().putFloat("playbackSpeed",PlaybackSpeed.normalize(value)).apply() }
}
