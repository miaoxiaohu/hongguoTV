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
 * What the site *does* page is the catalogue: 34 pages x 24 rows in each of the two named
 * catalogues, 1600 shows with zero overlap between them. [CatalogueBuilder] walks those, and this
 * class searches the result locally.
 *
 * Matching is ranked rather than boolean, because a flat "contains" loses the show the user
 * actually typed: 「谁让这个文盲修仙的」 must come before 「谁让这个文盲修仙的第三季」's neighbours.
 */
class CatalogueIndex(entries: List<Series>) {
    /**
     * Entries are kept in catalogue order so ties resolve to the site's own ranking.
     *
     * Normalised titles and tags are computed once here: a search sweeps all 1600 entries, and
     * re-running two regex replaces over each of them per keystroke would be the slowest part of a
     * keystroke.
     */
    private val ordered: List<Entry> = entries.distinctBy { it.id }.map {
        Entry(it, normalizeForSearch(it.title), normalizeForSearch(it.tags))
    }

    private class Entry(val series: Series, val title: String, val tags: String)

    /**
     * Ranked matches for [keyword], best first, capped at [limit].
     *
     * Blank input matches nothing rather than everything; [ContentRepository.mentions] relies on
     * the same rule, and an index that returned the whole library for a stray space would defeat
     * the 「站内没有这部剧」 notice entirely.
     */
    fun search(keyword: String, limit: Int = DEFAULT_LIMIT): List<Series> {
        val needle = normalizeForSearch(keyword)
        if (needle.isEmpty() || limit <= 0) return emptyList()
        return ordered.asSequence()
            .mapIndexedNotNull { order, entry -> rank(entry, needle)?.let { Ranked(entry.series, it, order) } }
            .sortedWith(compareBy({ it.rank }, { it.series.title.length }, { it.order }))
            .take(limit)
            .map { it.series }
            .toList()
    }

    /** How many entries match, ignoring [CatalogueIndex.search]'s cap. */
    fun count(keyword: String): Int {
        val needle = normalizeForSearch(keyword)
        if (needle.isEmpty()) return 0
        return ordered.count { rank(it, needle) != null }
    }

    /** The number of shows this index holds, for the settings line and the build progress. */
    val size get() = ordered.size

    /**
     * The first [limit] shows in catalogue order — the site's own front page order.
     *
     * Used for the search tab's empty state, so a first-run user with no history and no query
     * lands on browsable rows instead of a bare input box.
     */
    fun newest(limit: Int): List<Series> = ordered.take(limit.coerceAtLeast(0)).map { it.series }

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
    fun searchAllowingTypo(keyword: String, limit: Int = DEFAULT_LIMIT): Pair<List<Series>, Boolean> {
        val literal = search(keyword, limit)
        if (literal.isNotEmpty()) return literal to false
        val needle = normalizeForSearch(keyword)
        if (needle.length < TYPO_FLOOR) return emptyList<Series>() to false
        val relaxed = ordered.asSequence()
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
     * of the matrix. A 1600-show sweep is a couple of milliseconds, and it runs only when the
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
 * @property cursor catalogue -> last page already folded in.
 * @property done catalogues that reached their final page.
 */
data class CatalogueSnapshot(
    val entries: List<Series>,
    val cursor: Map<ContentType, Int>,
    val done: Set<ContentType>
) {
    val index: CatalogueIndex by lazy { CatalogueIndex(entries) }
    val size get() = entries.size
    /** True when the walk reached the end of every catalogue. */
    val complete get() = done.size == ContentType.entries.size
}

/**
 * On-disk form of a [CatalogueIndex].
 *
 * Only the five fields a search result needs are written. The catalogue rows also carry a full
 * `series_intro`, and keeping it would push 1600 shows past 800 KB of JSON for text the detail
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
        encode(entries, emptyMap(), emptySet(), now)

    fun encode(entries: List<Series>, cursor: Map<ContentType, Int>, done: Set<ContentType>, now: Long): String = JSONObject()
        .put("savedAt", now)
        .put("count", entries.size)
        .put("cursor", JSONObject().apply { cursor.forEach { (type, page) -> put(type.storedValue, page) } })
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
        val cursor = o.optJSONObject("cursor")?.let { stored ->
            ContentType.entries.mapNotNull { type -> stored.optInt(type.storedValue, 0).takeIf { it > 0 }?.let { type to it } }
        }.orEmpty().toMap()
        // An unknown stored value is dropped rather than rejected: a build that retired a category
        // must not make the whole index unreadable.
        val done = o.optJSONArray("done")?.let { stored ->
            (0 until stored.length()).mapNotNull { position ->
                ContentType.entries.firstOrNull { type -> type.storedValue == stored.optString(position) }
            }
        }.orEmpty().toSet()
        CatalogueSnapshot(entries, cursor, done)
    }.getOrNull()
}
