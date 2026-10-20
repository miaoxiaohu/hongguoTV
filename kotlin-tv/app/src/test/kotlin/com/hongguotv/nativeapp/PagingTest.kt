// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻页的账。
 *
 * 这里的每一条都是一次真的翻车，或者一次差点翻的车：
 * - 首页（热门）曾经不在任何一支里，于是它永远答「一页」，页码选择器永远弹「只有一页，
 *   没有可挑的」，而首页明明有 34 页，屏幕上的「上一页 / 下一页」也明明是通的。
 * - 站方没说话的时候不能编一个分母出来。
 * - 本机清单和搜索结果的分母是自己数的，榜单和首页的分母是站方给的 —— 混起来就会数错。
 */
class PagingTest {
    /** 站方给首页的页数（实测 2026-10-20：三条货架都是 800 部、24 条一页、34 页）。 */
    private val sitePages=34

    @Test fun theHomePageKnowsItHasMoreThanOnePage() {
        val account=Paging.of(Paging.HOME, localItems=null, pageSize=18, sitePages=sitePages, rankingPages=null, searchHits=null)
        assertEquals(34,account.total)
        assertEquals("第 2 / 34 页",account.label(2))
    }

    @Test fun theHomePageWithoutTheSitesAnswerCountsAsOnePage() {
        // 站方没说话的时候不能编一个分母出来：宁可写「第 N 页」。
        val account=Paging.of(Paging.HOME, localItems=null, pageSize=18, sitePages=null, rankingPages=null, searchHits=null)
        assertEquals(1,account.total)
        assertEquals("第 2 页",account.label(2))
    }

    @Test fun theRankingPageUsesTheBoardsOwnPageCount() {
        val account=Paging.of(Paging.RANKING, localItems=null, pageSize=36, sitePages=null, rankingPages=3, searchHits=null)
        assertEquals(3,account.total)
        assertEquals("第 1 / 3 页",account.label(1))
    }

    @Test fun theSearchPageCountsTheHitsItGot() {
        val account=Paging.of(Paging.SEARCH, localItems=null, pageSize=30, sitePages=null, rankingPages=null, searchHits=1042)
        assertEquals(35,account.total)
        assertEquals("第 1 / 35 页 · 共 1042 部",account.label(1))
    }

    @Test fun aSearchThatFoundNothingIsOnePage() {
        val account=Paging.of(Paging.SEARCH, localItems=null, pageSize=30, sitePages=null, rankingPages=null, searchHits=0)
        assertEquals(1,account.total)
        assertEquals("第 1 页",account.label(1))
    }

    @Test fun aLocalListCountsItsOwnItems() {
        // 收藏 20 条、一页 18 条 -> 两页。
        val account=Paging.of(Paging.FAVORITES, localItems=20, pageSize=18, sitePages=null, rankingPages=null, searchHits=null)
        assertEquals(2,account.total)
        assertEquals("第 1 / 2 页",account.label(1))
    }

    @Test fun anEmptyLocalListIsStillOnePage() {
        val account=Paging.of(Paging.HISTORY, localItems=0, pageSize=18, sitePages=null, rankingPages=null, searchHits=null)
        assertEquals(1,account.total)
        assertEquals("第 1 / 1 页",account.label(1))
    }

    @Test fun theHotCollectionCountsTheRowsItHolds() {
        // 「热门发现」合集拿的是首页那一批（站方一页 24 条），App 一页画 18 条 -> 两页。
        val account=Paging.of(Paging.HOME, localItems=24, pageSize=18, sitePages=sitePages, rankingPages=null, searchHits=null)
        assertEquals(2,account.total)
    }
}
