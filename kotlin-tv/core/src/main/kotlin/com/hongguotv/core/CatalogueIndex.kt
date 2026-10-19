// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A local index of every show the site carries, so search is not capped at the ten rows it renders.
 *
 * The site will not page its search. `/search/<keyword>` server-renders exactly ten rows and
 * ignores seventeen different paging parameters; the page's JavaScript bundle makes no API call of
 * its own, so the public site shows the same ten; and the signed App endpoint
 * `reading/bookapi/search/tab/v/` answers HTTP 200 with a zero-byte body for thirteen different
 * parameter shapes while a control request to `video_model/v1/` returns 24 KB in the same process.
 * Ten is therefore the server's ceiling, not a client-side shortcoming.
 *
 * What the site *does* page is the catalogue: 34 pages x 24 rows in each of the three named
 * routes, 2400 shows with zero overlap between them. [CatalogueBuilder] walks those, and this
 * class searches the result locally.
 *
 * Matching is ranked rather than boolean, because a flat "contains" loses the show the user
 * actually typed: 「谁让这个文盲修仙的」 must come before 「谁让这个文盲修仙的第三季」's neighbours.
 */
class CatalogueIndex(entries: List<Series>, routes: List<Route?> = emptyList()) {
    /**
     * Entries are kept in catalogue order so ties resolve to the site's own ranking.
     *
     * Normalised titles and tags are computed once here: a search sweeps all 2400 entries, and
     * re-running two regex replaces over each of them per keystroke would be the slowest part of a
     * keystroke.
     */
    private val ordered: List<Entry> = run {
        val rows = ArrayList<Entry>(entries.size)
        val at = LinkedHashMap<String, Int>(entries.size.coerceAtLeast(1))
        entries.forEachIndexed { position, series ->
            val route = routes.getOrNull(position)
            val known = at[series.id]
            if (known == null) {
                at[series.id] = rows.size
                rows += Entry(series, normalizeForSearch(series.title), normalizeForSearch(series.tags), route)
            } else if (route != null) {
                // 同一部剧可能来两次：一次来自还没记分类的老索引，一次来自补记分类的重走查。
                // 记了分类的那份必须赢。老的 distinctBy 留的是先到的那份，也就是没分类的那份 ——
                // 于是标签抓回来了、存下去了，搜索按分类过滤时却仍然把这一行当成「哪边都算」，
                // 分不开，而且补多少次都补不好。
                rows[known] = Entry(rows[known].series, rows[known].title, rows[known].tags, route)
            }
        }
        rows
    }

    /**
     * One entry plus which route it came from.
     *
     * [route] is null for an index written before the catalogues were labelled, and for tests
     * that never had a catalogue in mind. Those entries are treated as belonging to every filter, so
     * an old index still browses rather than coming up empty.
     */
    private class Entry(val series: Series, val title: String, val tags: String, val route: Route?)

    /**
     * True when every show here records the route it came from.
     *
     * Only then can a search be split by catalogue. A missing label reads as 「belongs to both」 so
     * that an index written before the labels existed still browses — but that same leniency makes a
     * filter over such an index a no-op that looks like it worked. Callers check this and say so
     * rather than serving results that quietly ignore what the user selected.
     */
    val labelled: Boolean = ordered.all { it.route != null }

    /**
     * Ranked matches for [keyword], best first, capped at [limit].
     *
     * Blank input matches nothing rather than everything; [ContentRepository.mentions] relies on
     * the same rule, and an index that returned the whole library for a stray space would defeat
     * the 「站内没有这部剧」 notice entirely.
     *
     * [type] narrows to one catalogue. It is worth having because the same word lands on both:
     * measured over the whole library on 2026-10-19 (2400 shows), 「都市」 hits 1042 of which 670
     * are 漫剧, 「穿越」 545 of which 476, 「重生」 358 of which 289. Searching the whole library for
     * a genre therefore buries most of what the user wanted. Null means no filter, which is also
     * what an index whose shows carry no catalogue label falls back to.
     */
    fun search(keyword: String, limit: Int = DEFAULT_LIMIT, type: ContentType? = null): List<Series> {
        val needle = normalizeForSearch(keyword)
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val pool = if (type == null) ordered else ordered.filter { it.route == null || it.route.type == type }
        return pool.asSequence()
            .mapIndexedNotNull { order, entry -> rank(entry, needle)?.let { Ranked(entry.series, it, order) } }
            .sortedWith(compareBy({ it.rank }, { it.series.title.length }, { it.order }))
            .take(limit)
            .map { it.series }
            .toList()
    }

    /** Which route [id] was filed under, or null when the index never recorded it. */
    fun routeOf(id: String): Route? = ordered.firstOrNull { it.series.id == id }?.route

    /** How many entries match, ignoring [CatalogueIndex.search]'s cap. */
    fun count(keyword: String): Int {
        val needle = normalizeForSearch(keyword)
        if (needle.isEmpty()) return 0
        return ordered.count { rank(it, needle) != null }
    }

    /** The number of shows this index holds, for the settings line and the build progress. */
    val size get() = ordered.size

