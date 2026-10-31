// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 晚到的弹幕怎么安置。
 *
 * 弹幕在画面上的位置是按「播放位置减内容时间」算的，所以弹幕和画面对得上。但弹幕只有数据到了
 * 才画得出来，而数据是一窗一窗从站方拉的 —— 平台给的那一窗里，**已经看过的那一段也在里面**
 * （2026-11-01 实测：问 48 秒那窗，回来的内容时间是 33~47 秒，全都已经在过去了）。
 * 那些弹幕一露面就已经飞了一段路，**整条摆在画面右边**，而不是第一个字贴在右边缘慢慢飘。
 *
 * 这里测的就是：晚到的重新锚定入场时间（从「现在」开始飞），内容时间不动；没晚到的一根手指都不动。
 */
class DanmakuAnchorTest {
    private fun at(ms: Long, text: String = "x")=Danmaku(text,ms,0)

    /**
     * 修之前的样子：数据晚到了 5 秒，弹幕就整条摆在画面中间 —— 它一出现已经在 5/8 的路程上，
     * 也就是画面偏右但很靠里的位置，看着就是凭空冒出来。
     */
    @Test fun withoutReanchoringALateOneAppearsInTheMiddle() {
        val timeline=DanmakuTimeline(listOf(at(0,"迟到的")),trackCount=4,lifetimeMs=8_000L)
        assertEquals(5_000L,timeline.visibleAt(5_000L)[0].elapsedMs)
    }

    /** 重新锚定之后：它从画面右边飞进来。 */
    @Test fun aLateOneFliesInFromTheRightInsteadOfAppearingInTheMiddle() {
        val anchored=DanmakuAnchors.reanchor(listOf(at(0,"迟到的")),nowMs=5_000L)
        val timeline=DanmakuTimeline(anchored,trackCount=4,lifetimeMs=8_000L)
        val visible=timeline.visibleAt(5_000L)
        assertEquals(1,visible.size)
        // 刚刚入场：它是从**现在**开始飞的，不是在过去的某一刻开始飞的。
        assertEquals(0L,visible[0].elapsedMs)
    }

    /**
     * 关键的一条：晚到几百毫秒的那条，一露面也得贴着画面右边缘。
     *
     * 晚到四百三十四毫秒的那些，2026-11-01 实测落在屏宽的百分之九十三 —— 整条文字都在画面里，
     * 正是用户说的「整条突然出现在右边」。锚定到「现在」之后，它才刚刚入场。
     */
    @Test fun aSlightlyLateOneAlsoStartsAtTheRightEdge() {
        val anchored=DanmakuAnchors.reanchor(listOf(at(4_566,"晚到四百多毫秒")),nowMs=5_000L)
        val timeline=DanmakuTimeline(anchored,trackCount=4,lifetimeMs=8_000L)
        assertEquals(0L,timeline.visibleAt(5_000L)[0].elapsedMs)
        // 锚定之后它比内容时间晚了，但不是「已经在半路上」：入场到现在不超过一帧。
        assertEquals(5_000L,anchored[0].enterAtMs)
    }

    /** 入场那一刻是从右边飞进来，不是凭空出现在画面里。 */
    @Test fun aReanchoredOneStartsAtTheRightEdge() {
        val anchored=DanmakuAnchors.reanchor(listOf(at(0,"迟到的")),nowMs=5_000L)
        val timeline=DanmakuTimeline(anchored,trackCount=4,lifetimeMs=8_000L)
        assertEquals(0L,timeline.visibleAt(anchored[0].enterAtMs)[0].elapsedMs)
    }

    /** 内容时间不能动 —— 动了就跟画面对不上了，那句话还是那句话在第几秒说的。 */
    @Test fun theContentTimeIsNeverTouched() {
        val anchored=DanmakuAnchors.reanchor(listOf(at(1_234,"迟到的")),nowMs=5_000L)
        assertEquals(1_234L,anchored[0].offsetMs)
    }

    /** 整批一起挪：彼此的间隔不变，它们是一条条飞进来的，不会挤成一团。 */
    @Test fun theLateOnesKeepTheirSpacing() {
        val items=listOf(at(100,"a"),at(400,"b"),at(900,"c"))
        val anchored=DanmakuAnchors.reanchor(items,nowMs=2_000L)
        // 整批从「现在」开始飞，间隔照旧。
        assertEquals(listOf(2_000L,2_300L,2_800L),anchored.map { it.enterAtMs })
        assertEquals(300L,anchored[1].enterAtMs-anchored[0].enterAtMs)
        assertEquals(500L,anchored[2].enterAtMs-anchored[1].enterAtMs)
    }

