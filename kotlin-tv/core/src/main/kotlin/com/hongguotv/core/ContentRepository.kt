// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
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

/** Whitespace and punctuation carry no meaning when looking for a show by name. */
private val SEARCH_NOISE = Regex("[\\s\\p{P}\\p{S}]+")

/** Lowercase, with whitespace and punctuation removed. */
internal fun normalizeForSearch(value: String) = SEARCH_NOISE.replace(value.lowercase(Locale.ROOT), "")

/**
 * Does [title] actually mention [keyword]?
 *
 * The site's search never says no. Searching a show it does not carry still answers with ten
 * popular rows of the same genre, and it reports a `totalCount` of 174-210 for such a term as if
 * it had matched something - so 「庆余年」 comes back as 《剑神归来》《零下五十度我被困在公司》,
 * with nothing in the ten rows containing the term. Search is fuzzy ranking over a fixed list of
 * ten, never a filter, and the client has to draw that line itself.
 *
 * Punctuation is ignored because no one retypes a title's punctuation exactly: 搜「伪人降临 我是
 * 全场唯一预言家第三季」from a phone lands on 「伪人降临！我是全场唯一预言家第三季」.
 */
internal fun mentions(title: String, keyword: String): Boolean {
    val needle = normalizeForSearch(keyword)
    return needle.isNotEmpty() && needle in normalizeForSearch(title)
}
data class RankPosition(val rank: Int?, val heat: String)
data class HotRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
/**
 * [matchedCount] is how many of [items] the keyword actually found, [totalHits] how many exist in
 * total across pages, and [fuzzy] whether that came from the one-character typo fallback. All three
 * are null / false where the page is not a search result and the questions do not apply.
 */
data class CatalogPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val ranking: HotRanking? = null,
    val matchedCount: Int? = null,
    val totalHits: Int? = null,
    val fuzzy: Boolean = false
)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

/** Carries the status code so callers can explain a 404 instead of showing a bare error. */
class HttpStatusException(val code: Int): IOException("内容请求失败（HTTP $code）")

/**
 * How many board pages one shelf may have.
 *
 * The site publishes five for every board and answers 404 on the sixth - measured 2026-10-19 for
 * all four boards. The bound is a safety net rather than a known number: a board that suddenly
 * grew must not turn into an endless walk.
 */
