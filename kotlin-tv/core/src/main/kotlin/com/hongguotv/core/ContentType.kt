// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * The catalogues the website serves that this client browses, under the labels the site uses itself.
 *
 * Browsing used to go through `?tab=`, which is only a two-way legacy alias: `tab=1` mirrors
 * `/category/real-drama` page for page, and every other value — including `tab=2` and `tab=9` —
 * answers with the comic list or falls straight back to `tab=1`'s. Probing `tab=0..15` found
 * exactly two distinct lists behind that parameter, 800 and 74 shows. The named routes are real
 * separate catalogues with zero measured pairwise overlap, which is what made new shows reachable
 * at all: they live in `comic-drama`, which `?tab=1` could never surface.
 *
 * `ai-drama` (800 shows) exists and is equally playable — it was offered from 0.9.14 — but it was
 * dropped in 0.9.15 because browsing it did not pay for itself. Nothing about the route changed;
 * [RETIRED_STORED_VALUES] is what keeps an older library from being misread as corrupt.
 *
 * The site's fourth route, `/category/comic` (漫画, 74 shows), is never browsable. It does list a
 * `vid_list`, but those ids sit in a different numeric range (711… against 768…/769… for every
 * real video catalogue) and the signed player endpoint answers HTTP 500 for all eight shows tried.
 * Its "episodes" are comic chapters, not video, so a tab full of them would be dead ends.
 *
 * [categorySlug] is the only thing that differs per catalogue; they share one parser, one paging
 * shape (`pagination.totalPages`) and one detail/player chain.
 */
enum class ContentType(val storedValue: String, val label: String, val categorySlug: String) {
    SHORT("short","真人剧","real-drama"),
    COMIC("comic","漫剧","comic-drama");

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
         */
        fun fromStored(value: String?, fallback: ContentType = COMIC): ContentType =
            entries.firstOrNull { it.storedValue==value } ?: fallback
    }
}