    /** 没晚到的一个都不动 —— 数据在手时它还没到点，老老实实按内容时间飞。 */
    @Test fun theOnesThatAreNotLateAreNotTouched() {
        val items=listOf(at(100,"迟到的"),at(5_000,"还没到的"))
        val anchored=DanmakuAnchors.reanchor(items,nowMs=2_000L)
        assertEquals(2_000L,anchored[0].enterAtMs)
        assertEquals(5_000L,anchored[1].enterAtMs)
        assertEquals(5_000L,anchored[1].offsetMs)
    }

    /** 刚好到点的那条不算晚到：它马上就该飞了。 */
    @Test fun theOneThatIsJustOnTimeIsNotLate() {
        val items=listOf(at(2_000,"刚好"))
        val anchored=DanmakuAnchors.reanchor(items,nowMs=2_000L)
        assertEquals(2_000L,anchored[0].enterAtMs)
    }

    /**
     * 刚刚过去的也算晚到。
     *
     * 差一点的那条要是按内容时间照飞，它一露面就带着几百毫秒的「已飞路程」—— 短的话整条都在
     * 画面右边。宁可让它晚几十毫秒出现，也要它从右边缘慢慢飘进去。
     */
    @Test fun aBarelyLateOneIsAlsoReanchored() {
        val items=listOf(at(1_999,"刚过去"))
        val anchored=DanmakuAnchors.reanchor(items,nowMs=2_000L)
        assertEquals(2_000L,anchored[0].enterAtMs)
    }

    /** 数据到得早的时候，一根手指都不用动 —— 免得把好好的也改了。 */
    @Test fun nothingHappensWhenTheDataArrivedInTime() {
        val items=listOf(at(100,"早的"),at(200,"也早"))
        val anchored=DanmakuAnchors.reanchor(items,nowMs=0L)
        assertEquals(listOf(100L,200L),anchored.map { it.enterAtMs })
    }

    /** 换集之后重来：晚到的挪过，同一条弹幕再出现一次也不能再挪一次。 */
    @Test fun theReanchoredOneKeepsItsEnterTimeWhenTheTimelineIsRebuilt() {
        val anchored=DanmakuAnchors.reanchor(listOf(at(0,"迟到的")),nowMs=5_000L)
        val again=DanmakuAnchors.reanchor(anchored,nowMs=6_000L)
        assertEquals(anchored[0].enterAtMs,again[0].enterAtMs)
    }

    /**
     * 错过太久的不能往回捞。
     *
     * 差几秒是网络慢了点；差几分钟是压根不是这一段的内容 —— 那句话早就过去了，
     * 把它的弹幕补放出来，观众看到的是一条跟眼前画面无关的东西，比没有弹幕更糟。
     */
    @Test fun theOnesThatMissedTooMuchAreNotPulledBack() {
        val items=listOf(at(0,"十分钟前的"))
        val anchored=DanmakuAnchors.reanchor(items,nowMs=600_000L)
        assertEquals(0L,anchored[0].enterAtMs)
        assertEquals(0L,anchored[0].offsetMs)
    }

    /** 一批里既有刚晚到的，也有早就不相干的：只捞前者。 */
    @Test fun onlyTheSlightlyLateOnesArePulledBack() {
        val items=listOf(at(599_000,"刚晚到的"),at(0,"很久以前的"))
        val anchored=DanmakuAnchors.reanchor(items,nowMs=600_000L)
        assertEquals(600_000L,anchored[0].enterAtMs)
        assertEquals(0L,anchored[1].enterAtMs)
    }

    /**
     * 整批挪完之后，批里每一条露面时都还贴着右边缘。
     *
     * 2026-11-01 电视上就是这个毛病：一批晚到的弹幕一露面就落在屏宽百分之八十八到一百之间，
     * 短的那些整条都在画面里。这里逐条验一遍：任何一条露面时都不能已经飞过。
     */
    @Test fun everyReanchoredOneStartsFromTheRightEdge() {
        val items=(0 until 12).map { at(33_000L+it*1_000L,"第${it}条") }
        val anchored=DanmakuAnchors.reanchor(items,nowMs=48_000L)
        val timeline=DanmakuTimeline(anchored,trackCount=8,lifetimeMs=8_000L)
        for(item in anchored) {
            val visible=timeline.visibleAt(item.enterAtMs)
            assertTrue(
                "第 ${item.text} 条露面时已经走了 ${visible.firstOrNull()?.elapsedMs} 毫秒",
                visible.any { it.danmaku==item && it.elapsedMs==0L }
            )
        }
    }
}
