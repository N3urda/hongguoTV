// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Main-thread snapshots. Retain at most 80 small cards, never bitmaps or complete result pages. */
internal class PhoneControlCatalog(
    private val library: Library,
    private val account: AccountController,
    private val play: (Series, CloudProgress?) -> Unit,
) {
    private data class Entry(val series: Series, val upcoming: Boolean, val reservable: Boolean)
    private val entries = linkedMapOf<String, Entry>()
    private var scope = account.historyScope
    var message = ""
        private set
    var revision = 0
        private set
    private var mutationPending = false
    private var reservationId: String? = null
    private var favoriteId: String? = null

    fun login(credentials: AccountCredentials, scope: Int) = account.acceptConsoleLogin(credentials, scope)

    fun clear() { entries.clear(); message = ""; mutationPending = false; reservationId = null; favoriteId = null }

    fun favoriteStatus(): JSONObject? {
        val id = favoriteId ?: return null
        return JSONObject().put("id", id).put("favorite", account.accountFavorite(id))
            .put("pending", account.favoriteNeedsRefresh(id)).put("loading", account.favoriteState().loading)
    }

    fun reservationStatus(): JSONObject? {
        val id = reservationId ?: return null
        val entry = entries[id] ?: return null
        return JSONObject().put("id", id).put("label", account.reservationLabel(RelatedWork(entry.series, "", true)))
    }

    fun checkScope() {
        if (scope != account.historyScope) { clear(); scope = account.historyScope }
    }

    fun series(id: String): Series {
        checkScope()
        return entries[id]?.series ?: throw IllegalArgumentException("Expired card")
    }

    private fun safeCover(value: String): String = value.takeIf { it.length <= 2048 &&
        runCatching { java.net.URI(it).let { uri -> uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null } }.getOrDefault(false)
    }.orEmpty()

    private fun mergedSeries(rows: List<Series>): List<Series> = rows.groupBy { it.id }.values.map { versions ->
        val first = versions.first()
        first.copy(cover = versions.firstOrNull { safeCover(it.cover).isNotEmpty() }?.cover.orEmpty())
    }

    fun result(rows: List<RelatedWork>, hasMore: Boolean = false, status: String = ""): JSONObject {
        checkScope()
        val items = JSONArray()
        rows.take(40).forEach { work ->
            val source = work.series
            val small = Series(source.id.take(64), source.title.take(200), cover = safeCover(source.cover), badge = source.badge.take(120))
            entries.remove(small.id)
            entries[small.id] = Entry(small, work.upcoming, work.upcoming && small.id.matches(Regex("[0-9]{1,19}")))
            while (entries.size > 80) entries.remove(entries.keys.first())
            items.put(JSONObject().put("id", small.id).put("title", small.title).put("cover", small.cover).put("badge", work.label + " · " + small.badge)
                .put("reservationLabel", if (work.upcoming) account.reservationLabel(work) else "")
                .put("favorite", account.accountFavorite(small.id)).put("favoritePending", account.favoriteNeedsRefresh(small.id))
                .put("playable", !work.upcoming).put("reservable", entries[small.id]!!.reservable).put("scope", scope))
        }
        return JSONObject().put("items", items).put("hasMore", hasMore).put("message", status)
    }

    fun list(body: JSONObject): JSONObject {
        checkScope()
        val kind = body.getString("kind")
        val page = body.optInt("page", 1).also { require(it in 1..100) }
        val refresh = body.optBoolean("refresh")
        val cloud = kind in setOf("online", "upcoming")
        if (cloud && !account.signedIn) return result(emptyList(), status = "请到「账户」登录红果")
        var status = ""
        var loading = false
        val rows = when (kind) {
            "favorites" -> {
                if (refresh && account.signedIn) account.refreshFavorites()
                status = if (account.signedIn) "本机与账户收藏合并显示 · " + account.favoriteState().message
                    else "本机收藏 · 登录后在这里合并显示账户收藏"
                loading = account.favoriteState().loading
                mergedSeries(library.favorites() + account.favoriteState().rows.map { it.series })
                    .map { RelatedWork(it, "", false) }
            }
            "history" -> {
                if (refresh && account.signedIn) account.refreshHistory()
                status = if (account.signedIn) "按最近观看排序 · " + account.historyStatus
                    else "本机历史 · 登录后在这里合并显示账户历史"
                loading = account.signedIn && account.historyLoading
                val recent = (library.history().map { it.series to it.updatedAt } +
                    account.historyRows().map { it.series to it.updatedAt }).sortedByDescending { it.second }
                mergedSeries(recent.map { it.first }).map { RelatedWork(it, "", false) }
            }
            "online", "upcoming" -> {
                if (refresh) account.refreshReservations()
                val state = account.reservationState(kind == "online")
                loading = state.loading
                status = state.message + "；每类仅显示当前读取的最近 20 部"
                state.rows.map { RelatedWork(it.series, "", !it.online) }
            }
            "localFavorites" -> library.favorites().map { RelatedWork(it, "", false) }
            "localHistory" -> library.history().map { RelatedWork(it.series, "", false) }
            "later" -> library.later().map { RelatedWork(it, "", false) }
            else -> throw IllegalArgumentException("Unknown list")
        }
        return result(rows.drop((page - 1) * 20).take(20), rows.size > page * 20, status).put("loading", loading)
    }

    fun act(body: JSONObject): JSONObject {
        checkScope()
        val action = body.getString("action")
        if (body.getInt("scope") != scope) return JSONObject().put("error", "账户已切换，请重新读取列表")
        val entry = entries[body.getString("id")] ?: return JSONObject().put("error", "列表已过期，请重新读取")
        when (action) {
            "play" -> {
                require(!entry.upcoming)
                val progress = if (body.optString("source") == "history" && account.signedIn)
                    account.progress(entry.series.id)?.takeIf { cloud ->
                        val local = library.progress(entry.series.id)
                        local == null || cloud.updatedAt > local.updatedAt
                    } else null
                play(entry.series, progress)
                return JSONObject().put("message", "已发送到电视，正在准备播放")
            }
            "favorite", "reserve" -> {
                if (!account.signedIn) return JSONObject().put("error", "请先到「账户」登录")
                if (mutationPending || account.historyLoading) return JSONObject().put("error", "账户操作进行中，请稍候")
                if (action == "reserve") { require(entry.reservable); reservationId = entry.series.id }
                else { require(!entry.upcoming); favoriteId = entry.series.id }
                mutationPending = true
                val ticket = scope
                val done: (String) -> Unit = { text ->
                    if (ticket == account.historyScope) { mutationPending = false; message = text; revision++ }
                }
                message = "正在提交账户操作…"
                try {
                    if (action == "reserve") account.reserve(RelatedWork(entry.series, "", true), done)
                    else account.changeFavorite(entry.series, body.getBoolean("add"), done)
                } catch (e: Exception) { mutationPending = false; throw e }
                return JSONObject().put("message", message)
            }
            else -> throw IllegalArgumentException("Unknown action")
        }
    }
}
