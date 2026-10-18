// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * One page landing during a walk, carrying everything a checkpoint needs.
 *
 * @property fresh shows this page added that the walk had not already seen.
 * @property total everything collected so far, including what an earlier pass left behind.
 * @property cursor catalogue -> last page folded in, so the next pass resumes instead of restarting.
 * @property done catalogues that reached their final page and never need another request.
 */
data class WalkStep(
    val fresh: List<Series>,
    val type: ContentType,
    val total: Int,
    val cursor: Map<ContentType, Int>,
    val done: Set<ContentType>
)

/**
 * What one walk pass saw.
 *
 * The cursor is what makes this finishable on a slow box. A J1900 needs minutes for the 68 serial
 * requests, so a walk that is interrupted - by the user leaving the app, by the box running out of
 * memory, by one page timing out - must be resumable rather than restarted. Without it the index can
 * never complete: every launch begins at page one, dies in the same place, and a walk short enough
 * to be interrupted never gets far enough to be worth storing.
 *
 * @property types which catalogue each show in [shows] came from, parallel list. The two catalogues
 * are separate routes with separate ordering, and interleaving them would destroy each one's own
 * order - which is why browsing filters by kind instead of walking them in lockstep.
 * @property gaps pages that survived their retries. A hole is recorded rather than hidden, because a
 * library that silently hides shows is worse than one that admits a page was missed.
 */
data class CatalogueWalk(
    val shows: List<Series>,
    val types: List<ContentType>,
    val cursor: Map<ContentType, Int>,
    val done: Set<ContentType>,
    val gaps: List<String>
) {
    /** Every catalogue reached its end and no page was skipped. */
    val complete get() = gaps.isEmpty() && done.size == ContentType.entries.size
}

/**
 * Walks the site's paged catalogues so the local search index can hold every show it carries.
 *
 * The catalogue is the one thing the site still pages properly: 34 pages x 24 rows in each named
 * route, 1600 shows, measured with zero duplicates and zero overlap between the two. That is what a
 * [CatalogueIndex] is built from, because the search page itself will only ever render ten rows.
 *
 * Pages are handed to [onPage] as they land rather than collected and returned at the end, so a
 * search issued two minutes into a build already covers most of the library. Requests are serial
 * on purpose - concurrency only buys speed and costs rate-limit exposure, and the endpoint that
 * does answer this app is already sensitive about how often it is called.
 */
class CatalogueBuilder(
    private val repository: ContentRepository,
    /** Tries per page, including the first. Tests pass 1 so a retry test costs no wall-clock. */
    private val attempts: Int = PAGE_ATTEMPTS,
    private val retryPauseMillis: Long = RETRY_PAUSE_MILLIS
) {
    /**
     * @param resume the last page already folded in per catalogue; this pass starts at the next one.
     * @param done catalogues that already reached their end and must not be requested again.
     * @param isCancelled polled before every request so a closing Activity stops the walk at once.
     * @param onPage receives each page as it lands, with the state needed to checkpoint the walk.
     * @return the shows this pass added, plus where the next one should pick up.
     */
    fun build(
        resume: Map<ContentType, Int> = emptyMap(),
        done: Set<ContentType> = emptySet(),
        isCancelled: () -> Boolean = { false },
        onPage: (WalkStep) -> Unit = {}
    ): CatalogueWalk {
        val seen = mutableSetOf<String>()
        val collected = mutableListOf<Series>()
        val collectedTypes = mutableListOf<ContentType>()
        val cursor = LinkedHashMap(resume)
        val finished = LinkedHashSet(done)
        val gaps = mutableListOf<String>()
        var failures = 0
        var streakStart = 0
        var lastReason = ""
        for (type in ContentType.entries) {
            if (isCancelled() || type in finished) continue
            var page = (cursor[type] ?: 0) + 1
            while (page <= MAX_PAGES) {
                if (isCancelled()) break
                val result = fetch(type, page, gaps, isCancelled) { lastReason = it }
                if (result == null) {
                    // Cancelled mid-retry: leave the cursor where it is so the next pass refetches
                    // this page rather than recording a gap for something that was never tried.
                    if (isCancelled()) break
                    if (++failures >= MAX_CONSECUTIVE_FAILURES) {
                        // Three near-identical 「no network」 entries are noise; one line says it.
                        gaps.subList(streakStart, gaps.size).clear()
                        gaps += "连续 $failures 页抓取失败（$lastReason），本次走查提前结束，将从断点继续"
                        return CatalogueWalk(collected, collectedTypes, cursor, finished, gaps)
                    }
                    // A page that survived its retries is a hole, not a reason to abandon the other
                    // sixty-odd requests. Skip it, remember it, and let the walk finish.
                    cursor[type] = page
                    page++
                    continue
                }
                failures = 0
                streakStart = gaps.size
                cursor[type] = page
                page++
                val fresh = result.items.filter { seen.add(it.id) }
                collected += fresh
                repeat(fresh.size) { collectedTypes += type }
                onPage(WalkStep(fresh, type, collectedTypes.size, cursor.toMap(), finished.toSet()))
                // No rows, or the site saying this was the last page, ends that catalogue. Left
                // unfinished on MAX_PAGES, which is a runaway hasMore rather than an end.
                if (result.items.isEmpty() || !result.hasMore) { finished += type; break }
            }
            if (isCancelled()) break
        }
        return CatalogueWalk(collected, collectedTypes, cursor, finished, gaps)
    }

    /**
     * One catalogue page, retried before it is called a gap.
     *
     * A single timeout on a slow box should cost a page, not the rest of the walk: the old code
     * abandoned the catalogue outright, which on the projector capped the index at whatever fitted
     * before the first timeout - every single launch, at the same place.
     *
     * @return the page, or null when every attempt failed or the thread was interrupted.
     */
    private fun fetch(
        type: ContentType,
        page: Int,
        gaps: MutableList<String>,
        isCancelled: () -> Boolean,
        onFailure: (String) -> Unit = {}
    ): CatalogPage? {
        var failure: Exception? = null
        for (attempt in 0 until attempts.coerceAtLeast(1)) {
            if (attempt > 0 && (!pause() || isCancelled())) return null
            try { return repository.home(page, type) } catch (e: Exception) { failure = e }
        }
        val reason = failure?.message?.take(80) ?: "未知原因"
        onFailure(reason)
        gaps += "${type.categorySlug} 第 $page 页：$reason"
        return null
    }

    /** @return false when the thread was interrupted during the wait. */
    private fun pause(): Boolean = try { Thread.sleep(retryPauseMillis); true } catch (_: InterruptedException) { false }

    companion object {
        /** 34 pages measured per catalogue; the cap is a guard against a runaway hasMore. */
        const val MAX_PAGES = 100
        /** Three tries per page. Enough to ride out a WiFi blip without hammering the endpoint. */
        const val PAGE_ATTEMPTS = 3
        const val RETRY_PAUSE_MILLIS = 1_200L
        /**
         * Give up on the whole pass after this many pages in a row.
         *
         * Without it, a box with no network would burn 68 pages x 3 tries x 45s of call timeout -
         * hours of requests against an endpoint that is already sensitive to call rate, all of them
         * certain to fail.
         */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