    /**
     * [size] shows starting at [offset] within one catalogue.
     *
     * The order is that catalogue's own, not a ranking of ours and not newest-first - the earlier
     * `newest()` name claimed something the data cannot support, and a user reasonably asked why
     * 「随便看看」was showing these particular shows. The honest answer had to be a name too.
     *
     * [type] filters to one catalogue because the routes behind it are separate shelves with
     * separate ordering: walking them one after the other put all 800 真人剧 ahead of all 1600
     * 漫剧, so a filtered view over the flat list is what makes 「真人剧」and 「漫剧」each start at
     * its own front page instead of the 漫剧 filter landing 800 rows in. Null means "no filter",
     * which is also what an index written before catalogues were labelled falls back to.
     */
    fun browse(offset: Int, size: Int, type: ContentType? = null): List<Series> {
        if (offset < 0 || size <= 0) return emptyList()
        val source = if (type == null) ordered else ordered.filter { it.route == null || it.route.type == type }
        return source.drop(offset).take(size).map { it.series }
    }

    /** How many shows [type] holds, so the browse label can say 「共 N 部」. */
    fun sizeOf(type: ContentType): Int = ordered.count { it.route == null || it.route.type == type }

    /**
     * [search], then a one-character fallback when nothing matched literally.
     *
     * Typing a long Chinese title on a TV keyboard gets a character wrong often enough to be worth
     * absorbing: 搜「雾锁请迷」should still find 《雾锁情迷》. The fallback only runs on an empty
     * result, so it can never displace a real match, and only for queries of four characters or
     * more — on a two-character term a single slip lands on an unrelated word, and a confident
     * wrong answer is worse than an empty page.
     *
     * The boolean says whether the results came from the fallback, so the tab can say so.
     */
    fun searchAllowingTypo(keyword: String, limit: Int = DEFAULT_LIMIT, type: ContentType? = null): Pair<List<Series>, Boolean> {
        val literal = search(keyword, limit, type)
        if (literal.isNotEmpty()) return literal to false
        val needle = normalizeForSearch(keyword)
        if (needle.length < TYPO_FLOOR) return emptyList<Series>() to false
        // The fallback has to respect the same filter, or a mistyped 漫剧 title would come back as
        // a 真人剧 and the page would claim to be filtered when it is not.
        val pool = if (type == null) ordered else ordered.filter { it.route == null || it.route.type == type }
        val relaxed = pool.asSequence()
            .mapNotNull { entry -> editDistanceWithinOne(needle, entry.title).let { it to entry } }
            .filter { it.first <= 1 }
            .sortedWith(compareBy({ it.first }, { it.second.series.title.length }))
            .take(limit)
            .map { it.second.series }
            .toList()
        return relaxed to relaxed.isNotEmpty()
    }

    /**
     * Levenshtein distance, capped at [budget].
     *
     * Plain two-row DP. Two wrong shortcuts were tried and both are worth recording:
     *
     * - A hand-rolled "advance only the longer side" walk scored a substitution at the last
     *   character wrong while getting the same substitution mid-string right by luck.
     * - Bailing out when a row's minimum exceeds the budget is worse: the DP row minimum is *not*
     *   monotone. For 「总裁回未」 against 「总裁归来」 row 2 bottoms out at 2, yet row 3 realigns on
     *   the shared 回 and drops back to 1. The total really is a single substitution.
     *
     * Only the length-gap early exit survives, because it is a property of the inputs rather than
     * of the matrix. A 2400-show sweep is a couple of milliseconds, and it runs only when the
     * literal search came back empty.
     *
     * @return the distance, or `budget + 1` if it is at least that.
     */
    internal fun editDistanceWithinOne(a: String, b: String, budget: Int = 1): Int {
        if (a == b) return 0
        // A length gap larger than the budget can never be closed by the edits left.
        if (kotlin.math.abs(a.length - b.length) > budget) return budget + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + substitution)
            }
            previous = current
        }
        val distance = previous[b.length]
        return if (distance > budget) budget + 1 else distance
    }

    private data class Ranked(val series: Series, val rank: Int, val order: Int)

    private fun rank(entry: Entry, needle: String): Int? {
        // Tags are a genuine fallback: 「总裁」 is a tag on 《雾锁情迷》 even though its title does
        // not contain the word, and the site's own search ranks such rows first too.
        return when {
            entry.title == needle -> 0
            entry.title.startsWith(needle) -> 1
            needle in entry.title -> 2
            needle in entry.tags -> 3
            else -> null
        }
    }

    companion object {
        /** A page shows 30 rows; this leaves room for a few pages of scroll without unbounded work. */
        const val DEFAULT_LIMIT = 200
        /** Below this length a one-character slip is a different word, not a typo. */
        const val TYPO_FLOOR = 4
    }
}

/**
 * A decoded index plus the walk state needed to carry on from where it stopped.
 *
 * @property entries the shows themselves, so a build can append to them without refetching.
 * @property cursor route -> last page already folded in.
 * @property done routes that reached their final page.
 */
