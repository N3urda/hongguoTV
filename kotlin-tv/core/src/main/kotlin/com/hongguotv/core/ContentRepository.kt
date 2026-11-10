// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val devices = PlayerDeviceSource(http)
    private val site = "https://hongguoduanju.com"
    private fun text(url: String, signed: Signer.Request? = null, websiteSearch: Boolean = false): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        if (websiteSearch) request.header("User-Agent","Mozilla/5.0").header("Referer","$site/").header("Accept","application/json")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8).also { if (it.isBlank()) throw IOException("内容服务返回空内容，请稍后重试") }
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val section=JSONObject(text("$site/api/search/result?query=${Signer.encode(keyword)}&page=$page",websiteSearch=true))
        if(section.has("pageNum") && section.optInt("pageNum")!=page) throw IOException("搜索页码不匹配，请重新搜索")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val genre=if(type==ContentType.COMIC) "205" else "203"
        val items=valid(rows.objects().filter { it.optJSONObject("video_data")?.str("genre")==genre }.map(::card))
        val more=if(section.has("hasMore")) section.optBoolean("hasMore") else page<section.optInt("totalPages",page)
        return CatalogPage(SearchPayload.prioritize(items,keyword),more && page<100)
    }

    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        try { return currentStream(id,maxQuality) }
        catch(e: PlaybackSourceException) {
            if(deviceStore==null || Thread.currentThread().isInterrupted || e.reason==PlaybackSourceException.Reason.HTTP_REJECTED) throw e
            return registeredStream(id,maxQuality)
        }
    }
    internal fun registeredStream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        for(attempt in 0..1) {
            try { return playerStream(id,maxQuality) }
            catch(e: DeviceRejectedException) { devices.invalidate() }
            catch(e: Exception) { if(Thread.currentThread().isInterrupted) throw e; break }
        }
        return currentStream(id,maxQuality)
    }
    private fun currentStream(id: String, maxQuality: Int): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/",VendorConstants.device + mapOf("video_id" to id,"video_id_type" to "0","device_level" to "1","need_all_video_definition" to "1","need_mp4_align" to "0","use_os_player" to "0","video_platform" to "1024","source" to "0","from_video_id" to ""))
        val data=try { JSONObject(playbackText(signed.url,signed)) } catch(e: org.json.JSONException) {
            throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        }
        PlaybackModels.checkStatus(data)
        val map=data.optJSONObject("data") ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        val model=PlaybackModels.model(map.optJSONObject("video_model_data") ?: map,id) ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        val direct=PlaybackModels.candidates(model)
        if(direct.isNotEmpty()) return PlaybackModels.pick(direct,maxQuality)
        val fallback=PlaybackModels.fallback(model) ?: throw PlaybackSourceException(PlaybackSourceException.Reason.NO_PLAYABLE_SOURCE)
        val response=try { JSONObject(playbackText(fallback)) } catch(e: org.json.JSONException) {
            throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        }
        PlaybackModels.checkStatus(response)
        val info=response.optJSONObject("video_info")?.optJSONObject("data")
            ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
        return PlaybackModels.pick(PlaybackModels.candidates(info),maxQuality)
    }

    /** Playback-only HTTP classification; catalog/search transport stays unchanged. */
    private fun playbackText(url: String, signed: Signer.Request? = null): String {
        val request=Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (key,value) -> request.header(key,value) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if(response.code in 400..499 && response.code !in setOf(408,429))
                throw PlaybackSourceException(PlaybackSourceException.Reason.HTTP_REJECTED,response.code)
            if(!response.isSuccessful) throw IOException("播放请求失败（HTTP ${response.code}）")
            val body=response.body ?: throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val limit=16*1024*1024
            if(body.contentLength()>limit) throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); val input=body.byteStream()
            while(out.size()<=limit) { val count=input.read(buffer); if(count<0) break; out.write(buffer,0,count) }
            if(out.size()>limit) throw PlaybackSourceException(PlaybackSourceException.Reason.INVALID_RESPONSE)
            String(out.toByteArray(),Charsets.UTF_8)
        }
    }
    private fun playerStream(id: String, maxQuality: Int): StreamInfo {
        val device = devices.obtain()
        val rows = mutableListOf<PlayerVariant>()
        var rejected = false
        var sawResponse = false
        var transportFailure: Exception? = null
        val requests = listOf(
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.SHORTPLAY_URL, device), PlayerDeviceSource.shortplayBody(id), mapOf("User-Agent" to "com.dragon.read", "Content-Type" to "application/json; charset=utf-8")),
            Triple(PlayerDeviceSource.substituteDevice(PlayerDeviceSource.PHOENIX_URL, device), PlayerDeviceSource.phoenixBody(id), mapOf("User-Agent" to VendorConstants.VIDEO_UA, "Accept" to "application/json; charset=utf-8,application/x-protobuf", "Content-Type" to "application/json", "x-xs-from-web" to "0", "sdk-version" to "2")),
        )
        for ((url, body, headers) in requests) {
            try {
                val signed = PlayerSigner.headers(url.substringAfter('?', ""), body)
                val data = JSONObject(fetch(url, headers + signed, body))
                val code = data.optInt("code", data.optInt("Code", 0))
                if (code == 10001 || code == 10002 || code == 110001) throw DeviceRejectedException("播放接口拒绝该设备")
                sawResponse = true
                if(code!=0) throw IOException("播放接口拒绝请求（错误码 $code）")
                val payload=data.optJSONObject("data") ?: JSONObject()
                val model=PlaybackModels.model(payload,id) ?: continue
                for (model in listOf(model)) {
                    rows += StreamModels.listRows(model.optJSONObject("video_list"), StreamModels.keySeed(model))
                    rows += StreamModels.arrayRows(model.optJSONArray("video_list"))
                    val fallbackUrl = StreamModels.fallbackApiUrl(model)
                    if (fallbackUrl.startsWith("https://")) runCatching {
                        val fallback = JSONObject(fetch(fallbackUrl, mapOf("User-Agent" to PlayerDeviceSource.FALLBACK_USER_AGENT, "Accept-Language" to "zh-CN,zh;q=0.9"), null))
                        val info = fallback.optJSONObject("video_info")?.optJSONObject("data")
                        rows += StreamModels.listRows(info?.optJSONObject("video_list"), info?.str("key_seed")?.takeIf { it.isNotEmpty() }?.let(StreamModels::lenientB64))
                    }
                }
            } catch (e: DeviceRejectedException) { rejected = true }
            catch (e: Exception) { if(Thread.currentThread().isInterrupted) throw e; transportFailure = e }
        }
        val chosen = StreamModels.pick(rows, maxQuality)
            ?: run {
                if (rejected) throw DeviceRejectedException("播放设备已被上游拒绝")
                if (!sawResponse) throw transportFailure ?: IOException("播放接口暂不可用")
                throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
            }
        val quality = if (chosen.quality > 0) "${chosen.quality}P" + (if (chosen.quality > maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(chosen.url, chosen.key, quality)
    }
    private fun fetch(url: String, headers: Map<String, String>, body: ByteArray?): String {
        val request = Request.Builder().url(url).header("Accept-Language", "zh-CN,zh;q=0.9")
        headers.forEach { (k, v) -> request.header(k, v) }
        body?.let { request.post(it.toRequestBody("application/json; charset=UTF-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (response.code == 401 || response.code == 403) throw DeviceRejectedException("播放请求被拒绝（HTTP ${response.code}）")
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val bodyStream = response.body ?: throw IOException("内容响应为空")
            if (bodyStream.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            val input = bodyStream.byteStream(); val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 16 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            if (out.size() > 16 * 1024 * 1024) throw IOException("内容响应过大")
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }
    companion object {
        @Volatile var deviceStore: PlayerDeviceStore? = null

        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
