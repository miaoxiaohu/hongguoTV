// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * 翻页之后，焦点落在新页的哪一格。
 *
 * 几页的做法不一样，理由也不一样：
 *
 * - **搜索页**回到同一格：结果是一份连续的清单，用户拿两页对着看，位置跳了就没法比。这一条是
 *   2026-10-20 那次修复定的 —— 原先的算法是「按页长切片再取下标」，而切片用的页长（18）和
 *   搜索页真正的页长（30）不是一个数，第 19 张以后的下标全是错的，于是每翻一页焦点就跳回第 1 张。
 * - **别的页**从新页的第一张开始：翻页那一行（上一页 / 页码 / 下一页）画在整页的最下面，它不在
 *   网格里，所以「回到翻页前的那一格」对它没有意义 —— 那一格是旧页的最后一排，照搬到新页就是把
 *   用户丢到新页的最下面，而新页的头几行还在屏幕上面看不见。
 *   实测（2026-10-21，排行榜一页 36 张、六列六行）：焦点从左上角一路按到底，停在第六行第一列；
 *   按下「下一页」之后新页的焦点也在第六行第一列 —— 看着就像「跳到了角落里」。
 *   2026-10-20 那次修复把「同一格」的还原推广到了所有页，排行榜页从此也这样；这一页在修复之前
 *   是从新页的第一张开始的。
 *
 * 还有一条对哪一页都成立：**用户正在看的那张卡还在新页里，就让它留在原地** —— 页面自己换的时候
 * （榜单每天自己刷新一次、收藏有更新）别把它从用户眼皮底下挪走。
 *
 * 放在这里是因为它得有一个能写测试的地方：它原来散在 MainActivity 的私有逻辑里，测不到，
 * 而「新页的焦点该落在哪一格」正是那种读代码看不出错、只有站在电视前面才看得见的差别。
 */
object PageTurn {
    /** 新页里没有旧页那张卡。 */
    const val NOT_THERE=-1

    /**
     * 新页的焦点落在第几张卡上（从 0 起）。
     *
     * @param tab 翻的是哪一页（[Paging.SEARCH] 是搜索页，其余各页从新页的第一张开始）。
     * @param remembered 焦点那张卡在旧页里的序号（没有就 null）。
     * @param sameCard 焦点那张卡在新页里的序号（[NOT_THERE] 表示新页里没有它）。
     * @param newPageSize 新页有多少张卡。
     */
    fun focusCell(tab: Int, remembered: Int?, sameCard: Int, newPageSize: Int): Int {
        if(newPageSize<=0) return 0
        // 那张卡还在新页里，就让它还在原地：页面自己换的时候别把用户正在看的那张挪走。
        if(sameCard in 0 until newPageSize) return sameCard
        // 搜索页回到同一格。
        if(tab==Paging.SEARCH) {
            val cell=remembered
            if(cell!=null && cell in 0 until newPageSize) return cell
        }
        // 其余各页：新页的第一张。
        return 0
    }
}
