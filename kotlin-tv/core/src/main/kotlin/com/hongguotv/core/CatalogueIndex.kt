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
    }
}

/**
 * On-disk form of a [CatalogueIndex].
 *
 * Only the five fields a search result needs are written. The catalogue rows also carry a full
 * `series_intro`, and keeping it would push 1600 shows past 800 KB of JSON for text the detail
 * page fetches anyway on open.
 */
object CatalogueIndexDisk {
    private const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_CHARS = 1_500_000

    fun encode(entries: List<Series>, now: Long): String = JSONObject()
        .put("savedAt", now)
        .put("count", entries.size)
        .put("items", JSONArray(entries.map {
            JSONObject().put("i", it.id).put("t", it.title).put("c", it.cover)
                .put("b", it.badge).put("g", it.tags)
        }))
        .toString()

    /** Returns null for anything unreadable, oversized, expired or empty - a stale index is not worth failing over. */
    fun decode(value: String, now: Long): CatalogueIndex? = runCatching {
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
        CatalogueIndex(entries)
    }.getOrNull()
}
