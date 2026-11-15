// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Old installed clients' data must remain readable after model extraction. */
class SharedModelJsonTest {
    @Test fun seriesRetainsHistoricalFieldsAndBlankHandling() {
        val stored = JSONObject("""{"id":" 123 ","title":" 标题 ","cover":null,"description":"简介","badge":"共 80 集","tags":"短剧"}""")
        val series = Series.fromJson(stored)
        assertEquals(Series("123", "标题", "", "简介", "共 80 集", "短剧"), series)
        assertEquals(setOf("id", "title", "cover", "description", "badge", "tags"), series.toJson().keys().asSequence().toSet())
        assertEquals(series, Series.fromJson(series.toJson()))
    }

    @Test fun progressEncodingRetainsBackupSanitizationAndFieldNames() {
        val progress = BackupProgress(Series("123", "标题", "", "private description"), "456", 1, 200, 1000, false, 300)
        val json = progress.json()
        assertEquals(setOf("series", "episodeId", "episodeIndex", "position", "duration", "completed", "updatedAt"), json.keys().asSequence().toSet())
        assertEquals("", json.getJSONObject("series").getString("description"))
        assertEquals("456", json.getString("episodeId"))
        assertEquals(200L, json.getLong("position"))
        assertEquals(300L, json.getLong("updatedAt"))
        assertFalse(json.getBoolean("completed"))
    }

    @Test fun favoriteUpdateReadsOldRecordsAndRejectsCorruption() {
        val previous = JSONObject("""{"total":90,"seen":80,"checkedAt":123}""")
        val update = FavoriteUpdate.decode(previous)!!
        assertEquals(10, update.added)
        assertEquals(update, FavoriteUpdate.decode(update.json()))
        assertNull(FavoriteUpdate.decode(null))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":0,"seen":80,"checkedAt":123}""")))
        assertNull(FavoriteUpdate.decode(JSONObject("""{"total":90,"seen":80,"checkedAt":-1}""")))
    }
}
