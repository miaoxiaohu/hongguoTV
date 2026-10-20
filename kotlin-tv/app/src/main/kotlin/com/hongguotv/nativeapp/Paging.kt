// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * 翻页的账：这一页是第几页，一共有几页，页码那行字该怎么写。
 *
 * 这三样是一件事，所以一起算、一起给。这个问题错过一次，错得很安静：首页（热门）不在那个
 * when 的任何一支里，于是它永远答「一页」，页码选择器也就永远弹「只有一页，没有可挑的」——
 * 而首页明明有 34 页，屏幕上的「上一页 / 下一页」也明明是通的（实测 2026-10-20：站方三条
 * 货架都是 800 部、24 条一页、34 页）。
 *
 * 另外两个缘故把它从 MainActivity 里搬出来：
 * - 它得有一个能写测试的地方。MainActivity 里的那一段是私有方法，测不到；而「哪一页一共
 *   有几页」正是那种读代码看不出错、只有数一遍才发现的逻辑。
 * - 页数和页码那行字原本是两个并排的 when，条件一样、写法一样，漏掉一支就是漏掉两支 ——
 *   上面的首页就是这样，两处同时漏，屏幕上写着「第 2 页」，选择器却说「只有一页」。
 */
object Paging {
    /** 首页（热门）。 */
    const val HOME=0
    /** 搜索。 */
    const val SEARCH=1
    /** 排行榜。 */
    const val RANKING=2
    /** 收藏。 */
    const val FAVORITES=3
    /** 最近观看。 */
    const val HISTORY=4
    /** 设置。 */
    const val SETTINGS=5

    /**
     * 这一页的账。
     *
     * @param tab 这一页是哪个标签页。
     * @param localItems 本机清单（收藏、最近观看、合集）里的条数；null 表示这一页不是本机清单。
     * @param pageSize 这一页画多少条。
     * @param sitePages 站方说的总页数（首页用它）。
     * @param rankingPages 榜单的总页数。
     * @param searchHits 搜索命中的条数。
     */
    fun of(
        tab: Int,
        localItems: Int?,
        pageSize: Int,
        sitePages: Int?,
        rankingPages: Int?,
        searchHits: Int?
    ): Account = when {
        // 本机清单：条数自己数。
        localItems != null -> Account(pagesOf(localItems, pageSize), null, true)
        // 榜单：站方说它有几页。
        tab == RANKING && rankingPages != null -> Account(rankingPages, null, true)
        // 首页（热门）：答案只能来自站方。它一页给 24 条、34 页，而 App 把这些条画在自己的
        // 网格里，从屏幕上的条数去数页数，数出来的是另一个数。
        tab == HOME && sitePages != null -> Account(sitePages, null, true)
        // 搜索：命中多少条，自己数。
        tab == SEARCH && (searchHits ?: 0) > 0 -> Account(pagesOf(searchHits!!, pageSize), searchHits, true)
        else -> Account(1, null, false)
    }

    /** 本机清单和搜索结果：N 条，每页 S 条。 */
    fun pagesOf(items: Int, pageSize: Int): Int = maxOf(1, (items + pageSize - 1) / pageSize)

    /**
     * 这一页的账。
     *
     * @param total 一共有几页。
     * @param hits 搜索命中的条数，只有搜索页有。
     * @param known 总页数是不是真的知道 —— 不知道的时候页码那行字只写「第 N 页」，因为写一个
     *   猜出来的分母，比不写更糟。
     */
    class Account(val total: Int, val hits: Int?, val known: Boolean) {
        /** 屏幕上那行字：「第 2 / 34 页」。 */
        fun label(page: Int): String = when {
            hits != null -> "第 $page / $total 页 · 共 $hits 部"
            known -> "第 $page / $total 页"
            else -> "第 $page 页"
        }
    }
}
