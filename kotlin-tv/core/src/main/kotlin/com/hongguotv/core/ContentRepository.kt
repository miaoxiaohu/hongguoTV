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

/**
 * Does this row answer what the user asked?
 *
 * The ordinary rule is that the row's title has to contain the word: the site answers every
 * query with ten confident same-genre rows, and the client has to draw that line itself.
 *
 * The rule bends once, for a word the site will not take. The site caps a query at thirty
 * characters - measured 2026-10-23, a longer one comes back `400 invalid_query`, "Provide one
 * non-empty search query of at most 30 characters" - so a long word has to be cut before it
 * is sent. The cut word is longer than any title that could answer it, because the title is
 * the *beginning* of it, so the ordinary rule can never hold and nothing would ever match.
 *
 * A title that sits inside the word the user typed does answer it: the user typed the show's
 * title and more, and the site answered with the show. Measured the same day - the forty
 * character query 「我在末世打脸女主了第二季番外篇全集高清无删减完整版在线观看…」 comes back
 * 400 as it stands, and cut to thirty it answers with 《我在末世打脸女主了》.
 */
internal fun answers(title: String, keyword: String, truncated: Boolean): Boolean {
    if(mentions(title,keyword)) return true
    if(!truncated) return false
    val needle=normalizeForSearch(keyword)
    val candidate=normalizeForSearch(title)
    return candidate.isNotEmpty() && candidate in needle
}
data class RankPosition(val rank: Int?, val heat: String)
data class HotRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)

/**
 * What happened to the site's own ten rows.
 *
 * The two failures are not the same to the person searching, and telling them apart is the
 * whole point. 「暂时不可用」 means a moment: the network hiccuped, the CDN answered 502, and
 * the same question works again in a minute. 「用不了」 means the site did answer and the
 * answer cannot be used - it refused the query, or it answered with a shape this client does
 * not read. Calling that 「暂时」 sends the user off to wait for something that is not coming
 * back on its own.
 *
 * Measured 2026-10-23 over roughly two hundred requests against the live endpoint: a 502
 * shows up about once in a hundred, while a refusal is standing. So the first is worth
 * waiting for and the second is worth saying out loud.
 */
enum class ServerSearchProblem {
    /** The site's rows were fetched. */
    NONE,
    /** No answer at all: the network, a timeout, or a 5xx. */
    UNAVAILABLE,
    /** An answer that cannot be used: a refusal, or a shape this client does not read. */
    REJECTED,
}
/**
 * [matchedCount] is how many of [items] the keyword actually found, [totalHits] how many exist in
 * total across pages, and [fuzzy] whether that came from the one-character typo fallback. All three
 * are null / false where the page is not a search result and the questions do not apply.
 *
 * [totalPages] is how many pages the whole listing has, when the site says so. It is the one
 * number the app cannot work out for itself: a page of a browse list holds a fixed number of
 * rows, but the rows a page of the site holds are the site's business (measured 2026-10-20: 24 a
 * page, 34 pages for every route), and the app draws the browsed rows in a grid of its own width.
 * Counting pages from the rows on screen would therefore count the app's pages, not the site's,
 * and the two must not be mixed up - see [routePage] and [home].
 */
data class CatalogPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val ranking: HotRanking? = null,
    val matchedCount: Int? = null,
    val totalHits: Int? = null,
    val fuzzy: Boolean = false,
    val totalPages: Int? = null,
    /**
     * 站方那一路取不到了。
     *
     * 搜索的主体是本机索引（2400 部都在里面），站方那十行只是**补位** —— 补的是站上有、
     * 索引里还没有的新剧。所以它取不到的时候，搜索照样能用，只是少了那十行。
     *
     * 2026-10-22 实测：站方把搜索页改成了纯前端渲染（首页、分类页、榜单页仍然服务端渲染），
     * HTML 里不再有 `_ROUTER_DATA`，于是那十行取不到了。此前它一失败，整页就变成
     * 「暂时无法加载」—— 本机索引里明明已经有一千多条命中。
     *
     * 取不到有两种，界面上得说不一样的话：见 [ServerSearchProblem]。
     */
    val serverProblem: ServerSearchProblem = ServerSearchProblem.NONE
) {
    /** 站方那十行是不是没拿到。 */
    val serverFailed: Boolean get() = serverProblem != ServerSearchProblem.NONE
}
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

/**
 * The site answered, but with a shape this client does not read.
 *
 * A standing failure, unlike a timeout or a 502: the site's search page moved on and this
 * client was left behind. Waiting does not bring the old shape back, so the interface says
 * 「用不了」 rather than 「暂时不可用」.
 */
