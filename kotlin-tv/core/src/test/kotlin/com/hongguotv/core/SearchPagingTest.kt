package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * The numbers a search page shows have to describe the whole library, not the slice the page kept.
 *
 * The site answers every keyword with ten rows, so the local index is the only thing that can
 * say how many shows a word hits. It used to keep at most 400 of them, and every number the
 * page showed was read off that kept slice: 「共 400 部」 for a word that hits 1042, a page
 * counter that stopped at thirteen, and a 「没有找到相关剧」 page for a search that plainly had
 * more. The shows past the cap were not hidden — they were gone.
 *
 * These run against the real [ContentRepository.search]. Every case uses page two or later on
 * purpose: the first page also asks the site, and a unit test must not need the network.
 */
class SearchPagingTest {
    /** A library of [count] shows that all match 「都市」. */
    private fun index(count: Int, route: Route? = Route.REAL_DRAMA): CatalogueIndex {
        val series = (1..count).map { Series((100000 + it).toString(), "都市剧$it") }
        return CatalogueIndex(series, series.map { route })
    }

    @Test fun theHitCountIsTheWholeLibraryNotTheSliceThePageKept() {
        val page = ContentRepository().search("都市", 2, ContentType.SHORT, index(500))
        assertEquals(500, page.totalHits)
    }

    @Test fun aPagePastTheOldCapIsNotHalfEmpty() {
        // The old cap kept 400 rows, so page fourteen started at 390 and had ten rows left.
        val page = ContentRepository().search("都市", 14, ContentType.SHORT, index(500))
        assertEquals(30, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test fun theLastPageIsTheLastOneAndThereIsNoOneAfterIt() {
        // 500 rows, thirty a page: seventeen pages, the last one holding twenty.
        val page = ContentRepository().search("都市", 17, ContentType.SHORT, index(500))
        assertEquals(20, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test fun aSearchThatMatchesNothingHasNoPages() {
        val page = ContentRepository().search("修仙", 2, ContentType.SHORT, index(500))
        assertEquals(0, page.totalHits)
        assertFalse(page.hasMore)
        assertTrue(page.items.isEmpty())
    }

    @Test fun aTypoSearchCountsTheRowsItActuallyFound() {
        // 「雾锁请迷」is one character off 「雾锁情迷」, and four characters long, so the
        // one-character fallback applies.
        val index = CatalogueIndex(listOf(Series("1", "雾锁情迷")), listOf(Route.REAL_DRAMA))
        val page = ContentRepository().search("雾锁请迷", 2, ContentType.SHORT, index)
        assertTrue(page.fuzzy)
        assertEquals(1, page.totalHits)
    }

    @Test fun theCountAndTheListAgreeOnWhichCatalogue() {
        val series = listOf(Series("1", "都市剧甲"), Series("2", "都市剧乙"), Series("3", "都市剧丙"))
        val index = CatalogueIndex(series, listOf(Route.REAL_DRAMA, Route.COMIC_DRAMA, Route.COMIC_DRAMA))
        assertEquals(1, index.count("都市", ContentType.SHORT))
        assertEquals(2, index.count("都市", ContentType.COMIC))
        assertEquals(3, index.count("都市"))
        // The count has to describe the same set the list does, or the page counter lies twice.
        assertEquals(index.count("都市", ContentType.COMIC), index.search("都市", 10, ContentType.COMIC).size)
        assertEquals(index.count("都市", ContentType.SHORT), index.search("都市", 10, ContentType.SHORT).size)
    }
}
