// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * The site's hot boards.
 *
 * The site serves one board per route - `/rank/hot-real-drama`, `/rank/hot-comic-drama`,
 * `/rank/hot-ai-drama` - and one overall board, `/rank/hot-drama`, which is the same hot list with
 * the routes interleaved. Measured 2026-10-19: 《谁让这个文盲修仙的第五季》 reads 「7613万热度」
 * on the comic board and on the overall board, and the overall board's first rows are the comic
 * board's first two, then the AI board's first two, then the real one, in descending heat.
 *
 * So a show's heat belongs to the show and not to the board it is printed on. That is what makes a
 * shelf's board possible at all - 漫剧 is two routes on the site, comic-drama and ai-drama, and
 * there is no board holding both, but their heat values are comparable, so the two boards can be
 * put into the one list the user expects: the hottest 漫剧 shows first. See [merge].
 *
 * The boards also disagree about where their data lives. The AI and overall boards carry it as
 * deferred JSON; the real and comic boards do not, and their rows have to be read out of the
 * rendered cards instead. Both paths are tried, in that order.
 *
 * Read the website's serialized data only; never execute its scripts.
 */
object HotRank {
    /** One board, as the site serves it. */
    class Board(
        val route: Route,
        val items: List<Series>,
        val positions: Map<String, RankPosition>,
        val totalPages: Int,
        val updatedText: String
    )

    /** The router key the site files this route's board under. */
    fun routeKey(route: Route) = "rank_${route.rankPath}/page"

    fun parse(html: String, page: Int, route: Route): Board {
        try {
            val document = Jsoup.parse(html)
            val updatedText = runCatching {
                ContentRepository.extractRouter(html).optJSONObject("loaderData")
                    ?.optJSONObject(routeKey(route))?.str("updatedText")
            }.getOrNull().orEmpty()
            for (tag in document.select("script[data-fn-name][data-fn-args]")) {
                val name = tag.attr("data-fn-name")
                if (name != "r" && name != "mergeLoaderData") continue
                val args = JSONArray(tag.attr("data-fn-args"))
                if (args.optString(0) != routeKey(route)) continue
                val raw = if (name == "r" && args.optString(1) == "content") args.opt(2) else if (name == "mergeLoaderData") {
                    args.optJSONArray(1)?.objects()?.firstOrNull { it.str("key") == "content" && it.str("routerDataFnName") == "p" }
                        ?.optJSONArray("routerDataFnArgs")?.opt(0)
                } else null
                val payload = when (raw) { is JSONObject -> raw; is String -> JSONObject(raw); else -> continue }
                if (!payload.optBoolean("isSuccess")) throw IOException("${route.label}榜暂不可用")
                val rows = payload.optJSONArray("rankList") ?: throw IOException("${route.label}榜格式已变化")
                val pagination = payload.optJSONObject("pagination") ?: throw IOException("${route.label}榜分页信息缺失")
                val totalPages = pagination.optInt("totalPages", 0)
                if (totalPages < 1 || pagination.optInt("pageNum", page) != page) throw IOException("${route.label}榜分页信息异常")
                val positions = linkedMapOf<String, RankPosition>()
                val items = rows.objects().map { row ->
                    val count = row.optInt("episodeCount", row.optJSONArray("episodeVids")?.length() ?: 0)
                    val tags = row.optJSONArray("tags") ?: JSONArray()
                    val id = row.str("seriesId").ifEmpty { row.str("id") }
                    if (id !in positions) positions[id] = RankPosition(row.optInt("rank").takeIf { it > 0 }, row.str("heatText"))
                    Series(id, row.str("title"), cleanUrl(row.str("cover")),
                        row.str("description"), if (count > 0) "全 $count 集" else row.str("heatText"),
                        (0 until minOf(tags.length(), 5)).joinToString(" · ") { tags.optString(it) })
                }.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
                return Board(route, items, positions.filterKeys { id -> items.any { it.id == id } },
                    minOf(totalPages, 100), updatedText)
            }
            return renderedBoard(document, html, page, route)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("${route.label}榜格式已变化", e)
        }
    }

