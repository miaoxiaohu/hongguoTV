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
internal object HotRank {
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
    fun merge(page: Int, boards: List<Board>): CatalogPage {
        val rows = boards.flatMap { board -> board.items.map { series -> board.positions[series.id]?.heat to series } }
        val ordered = rows.sortedByDescending { heatValue(it.first) ?: Long.MIN_VALUE }
        // The site's own ranks are per route but continuous across pages - measured: page 2 of a
        // board starts at 21, not at 1 - so the merged list starts where the site says this page
        // starts, once per board. With one board that reproduces the site's numbering exactly; with
        // two it is the shelf's own numbering, which is the thing the site does not publish.
        val siteFirst = boards.mapNotNull { board -> board.positions.values.mapNotNull { it.rank }.minOrNull() }.minOrNull()
        val base = if (siteFirst != null) (siteFirst - 1) * boards.size else (page - 1) * ordered.size
        val positions = LinkedHashMap<String, RankPosition>()
        ordered.forEachIndexed { index, (heat, series) -> positions[series.id] = RankPosition(base + index + 1, heat.orEmpty()) }
        val items = ordered.map { it.second }
        val totalPages = boards.minOfOrNull { it.totalPages } ?: 1
        val updated = boards.firstOrNull { it.updatedText.isNotBlank() }?.updatedText.orEmpty()
        return CatalogPage(items, items.isNotEmpty() && page < totalPages, HotRanking(positions, totalPages, updated))
    }

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
}