private const val MAX_RANK_PAGES = 50

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    /** The boards already fetched, per shelf, so turning the page costs nothing. */
    private val rankingCache=mutableMapOf<ContentType,HotRank.HotList>()
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
    /**
     * The hot list of one shelf.
     *
     * The board serves twenty rows a page and has no page-size parameter to change that - measured
     * 2026-10-19, ten spellings (pageSize, page_size, size, limit, rows, count, per_page, num and
     * combinations), all twenty. Twenty is not a multiple of the app's six columns, so a page
     * drawn straight from the board always ends with a short row - the thing the rest of the app
     * avoids by making its own pages a multiple of the row width. The app therefore cuts its pages
     * out of this list with [HotRank.page].
     *
     * The list itself is every board of the shelf's routes, merged into one heat order, because
     * the heat is a per-show number that reads the same on every board. Merging the whole list is
     * also what makes the ranks mean anything: the comic board's number 21 is hotter than the AI
     * board's number 30, so without one order the 「第 41 名」 on the second screen can be hotter
     * than the 「第 40 名」 on the first - measured 2026-10-19, 5437万 on the first screen's last
     * card against 5500万 on the second screen's first.
     *
     * Fetching the whole shelf is the only way to get one order out of it: five requests for 真人剧,
     * ten for 漫剧 (two boards), two seconds measured. The list is held for the session, so turning
     * the page costs nothing more.
     *
     * A board that fails does not take the whole tab down: the rest still lists, and the shelf's
     * hot list is shorter rather than missing. Only every board failing is a failure.
     *
     * @param force fetch even when a list fetched recently is already held.
     */
    @Synchronized
    fun rankingList(type: ContentType = ContentType.SHORT, force: Boolean = false): HotRank.HotList {
        val now=System.currentTimeMillis()
        rankingCache[type]?.let { if(!force && !it.isStale(now)) return it }
        var failure: Exception?=null
        val boards=mutableListOf<HotRank.Board>()
        for(route in type.routes) {
            var page=1
            while(page<=MAX_RANK_PAGES) {
                val board=try { HotRank.parse(text("$site/rank/${route.rankPath}?page=$page"),page,route) }
                catch(e: Exception) { if(failure==null) failure=e; break }
                if(board.items.isEmpty()) break
                boards+=board
                if(page>=board.totalPages) break
                page++
            }
        }
        if(boards.isEmpty()) throw failure ?: IOException("热播榜暂不可用")
        val list=HotRank.combine(boards)
        rankingCache[type]=list
        return list
    }
    /** Throws the fetched boards away, so the next request goes to the site again. */
    fun clearRankingCache() { rankingCache.clear() }
    /**
     * One route's page, exactly as the site serves it.
     *
     * The walk consumes this, one route at a time, because each route has its own page count and
     * its own bookmark. Browsing does not: a type is browsed as the whole of it, which is
     * [home]'s job.
     */
    fun routePage(page: Int = 1, route: Route = Route.REAL_DRAMA): CatalogPage {
        require(page in 1..100)
        val data=router("$site/category/${route.categorySlug}"+if(page>1) "?page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    /**
     * Browse one of the catalogues the user picks, named by [ContentType].
     *
     * The retired `?tab=1&sort_type=1` browse path only ever reached 874 shows, and `sort_type` is
     * inert there anyway — 0/1/2/3/4 all answer with byte-identical first pages, so there was no
     * "newest first" ordering to ask for. The named routes are the site's real navigation.
     *
     * A type can be served by more than one route: 漫剧 is `comic-drama` **and** `ai-drama`, and
     * browsing 漫剧 shows both shelves. Each route keeps its own ordering, and they are concatenated
     * rather than interleaved — the first shelf's own "hot" list stays the first thing on screen, and
     * the second shelf follows it on the same page instead of 34 pages later.
     *
     * The hot board deliberately no longer hangs off this function: it is reached through
     * [ranking] alone, so browsing a category and reading a ranking stay separate views
     * instead of one silently replacing the other.
     */
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        // 漫剧这一档由两条货架拼成，其中一条挂掉时不该把整页变成空白：能拿多少算多少，
        // 页面照样能翻。只有两条都失败，才算这次请求失败。
        var failure: Exception?=null
        val pages=type.routes.mapNotNull { route ->
            try { routePage(page,route) } catch(e: Exception) { if(failure==null) failure=e; null }
        }
        if(pages.isEmpty()) throw failure ?: IOException("首页数据暂不可用")
        val items=pages.flatMap { it.items }.distinctBy { it.id }
        return CatalogPage(items,items.isNotEmpty() && pages.any { it.hasMore })
    }
    /**
     * Search the whole catalogue locally when an index is available, otherwise through the public page.
     *
     * The public page is the only source that still answers, and it answers with ten rows and no
     * way to ask for more: `?page=` and sixteen other paging names return byte-identical pages,
     * the page's JavaScript bundle makes no API call of its own, and the signed App endpoint
     * `reading/bookapi/search/tab/v/` returns HTTP 200 with a zero-byte body for every parameter
     * shape tried while `video_model/v1/` returns 24 KB in the same process. So the public page
     * alone is a ten-row ceiling no client change can lift - see [CatalogueIndex], which is built
     * from the catalogue pages that *do* work and turns ten rows into the full 2400.
     *
     * The server is still asked once per search, and whatever it returns that the index has never
     * seen is appended when the page has room. That keeps newly listed shows reachable without
     * letting ten unranked rows outrank a local exact match.
     *
     * [type] narrows the local index to one catalogue, and only when [local] can actually classify
     * its shows — see [CatalogueIndex.labelled]. It cannot narrow the server: the public search page
     * accepts no category parameter at all. That is not a loss in practice, because the server only
     * ever contributes ten top-up rows while the index answers with every match in the library.
     * Measured over the whole library on 2026-10-19 (2400 shows: 800 真人剧, 1600 漫剧),
     * 「都市」 hits 1042 shows of which 670 are 漫剧, so without the split most of what a genre
     * search wanted is mixed in with the other shelf. The three routes are disjoint, so the
     * split itself is clean.
     *
     * [CatalogPage.matchedCount] records how many rows actually mention the keyword. It is not a
     * nicety: the site answers every query with ten rows and never an empty list, so 「站内没有这部
     * 剧」 can only be decided client-side — see [mentions].
     */
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT, local: CatalogueIndex? = null): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        var fuzzy=false
        val localRows = if(local==null) emptyList() else local.searchAllowingTypo(keyword,SEARCH_LOCAL_LIMIT,type).let { (rows,relaxed) -> fuzzy=relaxed; rows }
        // The server is asked on page one only. Its ten rows merely top up a page that the local
        // index has not filled, so re-asking on page two would spend a request to return rows that
        // are already merged in - and with no index at all it would spend one to answer nothing.
        val serverRows = if(page==1) publicSearch(keyword) else emptyList()
        // 分类过滤只在索引真能分类时才生效。索引里的每部剧都记着自己属于哪套目录，才谈得上
        // 「只看漫剧」；索引还欠着标签时（刚装上、或正在补建），硬过滤会把服务端的补位行全判成
        // 「不属于真人剧」而丢掉，搜什么都不出东西 —— 一个看起来生效、实际把人全筛没了的开关，
        // 比没有这个开关更糟。所以这里退回不过滤，界面上另有交代。
        //
        // 索引里根本没有的那些行同样不能丢。站方搜索能搜到的剧，比本机走查得到的多：走查只覆盖
        // 三条分类目录共 2400 部，而《回到心跳开始时》在站方搜索里是第一行，在这 2400 部里一部
        // 都没有（2026-10-19 实测）。这类剧只有站方搜索这一条路，索引永远装不下它；把它按
        // 「不属于当前分类」筛掉，等于让这部剧从此搜不到 —— 恰恰是过滤最不该做的事。
        // 索引判不出来的行一律留下：分类过滤只筛索引能分类的行，判不出来的就别替它下结论。
        // （type 是非空的，所以这里没有 type==null 这一说；编译器本来就在报它恒为 false。）
        val scoped = if(local?.labelled!=true) serverRows else serverRows.filter { row -> local.routeOf(row.id)?.let { it.type==type } ?: true }
        val known = localRows.mapTo(HashSet()) { it.id }
        val unseen = scoped.filterNot { it.id in known }
        // A local hit has to stay alone: 搜「雾锁情迷」finds exactly one show, and topping the page
        // up with the server's fuzzy rows would bury nine unrelated dramas under it. When the index
        // finds nothing at all those rows are the only thing left to show, and they are shown whole
        // under the 「以下是同类推荐」 notice instead.
        val filler = if(localRows.isEmpty()) unseen else unseen.filter { mentions(it.title,keyword) }
        val window = when {
            // No index, or the index matched nothing: the ten server rows are all there is.
            localRows.isEmpty() -> if(page>1) emptyList() else scoped
            else -> {
                val slice = localRows.drop((page-1)*SEARCH_PAGE_SIZE).take(SEARCH_PAGE_SIZE).toMutableList()
                // Only the first page borrows room from the server, so paging never drifts between
                // two different orderings.
                if(page==1 && slice.size<SEARCH_PAGE_SIZE) slice += filler.take(SEARCH_PAGE_SIZE-slice.size)
                slice
            }
        }
        val hasMore = localRows.size > page*SEARCH_PAGE_SIZE
        // A local hit counts even when the word is only in the tags — 《雾锁情迷》 carries a
        // 「总裁」 tag — otherwise the tab would claim 「站内没有《总裁》」 while listing exactly
        // the shows that matched it.
        val matched = localRows.size + filler.count { mentions(it.title,keyword) }
        // What paging will walk in total. Server filler is counted only on page one, where it is
        // actually merged in, so the number does not change as the user turns pages.
        val total = localRows.size + if(page==1) filler.size else 0
        return CatalogPage(window.distinctBy { it.id },hasMore,null,matched,total,fuzzy)
    }
    private fun publicSearch(keyword: String): List<Series> {
        val data=router("$site/search/${Signer.encode(keyword)}?page=1")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        return valid(rows.objects().map(::card))
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
        catch(signed: Exception) {
            // Must be Exception, not IOException: the signed endpoint now answers HTTP 200 with a
            // zero-byte body, so `JSONObject(text(...))` throws org.json.JSONException - a
            // RuntimeException - which an IOException-only catch lets escape past the fallback.
            try { return webStream(seriesId,id,maxQuality) }
            catch(web: Exception) { web.addSuppressed(signed); throw web }
        }
    }
    private fun signedStream(id: String, maxQuality: Int): StreamInfo {
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"video_id":"$id"}""".toByteArray()
        // A fresh identity per signed request: the previously hardcoded pair is shared by every
        // client built from the drpys sources and is now silently blocked (HTTP 200, empty body).
        val signed=signer.sign(VIDEO_MODEL_API,VendorConstants.newDevice(),payload)
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
        /** One screen of search results, matching the 30 rows the list renders. */
        const val SEARCH_PAGE_SIZE = 30
        /** Upper bound on local matches per search, so one keystroke cannot re-rank 2400 rows. */
        private const val SEARCH_LOCAL_LIMIT = 400
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
