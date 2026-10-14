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

/** One season of a multi-season series, plus the season number parsed out of its title. */
data class SeasonEntry(val series: Series, val season: Int)

private val SEASON_MARK = Regex("[（(【\\[]?\\s*第\\s*([0-9一二三四五六七八九十百零两]+)\\s*季\\s*[）)】\\]]?")
private val CN_DIGITS = mapOf('零' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
private val EMPTY_BRACKETS = Regex("[（(【\\[]\\s*[）)】\\]]")

/** "第七季" -> 7, "（第二季）" -> 2; null when the title carries no season marker. */
internal fun seasonNumber(title: String): Int? {
    val raw = SEASON_MARK.find(title)?.groupValues?.get(1) ?: return null
    raw.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
    // 中文数字。写法只有「十二」「二十」「一百零二」这类，简繁通用的十/百进位即可。
    var total = 0
    var digit = -1          // 尚未遇到进位字前累积的数字
    var seen = false
    for (ch in raw) {
        when {
            ch == '百' -> {
                val head = if (digit >= 0) digit else 1     // 「百」前无数字时按「一百」理解
                total += head * 100
                digit = -1; seen = true
            }
            ch == '十' -> {
                val head = if (digit >= 0) digit else 1     // 「十二」=12，「二十」=20
                total += head * 10
                digit = -1; seen = true
            }
            ch in CN_DIGITS -> {
                val d = CN_DIGITS[ch] ?: return null
                // 「零」在「一百零二」里只是占位，不占数位；其它数字重复出现则非法。
                if (d == 0) { seen = true; continue }
                if (digit >= 0) return null                 // 「一二三」不是合法数字
                digit = d; seen = true
            }
            else -> return null
        }
    }
    if (!seen) return null
    val n = total + if (digit >= 0) digit else 0
    return n.takeIf { it > 0 }
}

/** Strip the season marker so all seasons of one show collapse onto the same base title. */
internal fun seriesBaseTitle(title: String): String =
    title.replace(SEASON_MARK, "").replace(EMPTY_BRACKETS, "").replace(Regex("\\s+"), " ").trim()
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

/** Carries the status code so callers can explain a 404 instead of showing a bare error. */
class HttpStatusException(val code: Int): IOException("内容请求失败（HTTP $code）")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
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
    /**
     * Search through the public page, the only source that still answers.
     *
     * The signed App endpoint (`reading/bookapi/search/tab/v`) now replies HTTP 200 with a
     * zero-byte body, so the old `appSearch` path could only ever throw - it left comic search
     * dead and cost a doomed request before every short-drama fallback. The public page serves
     * both short dramas and comics, so [type] no longer selects a source.
     *
     * It renders only the first page server-side and ignores `?page=`, so it must never advertise
     * a next page; claiming one made the list repeat itself forever.
     */
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        if(page>1) return CatalogPage(emptyList(),false)
        val data=router("$site/search/${Signer.encode(keyword)}?page=1")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        return CatalogPage(valid(rows.objects().map(::card)),false)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    /**
     * Find the other seasons of the same show.
     *
     * The App shows a 「系列剧」 tab, but no server-side endpoint exposes that relationship: every
     * `/novel/series/` and `/novel/bookapi/series_*` path answers 404 (verified with a working
     * control request against `video_model/v1/`), the public detail page carries no series field,
     * and the reference APK's DEX has no `season`/`sub_series` string at all. Each season is simply
     * its own `series_id` whose title carries a season marker.
     *
     * So this recovers the relationship the only way the remaining data allows: search the base
     * title and keep whatever normalises onto the same base. That is deliberately conservative —
     * 「变废为宝…」 and 「捡废为宝…」 are different shows that both have a 第二季, and fuzzy
     * matching would wrongly merge them, so titles must be identical after the marker is removed.
     *
     * Returns the current series' siblings only; it is never empty-throwing, because a lone-season
     * show is the normal case and must not surface an error.
     */
    fun seasons(series: Series): List<SeasonEntry> {
        if(!series.id.matches(Regex("[0-9]{1,30}"))) return emptyList()
        val base=seriesBaseTitle(series.title)
        if(base.isBlank()) return emptyList()
        val found=mutableListOf<SeasonEntry>()
        val rows=runCatching { search(base,1).items }.getOrElse { return emptyList() }
        for(row in rows) {
            if(seriesBaseTitle(row.title)!=base) continue
            val number=seasonNumber(row.title) ?: 1   // 无季号者即第一季
            found+=SeasonEntry(row,number)
        }
        return found.distinctBy { it.series.id }.sortedBy { it.season }
    }

    /**
     * Resolve a playable stream, preferring the signed App endpoint.
     *
     * The signed endpoint that still answers is `novel/player/video_model/v1/` with a top-level
     * `video_id` string. This used to call `multi_video_model/v1/` with a `mixed_video_id_map`
     * payload; both the path and that parameter shape were retired server-side and now answer
     * `Code 110001` for every host and payload, which is what stopped the app playing anything.
     *
     * The public player page stays as a degraded fallback: it needs no signature and survives the
     * signed API going away again, but it hosts only the first three episodes at 360p.
     */
    fun stream(seriesId: String, id: String, maxQuality: Int = 1080): StreamInfo {
        require(seriesId.matches(Regex("[0-9]{1,30}")) && id.matches(Regex("[0-9]{1,30}")))
        try { return signedStream(id,maxQuality) }
        catch(signed: IOException) {
            try { return webStream(seriesId,id,maxQuality) }
            catch(web: IOException) { web.addSuppressed(signed); throw web }
        }
    }
    private fun signedStream(id: String, maxQuality: Int): StreamInfo {
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"video_id":"$id"}""".toByteArray()
        val signed=signer.sign(VIDEO_MODEL_API,VendorConstants.device,payload)
        val data=JSONObject(text(signed.url,signed))
        val code=if(data.has("code")) data.optInt("code",-1) else data.optInt("Code",-1)
        if(code!=0) throw IOException("播放接口暂不可用")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        // video_model arrives as a JSON-encoded string, not a nested object.
        val model=map.optJSONObject("video_model") ?: runCatching { JSONObject(map.str("video_model")) }.getOrNull() ?: throw IOException("播放信息为空")
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 renditions declare themselves as `bvc2` and ship no avcC/hvcC record at all, so
        // Android's extractor finds an audio track and no video track. Only ByteVC1 (HEVC) plays.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("definition").ifEmpty { o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    private fun webStream(seriesId: String, id: String, maxQuality: Int): StreamInfo {
        val html=try { text("$site/player/$seriesId/$id") }
        catch(e: HttpStatusException) { if(e.code==404) throw IOException("该来源每部剧只提供前 3 集，本集暂无资源"); throw e }
        val raw=Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"main_url\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: throw IOException("播放地址暂不可用")
        // A Range parameter baked into the page would truncate playback to that slice.
        val url=cleanUrl(raw).replace(Regex("([?&])Range=[^&]*",RegexOption.IGNORE_CASE),"${'$'}1").trimEnd('?','&')
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址格式异常")
        val height=Regex("(\\d{3,4})p").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val quality=if(height>0) "${height}P"+(if(height>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,null,quality)
    }
    companion object {
        /** The only signed player endpoint that still answers; see [stream]. */
        const val VIDEO_MODEL_API = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/"
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