data class CatalogueSnapshot(
    val entries: List<Series>,
    /** Which route each entry in [entries] came from, parallel list; null where unrecorded. */
    val routes: List<Route?>,
    val cursor: Map<Route, Int>,
    val done: Set<Route>
) {
    val index: CatalogueIndex by lazy { CatalogueIndex(entries, routes) }
    val size get() = entries.size
    /** True when the walk reached the end of every route. */
    val walkedToEnd get() = done.size == Route.entries.size
    /**
     * True when every show records the route it came from.
     *
     * The size check matters as much as the null check: [routes] defaults to empty, and `all` over an
     * empty list is vacuously true, so a snapshot carrying no labels at all would sail through.
     */
    val labelled get() = routes.size == entries.size && routes.all { it != null }
    /**
     * True when the walk reached the end of every catalogue **and** every show is labelled.
     *
     * The second half is not decoration. Search narrows to one catalogue through these labels, and
     * a missing label reads as 「belongs to both」, so an index written before they existed would
     * filter as though nothing were selected and silently keep mixing the two. Worse, calling that
     * state complete stops it ever being re-walked, so the labels would never arrive. An index that
     * cannot answer 「这一页全是漫剧吗」 has not finished its job.
     */
    val complete get() = walkedToEnd && labelled
}

/**
 * On-disk form of a [CatalogueIndex].
 *
 * Only the five fields a search result needs are written. The catalogue rows also carry a full
 * `series_intro`, and keeping it would push 2400 shows past 800 KB of JSON for text the detail
 * page fetches anyway on open.
 *
 * The walk cursor is written alongside the rows, which is the whole point: an index that cannot say
 * where its walk stopped has to be thrown away and rebuilt, and on a slow box it never survives long
 * enough to be worth keeping. An index written before this field existed simply decodes with no
 * cursor, which costs one extra full walk and then writes the new shape.
 */
object CatalogueIndexDisk {
    private const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_CHARS = 1_500_000

    fun encode(entries: List<Series>, now: Long): String =
        encode(entries, emptyList(), emptyMap(), emptySet(), now)

    fun encode(
        entries: List<Series>,
        routes: List<Route?>,
        cursor: Map<Route, Int>,
        done: Set<Route>,
        now: Long
    ): String = JSONObject()
        .put("savedAt", now)
        .put("count", entries.size)
        // Per-entry route, so each filter can show its own front page after a restart.
        .put("kinds", JSONArray(entries.indices.map { position ->
            routes.getOrNull(position)?.storedValue ?: JSONObject.NULL
        }))
        .put("cursor", JSONObject().apply { cursor.forEach { (route, page) -> put(route.storedValue, page) } })
        .put("done", JSONArray(done.map { it.storedValue }))
        .put("items", JSONArray(entries.map {
            JSONObject().put("i", it.id).put("t", it.title).put("c", it.cover)
                .put("b", it.badge).put("g", it.tags)
        }))
        .toString()

    /** Returns null for anything unreadable, oversized, expired or empty - a stale index is not worth failing over. */
    fun decode(value: String, now: Long): CatalogueSnapshot? = runCatching {
        require(value.length <= MAX_CHARS)
        val o = JSONObject(value)
        require(now - o.getLong("savedAt") in 0..MAX_AGE)
        val rows = o.getJSONArray("items")
        require(rows.length() in 1..4000)
        require(rows.length() == o.getInt("count"))
        val entries = (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val row = rows.getJSONObject(index)
                Series(
                    row.getString("i").also { require(it.matches(Regex("[0-9]{1,30}"))) },
                    row.getString("t").also { require(it.isNotBlank() && it.length <= 120) },
                    row.optString("c", "").take(400),
                    "",                                     // 简介按需从详情页取，不进索引
                    row.optString("b", "").take(40),
                    row.optString("g", "").take(200)
                )
            }.getOrNull()
        }
        require(entries.isNotEmpty())
        // An index written before this field existed has no per-entry route; every entry reads
        // back as null and matches any filter, so an old index still browses.
        val kinds = o.optJSONArray("kinds")
        val routes = entries.indices.map { position ->
            kinds?.optString(position).orEmpty().takeIf { it.isNotEmpty() && it != "null" }
                ?.let { stored -> Route.fromStored(stored) }
        }
        // The route ids deliberately reuse the old per-type ones ("short", "comic"), so an index
        // written before AI dramas were folded in keeps its bookmark: the two routes it already
        // walked stay walked, and only "ai" is left to fetch. Without that, folding AI dramas in
        // would cost the user a full re-walk of 102 pages on a box that needs minutes for 68.
        val cursor = o.optJSONObject("cursor")?.let { stored ->
            Route.entries.mapNotNull { route -> stored.optInt(route.storedValue, 0).takeIf { it > 0 }?.let { route to it } }
        }.orEmpty().toMap()
        // An unknown stored value is dropped rather than rejected: a build that retired a route
        // must not make the whole index unreadable.
        val done = o.optJSONArray("done")?.let { stored ->
            (0 until stored.length()).mapNotNull { position ->
                Route.fromStored(stored.optString(position))
            }
        }.orEmpty().toSet()
        CatalogueSnapshot(entries, routes, cursor, done)
    }.getOrNull()
}
