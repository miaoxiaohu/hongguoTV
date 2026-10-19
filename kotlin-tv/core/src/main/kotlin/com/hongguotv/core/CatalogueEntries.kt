// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * The shows a walk has collected so far, each with the route it came from.
 *
 * Merging is by id, and that is the whole point of this class.
 *
 * The walk returns every show it sees as "fresh", because it has no memory of an earlier pass -
 * and it must not have one: a re-walk is how an index that was written before the routes were
 * labelled gets its labels, and a show already held still has to come back so the label can be
 * attached. Appending what the walk returns to what is already held therefore doubles the
 * library on every re-walk: 2400 shows become 4800 rows, the number on screen says 4800, and the
 * index is written with a count no reader will accept (`rows.length() in 1..4000`), so the next
 * launch finds a file it cannot read and throws the whole library away.
 *
 * Replacing the earlier copy of a show instead of appending it keeps three things true at once:
 * the count is the number of distinct shows, the stored rows stay one row per show, and a label
 * learned on the second pass replaces the missing one from the first.
 *
 * Insertion order is the walk's own order and does not change when a show is replaced, so the
 * catalogue keeps the site's ranking - which is what a tie in a search result resolves by.
 */
class CatalogueEntries(entries: List<Series> = emptyList(), routes: List<Route?> = emptyList()) {
    private val order = LinkedHashMap<String, Series>()
    private val labels = LinkedHashMap<String, Route?>()

    init {
        entries.forEachIndexed { position, series ->
            order[series.id] = series
            labels[series.id] = routes.getOrNull(position)
        }
    }

    /**
     * Adds what the walk just returned, under [route].
     *
     * A show already held is replaced rather than added again. The replacement is the one that
     * just came off the wire, so it carries the freshest title, cover and - the reason this
     * matters - the route it was found on.
     */
    fun add(fresh: List<Series>, route: Route?) {
        fresh.forEach { series ->
            order[series.id] = series
            labels[series.id] = route
        }
    }

    /** How many distinct shows are held. */
    val size get() = order.size

    /** The shows, in the order they were first seen. */
    val entries: List<Series> get() = order.values.toList()

    /** Which route each show came from, parallel to [entries]; null where never recorded. */
    val routes: List<Route?> get() = order.keys.map { labels[it] }
}