class SearchShapeChangedException: IOException("搜索数据结构已变化")

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

class ContentRepository(val http: OkHttpClient = playbackHttpClient(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    /** The boards already fetched, per shelf, so turning the page costs nothing. */
    private val rankingCache=mutableMapOf<ContentType,HotRank.HotList>()
    /**
     * The transport for one playback attempt's address lookup.
     *
     * Derived from [http], so an injected client (and the interceptors on it) still applies, but
     * with the deadlines an address lookup should have: see [addressHttpClient].
     */
    private val addressHttp=addressHttpClient(http)
    private fun text(url: String, signed: Signer.Request? = null, client: OkHttpClient = http): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(client.newCall(request.build())) { response ->
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
        val list=HotRank.combine(boards,type)
        rankingCache[type]=list
        return list
    }
    /**
     * Throws the fetched boards away, so the next request goes to the site again.
     *
     * Synchronized for the same reason [rankingList] is: it empties the very map that method
     * reads and writes, and a plain [LinkedHashMap] being emptied while another thread walks it
     * is not a safe thing to do.
     */
    @Synchronized fun clearRankingCache() { rankingCache.clear() }
    /**
     * One route's page, exactly as the site serves it.
     *
     * The walk consumes this, one route at a time, because each route has its own page count and
     * its own bookmark. Browsing does not: a type is browsed as the whole of it, which is
     * [home]'s job.
     *
     * The page count the site reports is carried along, because the app cannot work it out for
     * itself: a browsed page is drawn in a grid of the app's own width, so the rows on screen
     * say nothing about how many pages the site has. Measured 2026-10-20: 24 rows a page and 34
     * pages for every route (800 shows).
     */
    fun routePage(page: Int = 1, route: Route = Route.REAL_DRAMA): CatalogPage {
        require(page in 1..100)
        val data=router("$site/category/${route.categorySlug}"+if(page>1) "?page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        val totalPages=minOf(lastPage,100)
        return CatalogPage(items,items.isNotEmpty() && page<totalPages,totalPages=totalPages)
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
        // 同一档的两条货架是齐步走的：一条走完了，这一页就不完整了。所以总页数取短的那条 ——
        // 再往后还能看到长出来的那条，可那不是这一档的完整一页。
        // 站方今天给三条货架报的都是 34 页（实测 2026-10-20，800 部，一页 24 条），取短的那条
        // 不改变今天的数字，但它写的是那条规则，而不是今天恰好相等。
        val totalPages=pages.mapNotNull { it.totalPages }.minOrNull()
        // 「还有下一页」和「一共有几页」必须是同一个数。不然页码选择器能翻到的最后一页，
        // 和「下一页」按到底的那一页，会打起来。
        val hasMore=if(totalPages!=null) items.isNotEmpty() && page<totalPages
            else items.isNotEmpty() && pages.any { it.hasMore }
        return CatalogPage(items,hasMore,totalPages=totalPages)
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
        // 站方的搜索接口只收 30 个字（2026-10-23 实测：超了回 400 invalid_query，
        // "Provide one non-empty search query of at most 30 characters"）。超长的话只能截。
        //
        // 截的是**问站方的那一句**，不是用户打的那一句：本机索引是我们自己的数据，没有这个限制。
        // 两侧各走各的路 —— 本机这一侧认「剧名是这几个字的开头」（[CatalogueIndex] 的 loose），
        // 站方答回来的行按 [answers] 判是不是命中。
        val siteKeyword=keyword.take(SITE_QUERY_LIMIT)
        val truncated=siteKeyword.length<keyword.length
        var fuzzy=false
        val localRows = if(local==null) emptyList() else local.searchAllowingTypo(keyword,SEARCH_LOCAL_LIMIT,type,truncated).let { (rows,relaxed) -> fuzzy=relaxed; rows }
        // 命中的总数要单独数一遍，不能从手上这份列表数出来。列表是给一页用的，总数是给
        // 「第 X/Y 页 · 共 N 部」用的，两者的口径必须一致 —— 之前总数是列表长度，而列表被
        // 截断过，于是「都市」明明命中 1042 部却写成「共 400 部」，翻过 13 页之后剩下的
        // 600 多部再也走不到，翻页还会撞上一张「没有找到相关剧」的空白页。
        // 命中为零时走的是错字容错，那份列表才是用户看到的，所以总数就取它。
        val localTotal=if(local==null) 0 else if(fuzzy) localRows.size else local.count(keyword,type,truncated)
        // The server is asked on page one only. Its ten rows merely top up a page that the local
        // index has not filled, so re-asking on page two would spend a request to return rows that
        // are already merged in - and with no index at all it would spend one to answer nothing.
        //
        // 取不到就算了，不能把整页拖下水：它只是补位，搜索的主体是本机索引。
        // 2026-10-22 实测站方把搜索页改成了纯前端渲染，这一路已经取不到了（首页/分类/榜单仍正常）。
        // 之前它一失败，整页就变成「暂时无法加载」，而本机索引里明明有上千条命中。
        //
        // 但「取不到」有两种，界面上不能都叫「暂时」：见 [ServerSearchProblem]。
        var serverProblem=ServerSearchProblem.NONE
        val serverRows=if(page==1) try { publicSearch(siteKeyword) }
        catch(e: Exception) { serverProblem=classifyServerFailure(e); emptyList() }
        else emptyList()
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
        val filler = if(localRows.isEmpty()) unseen else unseen.filter { answers(it.title,keyword,truncated) }
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
        val hasMore = localTotal > page*SEARCH_PAGE_SIZE
        // A local hit counts even when the word is only in the tags — 《雾锁情迷》 carries a
        // 「总裁」 tag — otherwise the tab would claim 「站内没有《总裁》」 while listing exactly
        // the shows that matched it.
        val matched = localTotal + filler.count { answers(it.title,keyword,truncated) }
        // What paging will walk in total. Server filler is counted only on page one, where it is
        // actually merged in, so the number does not change as the user turns pages.
        val total = localTotal + if(page==1) filler.size else 0
        return CatalogPage(window.distinctBy { it.id },hasMore,null,matched,total,fuzzy,serverProblem=serverProblem)
    }
    /**
     * 站方那一路的失败，是「等一等就好」还是「等不来」。
     *
     * 站方用 5xx 说「现在不行，过会儿再来」（实测约百分之一），用 4xx 说「这个查询我不接」——
     * 后者是常驻的，等下去也不会变。形状变了（答了但读不懂）同样是常驻的：等不会把站方改回去。
     *
     * 所以：5xx 归「暂时」，4xx 和读不懂归「用不了」。网络错误（超时、断线）也是暂时的 ——
     * 它跟 5xx 是同一种事。
     */
    private fun classifyServerFailure(e: Exception): ServerSearchProblem = when {
        e is SearchShapeChangedException -> ServerSearchProblem.REJECTED
        e is HttpStatusException && e.code in 400..499 -> ServerSearchProblem.REJECTED
        e is org.json.JSONException -> ServerSearchProblem.REJECTED
        else -> ServerSearchProblem.UNAVAILABLE
    }
    /**
     * 站方搜索的十条补位行。
     *
     * 2026-10-22 实测：站方把 `/search/{关键词}` 改成了**纯前端渲染** —— HTML 里不再有
     * `_ROUTER_DATA` 数据块（三个不同关键词返回的页面字节完全相同），数据改由
     * `/api/search/result?query=…` 下发。**参数名是 `query`，不是 `keyword`**（用 keyword
     * 回的是 400，用 query 才回 200）。
     *
     * 字段形状和当年 SSR 那份一模一样（`searchList[].video_data.*`），所以解析照旧。
     * 这个接口还能翻页（请求参数 `page`，响应里是 `pageNum`/`totalPages`/`hasMore`），
     * 但 App 的搜索主体是本机索引，站方这一路仍然只问第一页 —— 它只是补位。
     *
     * 这个接口只收 30 个字（[SITE_QUERY_LIMIT]），超了回 400。截断在 [search] 里做。
     */
    private fun publicSearch(keyword: String): List<Series> {
        val body=text("$site/api/search/result?query=${Signer.encode(keyword)}")
        val rows=JSONObject(body).optJSONArray("searchList") ?: throw SearchShapeChangedException()
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
            //
            // The fallback is only worth making when the signed endpoint *answered* with
            // something unusable. When the network is gone, the public page is the same dead
            // network: asking it as well doubles the silence before the user learns that the
            // episode cannot be played, and the retry counter does not start until it returns.
            if(fallbackCannotHelp(signed)) throw signed
            try { return webStream(seriesId,id,maxQuality) }
            catch(web: Exception) { web.addSuppressed(signed); throw web }
        }
    }
    private fun signedStream(id: String, maxQuality: Int): StreamInfo {
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"video_id":"$id"}""".toByteArray()
        // A fresh identity per signed request: the previously hardcoded pair is shared by every
        // client built from the drpys sources and is now silently blocked (HTTP 200, empty body).
        val signed=signer.sign(VIDEO_MODEL_API,VendorConstants.newDevice(),payload)
        val data=JSONObject(text(signed.url,signed,addressHttp))
        val code=if(data.has("code")) data.optInt("code",-1) else data.optInt("Code",-1)
        if(code!=0) throw IOException("播放接口暂不可用")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        // video_model arrives as a JSON-encoded string, not a nested object.
        val model=map.optJSONObject("video_model") ?: runCatching { JSONObject(map.str("video_model")) }.getOrNull() ?: throw IOException("播放信息为空")
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback,client=addressHttp)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
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
        val html=try { text("$site/player/$seriesId/$id",client=addressHttp) }
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
        /** How long a connection to the site or the CDN may take. */
        const val CONNECT_TIMEOUT_S = 15L
        /** How long a single socket may sit without data. */
        const val READ_TIMEOUT_S = 30L
        /** How long one request may take from start to finish. */
        const val CALL_TIMEOUT_S = 45L
        /**
         * The transport every stream of this app uses.
         *
         * The client that prepares the next episode is the client that plays it afterwards, so
         * this carries the timeouts a whole download needs. The player pulls the video in 512 KiB
         * ranges and each of those is one call that has to finish inside [CALL_TIMEOUT_S]; a
         * timeout short enough for a preparation would be short enough to fail a slow chunk of
         * the episode it prepared.
         *
         * A preparation has its own deadline instead - see [PrefetchPolicy.BUDGET_MS] - which is
         * what keeps a stalled preparation from holding the uplink.
         */
        fun playbackHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S,TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S,TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S,TimeUnit.SECONDS)
            .build()
        /**
         * How long one route of the address lookup may take.
         *
         * The player is a box in the living room, and the person is looking at 「正在获取播放地址…」.
         * The retry counter does not start until this call comes back, so every second spent
         * here is a second before the first retry. A dropped handshake used to sit there for the
         * whole 15-second connect timeout of the video transport - a transport that has to carry
         * 512 KiB chunks of a whole episode, and therefore cannot be tightened. An address lookup
         * is one small JSON, so it gets its own, much shorter leash.
         */
        const val ADDRESS_CONNECT_TIMEOUT_S = 5L
        /**
         * How long one address lookup may take from start to finish.
         *
         * This is the number the person actually waits, so this is the one that has to be small.
         * It bounds the whole lookup - every route, the DNS, the handshake and the response -
         * because it is the call timeout, not the connect timeout, that ends the call.
         */
        const val ADDRESS_CALL_TIMEOUT_S = 10L
        /**
         * The transport one playback attempt's address lookup uses.
         *
         * Derived from the transport it is given, so an injected client and its interceptors
         * still apply - only the deadlines change.
         *
         * OkHttp's own retry stays on. These hosts resolve to several addresses and not all of
         * them answer - measured on the phone: the same name came back as a working address and
         * as a black hole within a minute. One dead address must not kill the lookup, or the
         * episode stops playing for a reason nobody can see. The wait is bounded by
         * [ADDRESS_CALL_TIMEOUT_S] instead, which is the only bound the person watching the
         * screen can feel.
         */
        fun addressHttpClient(base: OkHttpClient = playbackHttpClient()): OkHttpClient = base.newBuilder()
            .connectTimeout(ADDRESS_CONNECT_TIMEOUT_S,TimeUnit.SECONDS)
            .callTimeout(ADDRESS_CALL_TIMEOUT_S,TimeUnit.SECONDS)
            .build()
        /** One screen of search results, matching the 30 rows the list renders. */
        const val SEARCH_PAGE_SIZE = 30
        /**
         * The most characters the site's search endpoint takes.
         *
         * Measured 2026-10-23 against the live endpoint: a longer query comes back
         * `400 {"code":"invalid_query","message":"Provide one non-empty search query of at most
         * 30 characters."}` - a refusal, not a failure, and it does not go away by waiting. So a
         * long word has to be cut before it is sent, and the cut has to be disclosed: the user
         * typed forty characters and the site was asked about thirty of them.
         */
        const val SITE_QUERY_LIMIT = 30
        /**
         * How many local matches a search keeps.
         *
         * The site carries 2400 shows, so this is a ceiling no index can reach; it is here so a
         * future one cannot make a keystroke hand back an unbounded list.
         *
         * It does **not** bound the work: [CatalogueIndex.search] sorts every match before it
         * takes any of them, so a smaller cap cost exactly the same time and did one thing
         * besides — it hid the rest of the library. The page counter and the 「共 N 部」 line
         * were then computed from what was left, so both under-reported and the pages past the
         * cap came up empty for a search that plainly had more.
         */
        const val SEARCH_LOCAL_LIMIT = 5000
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