    private fun renderedBoard(document: Document, html: String, page: Int, route: Route): Board {
        // Some responses contain rendered cards while the deferred JSON is still unresolved.
        val routeData = ContentRepository.extractRouter(html).optJSONObject("loaderData")
            ?.optJSONObject(routeKey(route)) ?: throw IOException("页面没有${route.label}榜数据")
        if (routeData.str("rankKey") != route.rankKey || routeData.optInt("pageNum") != page) {
            throw IOException("${route.label}榜分页信息异常")
        }
        val positions = linkedMapOf<String, RankPosition>()
        val items = document.select("article[aria-labelledby^=rank-title-]").mapNotNull { article ->
            val heading = article.attr("aria-labelledby")
            val id = heading.removePrefix("rank-title-")
            val title = article.getElementById(heading)?.text().orEmpty()
            if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            if (id !in positions) positions[id] = RankPosition(
                article.selectFirst("[class*=badge-number-]")?.text()?.toIntOrNull()?.takeIf { it > 0 },
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty())
            Series(id, title, article.selectFirst("img[src]")?.attr("src").orEmpty(),
                article.selectFirst("p[class*=description-]")?.text().orEmpty(),
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty(),
                article.select("p[class*=categories-] > span").take(5).joinToString(" · ") { it.text() })
        }.distinctBy { it.id }
        if (items.isEmpty()) throw IOException("页面没有${route.label}榜数据")
        val paging = document.selectFirst("nav[aria-label=榜单分页]") ?: throw IOException("${route.label}榜分页信息缺失")
        if (paging.selectFirst("[aria-current=page]")?.text()?.toIntOrNull() != page) throw IOException("${route.label}榜分页信息异常")
        val lastPage = maxOf(page, paging.select("a[href]").mapNotNull { it.attr("href").substringAfter("?page=", "").substringBefore('&').toIntOrNull() }.maxOrNull() ?: page)
        return Board(route, items, positions, minOf(lastPage, 100), routeData.str("updatedText"))
    }

    /**
     * Puts the boards of one shelf into one hot list.
     *
     * The site's own ranks are per route - the comic board counts from 1 among comic shows, the AI
     * board from 1 among AI shows - so two boards on one page would show two "第 1 名". The merged
     * list is renumbered instead, and it continues across pages the way the site's own boards do
     * (measured: page 2 of a board starts at 21, not at 1).
     *
     * The order is the heat, descending, because the heat is comparable across boards. A heat that
     * cannot be read is put last rather than guessed at: one row out of place is a small thing,
     * and inventing a number for it would be a lie in the place the user reads a ranking.
     */
    /**
     * The cards of one app page, when the caller does not say.
     *
     * The app's grid is six columns wide, and a page whose size is not a multiple of six ends
     * with a short row - the one thing the rest of the app is careful never to do. Eighteen is
     * three full rows, which is the page size the app's browse lists use. The app passes its own
     * size in, because the row width is the app's business and the two must not drift apart.
     */
    const val DEFAULT_PAGE_SIZE = 18

    /**
     * How long a fetched ranking is served before it is fetched again.
     *
     * The site's boards are updated once a day - the page says 「10月20日已更新」 - so fetching
     * them more often only spends the site's rate for the same list. A day is also what the user
     * expects: a hot list that changes under their feet every few hours is not a hot list.
     */
    const val FRESH_MS = 24 * 60 * 60 * 1000L

    /** A shelf's hot list: every show it holds, in one order, with the place each one holds. */
    class HotList(
        val items: List<Series>,
        val positions: Map<String, RankPosition>,
        val updatedText: String,
        val fetchedAt: Long,
        /**
         * The shelf this list was fetched for.
         *
         * The two shelves are separate boards and the user picks between them, so a list is only
         * ever good for the shelf it came from. The field is what makes that checkable: a list that
         * cannot say which shelf it belongs to must not be shown at all, because the comic board's
         * first row under the real shelf's heading is not a stale list, it is somebody else's list.
         */
        val type: ContentType
    ) {
        /** True when this list is old enough to be worth fetching again. */
        fun isStale(now: Long) = now - fetchedAt > FRESH_MS
    }

    /**
     * Puts the boards of a shelf into one list, in one heat order.
     *
     * The heat is a per-show number and reads the same on every board a show appears on, so the
     * boards can be put in one order. That order is the whole point: sorting one board at a time
     * would put the comic board's number 21 - hotter than the AI board's number 30 - after it, and
     * the rank shown on screen would not be a rank at all. Measured 2026-10-19 on the 漫剧 shelf:
     * the first screen's last card reads 5437万 and the second screen's first card reads 5500万.
     *
     * The ranks here are the place in that one order, counted from the hottest show of the shelf.
     * A heat that cannot be read is put last rather than guessed at: one row out of place is a
     * small thing, and inventing a number for it would be a lie in the place the user reads a
     * ranking.
     */
    fun combine(boards: List<Board>, type: ContentType): HotList {
        val rows = boards.flatMap { board -> board.items.map { series -> board.positions[series.id] to series } }
        // The same show can be on two boards - the site's overall board holds the hottest shows of
        // every route, and a route's own board can repeat one. The hotter copy is the first one
        // in heat order, so the id is kept once.
        val seen = HashSet<String>()
        val ordered = rows.filter { seen.add(it.second.id) }
            .sortedByDescending { heatValue(it.first?.heat) ?: Long.MIN_VALUE }
        val positions = LinkedHashMap<String, RankPosition>()
        ordered.forEachIndexed { index, (position, series) -> positions[series.id] = RankPosition(index + 1, position?.heat.orEmpty()) }
        val updated = boards.firstOrNull { it.updatedText.isNotBlank() }?.updatedText.orEmpty()
        return HotList(ordered.map { it.second }, positions, updated, System.currentTimeMillis(), type)
    }

