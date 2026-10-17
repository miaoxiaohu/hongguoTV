// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * The three video catalogues the website actually serves, under the labels it uses for them itself.
 *
 * Browsing used to go through `?tab=`, which is only a two-way legacy alias: `tab=1` mirrors
 * `/category/real-drama` page for page, and every other value — including `tab=2` and `tab=9` —
 * answers with the comic list or falls straight back to `tab=1`'s. Probing `tab=0..15` found
 * exactly two distinct lists behind that parameter, 800 and 74 shows.
 *
 * The named routes are genuinely separate catalogues — measured pairwise overlap is **zero** — and
 * hold 800 + 800 + 800 = 2400 shows. Two of them were never requested by this client at all, which
 * is why newly released shows looked unreachable: they live in `ai-drama` and `comic-drama`,
 * neither of which the old `?tab=1` browse path could ever surface.
 *
 * The site's fourth route, `/category/comic` (漫画, 74 shows), is deliberately absent. It lists a
 * `vid_list`, but those ids sit in a different numeric range (711… against 768…/769… for every
 * real video catalogue) and the signed player endpoint answers HTTP 500 for all eight shows tried.
 * Its "episodes" are comic chapters, not video, so a browse tab full of them would be 74 dead ends.
 *
 * [categorySlug] is the only thing that differs per category; the three share one parser, one paging
 * shape (`pagination.totalPages`) and one detail/player chain.
 */
enum class ContentType(val storedValue: String, val label: String, val categorySlug: String) {
    SHORT("short","真人剧","real-drama"),
    AI("ai","AI剧","ai-drama"),
    COMIC("comic","漫剧","comic-drama");

    companion object {
        /** Unknown or missing values keep the previous fallback so old backups still restore. */
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
