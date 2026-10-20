package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * A show with no episodes is not an update.
 *
 * The count used to be checked with `require(count>0)`, which throws an IllegalArgumentException
 * - and the callers are on the main thread, so one show with an empty episode list took the
 * whole check down with it. A count of zero is now refused, and the last known count stands.
 */
class FavoriteCountTest {
    private fun library()=LibraryIndex(BackupData(listOf(Series("1","剧 1")),emptyList(),emptySet(),emptyList(),BackupSettings()))

    @Test fun `a count of zero is refused and the last known state stands`() {
        val first=FavoriteUpdate.first(80,100)
        assertSame(first,first.observe(0,200))
        assertSame(first,first.observe(-3,200))
        assertEquals(2,first.observe(82,200).added)
        // A baseline for a show with no episodes is empty, not an exception.
        assertEquals(0,FavoriteUpdate.first(0,100).added)
    }

    @Test fun `the library keeps the count it had when the show answers with no episodes`() {
        val state=library()
        assertFalse(state.observeFavorite("1",0,10,false))
        assertNull(state.favoriteUpdate("1"))
        assertTrue(state.observeFavorite("1",80,10,false))
        assertEquals(80,state.favoriteUpdate("1")!!.total)
        // An empty episode list does not overwrite what is known.
        assertFalse(state.observeFavorite("1",0,20,false))
        assertEquals(80,state.favoriteUpdate("1")!!.total)
        assertEquals(0,state.updatedFavorites())
    }
}
