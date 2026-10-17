// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * Walks the site's paged catalogues so the local search index can hold every show it carries.
 *
 * The catalogue is the one thing the site still pages properly: 34 pages x 24 rows in each named
 * route, 1600 shows, measured with zero duplicates and zero overlap between the two. That is what
 * a [CatalogueIndex] is built from, because the search page itself will only ever render ten rows.
 *
 * Pages are handed to [onPage] as they land rather than collected and returned at the end, so a
 * search issued two minutes into a build already covers most of the library. Requests are serial
 * on purpose - concurrency only buys speed and costs rate-limit exposure, and the endpoint that
 * does answer this app is already sensitive about how often it is called.
 */
class CatalogueBuilder(private val repository: ContentRepository) {
    /**
     * @param isCancelled polled before every request so a closing Activity stops the walk at once.
     * @param onPage receives the freshly-seen shows and the running total, on the calling thread.
     * @return every show collected before completion or cancellation; partial is still useful.
     */
    fun build(isCancelled: () -> Boolean = { false }, onPage: (List<Series>, collected: Int) -> Unit = { _, _ -> }): List<Series> {
        val seen = mutableSetOf<String>()
        val all = mutableListOf<Series>()
        for (type in ContentType.entries) {
            var page = 1
            while (page <= MAX_PAGES) {
                if (isCancelled()) return all
                // One bad page must not throw away the pages that already succeeded - a half index
                // still finds most shows, and the next build starts over anyway.
                val result = try { repository.home(page, type) } catch (_: Exception) { break }
                if (result.items.isEmpty()) break
                val fresh = result.items.filter { seen.add(it.id) }
                all += fresh
                onPage(fresh, all.size)
                if (!result.hasMore) break
                page++
            }
        }
        return all
    }

    companion object {
        /** 34 pages measured per catalogue; the cap is a guard against a runaway hasMore. */
        const val MAX_PAGES = 100
    }
}
