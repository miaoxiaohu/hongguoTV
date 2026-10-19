// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * The two shelves the user picks from, and the three routes the site serves them from.
 *
 * Browsing used to go through `?tab=`, which is only a two-way legacy alias: `tab=1` mirrors
 * `/category/real-drama` page for page, and every other value — including `tab=2` and `tab=9` —
 * answers with the comic list or falls straight back to `tab=1`'s. Probing `tab=0..15` found
 * exactly two distinct lists behind that parameter, 800 and 74 shows. The named routes are real
 * separate catalogues with zero measured pairwise overlap, which is what made new shows reachable
 * at all: they live in `comic-drama`, which `?tab=1` could never surface.
 *
 * A **route** is one of the site's own shelves. A **type** is what the user picks, and one type can
 * cover several routes: `ai-drama` is 800 more 漫剧 rather than a third shelf, so it is folded into
 * [ContentType.COMIC] instead of being offered (or dropped) on its own. It was offered from 0.9.14
 * and dropped in 0.9.15 because browsing it did not pay for itself; as part of 漫剧 it costs one
 * more walk and brings 800 more searchable shows.
 *
 * The site's fourth route, `/category/comic` (漫画, 74 shows), is never browsable. It does list a
 * `vid_list`, but those ids sit in a different numeric range (711… against 768…/769… for every
 * real video catalogue) and the signed player endpoint answers HTTP 500 for all eight shows tried.
 * Its "episodes" are comic chapters, not video, so a tab full of them would be dead ends.
 *
 * [Route.categorySlug] is the only thing that differs per route; they share one parser, one paging
 * shape (`pagination.totalPages`) and one detail/player chain.
 */
enum class ContentType(val storedValue: String, val label: String) {
    SHORT("short","真人剧"),
    COMIC("comic","漫剧");

    /** The routes this type answers for, in the order the site lists them. */
    val routes: List<Route> get() = Route.entries.filter { it.type == this }

    companion object {
        /** storedValue strings written by builds that offered a category this one does not. */
        val RETIRED_STORED_VALUES = setOf("ai")

        /**
         * Unknown values resolve to [fallback] rather than failing.
         *
         * This used to be a hard lookup that threw 「内容类型无效」 when restoring a backup, so a
         * library saved while a now-removed category was selected looked like a corrupt file and the
         * whole import was refused. A stale preference is not corruption — the rest of the backup is
         * still validated — so the type degrades and the favorites, history and progress come back.
         *
         * "ai" lands here, and it means 漫剧 now: that value used to be a separate AI category, and
         * those shows are part of 漫剧 today, so falling back to 漫剧 is what the user meant.
         */
        fun fromStored(value: String?, fallback: ContentType = COMIC): ContentType =
            entries.firstOrNull { it.storedValue==value } ?: fallback
    }
}

/**
 * One shelf the site serves, and the type that answers for it.
 *
 * The id namespace is the route's, and it deliberately reuses the ids the old per-type cursor used
 * ("short", "comic") so an index written before AI dramas were folded in keeps its bookmark: the
 * two routes it already walked stay walked, and only `ai-drama` is left to fetch.
 */
enum class Route(val storedValue: String, val label: String, val categorySlug: String, val type: ContentType) {
    REAL_DRAMA("short","真人剧","real-drama",ContentType.SHORT),
    COMIC_DRAMA("comic","漫剧","comic-drama",ContentType.COMIC),
    AI_DRAMA("ai","AI剧","ai-drama",ContentType.COMIC);

    companion object {
        /** The route a stored id refers to, or null when the id names a route this build dropped. */
        fun fromStored(value: String?): Route? = entries.firstOrNull { it.storedValue==value }
    }
}
