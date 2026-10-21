// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻页之后，焦点落在新页的哪一格。
 *
 * 每一条断言都是一次真的「焦点落错了地方」：
 * - 排行榜按了「下一页」，新页的焦点落在第六行第一列 —— 看着就像「跳到了角落里」。
 *   实测（2026-10-21）：排行榜一页 36 张、六列六行，焦点从左上角一路按到底停在第六行第一列；
 *   按下「下一页」之后新页的焦点也在第六行第一列。
 * - 搜索页每翻一页焦点就跳回第 1 张 —— 那是 2026-10-20 修掉的：原先按页长切片再取下标，
 *   切片用的页长（18）和搜索页真正的页长（30）不是一回事，第 19 张以后的下标全是错的。
 * - 榜单自己刷新一次，焦点就从用户正在看的那张卡上挪走了 —— 那张卡还在，就该留在原地。
 */
class PageTurnTest {
    /** 排行榜一页 36 张：六行六列。 */
    private val rankingPage=36
    /** 搜索页一页 30 条：五行。 */
    private val searchPage=30

    @Test fun theRankingPageStartsTheNewPageAtItsFirstCard() {
        // 用户从左上角一路按到底，焦点停在第六行第一列（第 31 张，序号 30）。按下「下一页」
        // 之后，新页的焦点不该还在那个角落里 —— 新页的头几行还在屏幕上面看不见。
        assertEquals(0,PageTurn.focusCell(Paging.RANKING,30,PageTurn.NOT_THERE,rankingPage))
    }

    @Test fun theHomePageStartsTheNewPageAtItsFirstCard() {
        assertEquals(0,PageTurn.focusCell(Paging.HOME,17,PageTurn.NOT_THERE,18))
    }

    @Test fun theLocalListsStartTheNewPageAtTheirFirstCard() {
        assertEquals(0,PageTurn.focusCell(Paging.FAVORITES,17,PageTurn.NOT_THERE,18))
        assertEquals(0,PageTurn.focusCell(Paging.HISTORY,17,PageTurn.NOT_THERE,18))
    }

    @Test fun theSearchPageKeepsTheCell() {
        // 搜索的结果是一份连续的清单，用户拿两页对着看：位置跳了就没法比。
        assertEquals(24,PageTurn.focusCell(Paging.SEARCH,24,PageTurn.NOT_THERE,searchPage))
        assertEquals(0,PageTurn.focusCell(Paging.SEARCH,0,PageTurn.NOT_THERE,searchPage))
        assertEquals(29,PageTurn.focusCell(Paging.SEARCH,29,PageTurn.NOT_THERE,searchPage))
    }

    @Test fun theSearchPageFallsBackToTheFirstCardWhenTheCellIsGone() {
        // 旧页的第 40 张，新页只有 30 张 —— 那一格在新页里不存在了。
        assertEquals(0,PageTurn.focusCell(Paging.SEARCH,40,PageTurn.NOT_THERE,searchPage))
    }

    @Test fun theCardTheUserWasLookingAtStaysWhereItIs() {
        // 榜单每天自己刷新一次、收藏有更新：用户正在看的那张卡还在，就让它留在原地，
        // 别把它从眼皮底下挪走。
        assertEquals(12,PageTurn.focusCell(Paging.RANKING,30,12,rankingPage))
        assertEquals(12,PageTurn.focusCell(Paging.SEARCH,24,12,searchPage))
    }

    @Test fun theCellIsNeverOutsideTheNewPage() {
        for(tab in listOf(Paging.HOME,Paging.SEARCH,Paging.RANKING,Paging.FAVORITES,Paging.HISTORY)) {
            for(same in listOf(PageTurn.NOT_THERE,0,5,-1,99)) {
                val cell=PageTurn.focusCell(tab,29,same,10)
                assertEquals("$tab 的落点 $cell 超出了新页的 10 张",true,cell in 0 until 10)
            }
        }
    }

    @Test fun anEmptyPageHasNoCellToFocus() {
        assertEquals(0,PageTurn.focusCell(Paging.RANKING,30,PageTurn.NOT_THERE,0))
    }
}
