package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * The key a search history is kept under.
 *
 * It is the whole entry - the word and the shelf it was searched in - and it is the same key
 * in memory and on disk. This test pins that down, because the two were believed to differ:
 * the word was said to be merged into one entry when the list was read back, and it is not -
 * equality of a [RecentSearch] covers the shelf as well, so the two entries are two entries
 * before and after a restart alike.
 */
class SearchHistoryKeyTest {
    @Test fun `the same word in two shelves is two entries, before and after a restart`() {
        var rows=emptyList<RecentSearch>()
        rows=SearchHistory.remember(rows,"总裁",ContentType.SHORT)
        rows=SearchHistory.remember(rows,"总裁",ContentType.COMIC)
        assertEquals(2,rows.size)
        assertEquals(ContentType.COMIC,rows.first().type)
        // Reading the list back does not merge the two.
        val restored=SearchHistory.decode(SearchHistory.encode(rows))
        assertEquals(rows,restored)
        assertEquals(2,restored.size)
    }

    @Test fun `the same word in the same shelf is one entry`() {
        var rows=emptyList<RecentSearch>()
        rows=SearchHistory.remember(rows,"总裁",ContentType.SHORT)
        rows=SearchHistory.remember(rows,"总裁",ContentType.SHORT)
        assertEquals(1,rows.size)
        assertEquals(rows,SearchHistory.decode(SearchHistory.encode(rows)))
    }
}