    /**
     * One page of the list.
     *
     * The app's grid is six columns wide, and a page whose size is not a multiple of six ends
     * with a short row - the one thing the rest of the app is careful never to do. The app passes
     * its own size in, because the row width is the app's business and the two must not drift
     * apart.
     */
    fun page(list: HotList, page: Int, pageSize: Int): CatalogPage {
        require(page>=1 && pageSize>0)
        val items=list.items.drop((page-1)*pageSize).take(pageSize)
        val totalPages=maxOf(1,(list.items.size+pageSize-1)/pageSize)
        return CatalogPage(items,items.isNotEmpty() && page<totalPages,HotRanking(list.positions,totalPages,list.updatedText))
    }

    /**
     * The list as it is written down, so the next launch has something to show before the
     * network answers.
     *
     * The description of each show is left out, the way the catalogue index leaves it out: the
     * detail page fetches it when the show is opened, and keeping two hundred of them would
     * make the file about twice the size for text nobody reads here.
     */
    fun encode(list: HotList): String = JSONObject()
        .put("fetchedAt",list.fetchedAt)
        .put("updatedText",list.updatedText)
        // The shelf travels with the list. Without it a written-down list cannot be handed to a
        // shelf without guessing, and guessing here is how one shelf ends up showing another's board.
        .put("shelf",list.type.storedValue)
        .put("items",JSONArray(list.items.map { series ->
            val position=list.positions[series.id]
            JSONObject().put("i",series.id).put("t",series.title).put("c",series.cover)
                .put("b",series.badge).put("g",series.tags)
                .put("r",position?.rank ?: JSONObject.NULL).put("h",position?.heat.orEmpty())
        })).toString()

    /** Reads back what [encode] wrote; null for anything unreadable - a broken list is not worth failing over. */
    fun decode(value: String): HotList? = runCatching {
        val o=JSONObject(value)
        // 存下来的旧版本没有「属于哪一档」这一栏。那份榜单属于哪一档已经无从判断，
        // 宁可当作没有 —— 猜一个（比方说一律当漫剧）会让真人剧那一档画出漫剧的榜，
        // 而且一猜就错一整天。
        val type=if(!o.has("shelf")) null else ContentType.entries.firstOrNull { it.storedValue==o.optString("shelf") }
        if(type==null) return@runCatching null
        val items=ArrayList<Series>()
        val positions=LinkedHashMap<String,RankPosition>()
        o.getJSONArray("items").objects().forEach { row ->
            val id=row.getString("i")
            items+=Series(id,row.getString("t"),row.optString("c",""),"",row.optString("b",""),row.optString("g",""))
            positions[id]=RankPosition(if(row.isNull("r")) null else row.optInt("r"),row.optString("h",""))
        }
        require(items.isNotEmpty())
        HotList(items,positions,o.optString("updatedText",""),o.getLong("fetchedAt"),type)
    }.getOrNull()

    /**
     * "8370万热度" -> 837000000.
     *
     * The site's number is occasionally broken by a stray control character inside it - measured on
     * the real board: 「5997 万热度」 - so the text is stripped down to the digits and the unit
     * before it is read. A heat that is not there at all reads as null, and the caller decides
     * what to do with a row it cannot place.
     */
    fun heatValue(heat: String?): Long? {
        val text = heat?.filter { it.isDigit() || it == '.' || it == '万' || it == '亿' } ?: return null
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)([万亿]?)").find(text) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val scale = when (match.groupValues[2]) { "万" -> 10_000.0; "亿" -> 100_000_000.0; else -> 1.0 }
        return (number * scale).toLong()
    }

    /**
     * The hot lists, one per shelf.
     *
     * The two shelves are separate boards and the user picks between them, so a list belongs to
     * exactly one of them. Holding them in one place keyed by the shelf the list itself carries
     * makes handing a shelf the wrong board impossible: there is no list without a shelf to look
     * it up by.
     *
     * This is not a theoretical worry. The lists used to be held as one bare list with no shelf on
     * it, and the page then drew whatever that one list was - so switching from 漫剧 to 真人剧 put
     * the comic board under the real shelf's heading, and the real shelf's own board was never
     * even fetched, because the cached list answered for it and was younger than a day.
     */
    class Cache {
        private val lists=mutableMapOf<ContentType,HotList>()

        /** The list held for [type], whatever its age. */
        fun get(type: ContentType): HotList? = lists[type]

        /** The list held for [type], if it is young enough to show. */
        fun fresh(type: ContentType, now: Long): HotList? = lists[type]?.takeIf { !it.isStale(now) }

        /** Keeps a list under the shelf it was fetched for. */
        fun put(list: HotList) { lists[list.type]=list }

        companion object {
            /** The key a shelf's list is written under. */
            fun key(type: ContentType) = "ranking-${type.storedValue}"
        }
    }
}
