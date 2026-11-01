// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕的横向飞行。
 *
 * 用户 2026-11-01 的话：「弹幕应该是从右边第一个字出来，左最后一个字结束」。
 * 这里测的就是这句话：飞行区间盖满整屏；起手时整条在屏外；飞行中露头的先是第一个字；
 * 收尾时留在屏上的最后是最后一个字。
 */
class DanmakuMotionTest {
    private val lifetime = 8_000L
    private val bandWidth = 1_200
    private val textWidth = 300f

    /**
     * 起手：一个字都不在屏上。
     *
     * 之前是「整条摆在右边」—— 用户说的「整条突然出现在右边」。起手就得整条在屏外。
     */
    @Test fun itStartsCompletelyOffScreen() {
        assertEquals(bandWidth.toFloat(), DanmakuMotion.xAt(0L, lifetime, bandWidth, textWidth), 0.001f)
        assertNull(DanmakuMotion.visibleTextRange(0L, lifetime, bandWidth, textWidth))
    }

    /**
     * 收尾：整条都出去了。
     *
     * 最后一个字在屏幕左缘收尾 —— 也就是文字左边缘正好落在 -textWidth。
     */
    @Test fun itEndsCompletelyOffScreen() {
        assertEquals(-textWidth, DanmakuMotion.xAt(lifetime, lifetime, bandWidth, textWidth), 0.001f)
        assertNull(DanmakuMotion.visibleTextRange(lifetime, lifetime, bandWidth, textWidth))
    }

    /** 只往左飞，不往回退。 */
    @Test fun itOnlyMovesLeft() {
        var last = Float.MAX_VALUE
        for (elapsed in 0L..lifetime step 50L) {
            val x = DanmakuMotion.xAt(elapsed, lifetime, bandWidth, textWidth)
            assertTrue("在 $elapsed 毫秒时往右退了", x <= last)
            last = x
        }
    }

    /**
     * 用户那句话的单测版本：**进场时露头的一定是第一个字，出场时留在屏上的最后是最后一个字**。
     *
     * 文字自身坐标里：0 是第一个字的左边缘，textWidth 是最后一个字的右边缘。
     * 所以「露头的是第一个字」= 可见区间的起点是 0；「留在屏上的最后是最后一个字」= 终点是 textWidth。
     * 整个飞行过程里，这两条至少得占一条。
     */
    @Test fun theFirstCharLeadsAndTheLastCharEnds() {
        var sawEntry = false
        var sawExit = false
        for (elapsed in 0L..lifetime step 25L) {
            val range = DanmakuMotion.visibleTextRange(elapsed, lifetime, bandWidth, textWidth) ?: continue
            val startsAtFirstChar = range.start == 0f
            val endsAtLastChar = range.endInclusive == textWidth
            assertTrue(
                "在 $elapsed 毫秒时露头的不是第一个字、留在屏上的也不是最后一个字：$range",
                startsAtFirstChar || endsAtLastChar
            )
            if (!endsAtLastChar) sawEntry = true
            if (!startsAtFirstChar) sawExit = true
        }
        // 两头都得真的发生过：先是第一个字露头，最后才是最后一个字收尾。
        assertTrue("没见到第一个字从右缘露头", sawEntry)
        assertTrue("没见到最后一个字在左缘收尾", sawExit)
    }

    /**
     * 飞行区间真的盖满整屏。
     *
     * 走到文字左边缘落在屏幕左缘那一刻，整条文字都在屏里，可见区间是 0 到 textWidth。
     */
    @Test fun theTravelCoversTheWholeScreen() {
        // lifetime * bandWidth / (bandWidth + textWidth)：让 x 正好落在 0 的那一刻。
        val elapsed = lifetime * bandWidth / (bandWidth + textWidth.toInt())
        assertEquals(0f, DanmakuMotion.xAt(elapsed, lifetime, bandWidth, textWidth), 0.001f)
        val range = DanmakuMotion.visibleTextRange(elapsed, lifetime, bandWidth, textWidth)
        assertNotNull(range)
        assertEquals(0f, range!!.start, 0.001f)
        assertEquals(textWidth, range.endInclusive, 0.001f)
    }

    /** 飞到中间：整条文字都在屏里，看得见的就是全部。 */
    @Test fun inTheMiddleTheWholeTextIsVisible() {
        val elapsed = lifetime / 2
        val range = DanmakuMotion.visibleTextRange(elapsed, lifetime, bandWidth, textWidth)
        assertNotNull(range)
        assertEquals(0f, range!!.start, 0.001f)
        assertEquals(textWidth, range.endInclusive, 0.001f)
    }

    /** 刚进场：只有头几个字在屏上，右边一截还在屏外。 */
    @Test fun whileEnteringOnlyTheFirstCharsAreVisible() {
        val elapsed = lifetime / 10
        val range = DanmakuMotion.visibleTextRange(elapsed, lifetime, bandWidth, textWidth)
        assertNotNull(range)
        assertEquals(0f, range!!.start, 0.001f)
        // 露头的是第一个字，右边一截还没进来。
        assertTrue("进场时露出得太多了", range.endInclusive < textWidth)
    }

    /** 快出去了：右边一截已经在屏外，留在屏上的最后是最后一个字。 */
    @Test fun whileLeavingTheLastCharIsTheLastOneLeft() {
        val elapsed = lifetime * 9 / 10
        val range = DanmakuMotion.visibleTextRange(elapsed, lifetime, bandWidth, textWidth)
        assertNotNull(range)
        assertEquals(textWidth, range!!.endInclusive, 0.001f)
        // 左边一截已经在屏外。
        assertTrue("出场时左边还留着太多", range.start > 0f)
    }

    /** 零时长、零宽度、空文字：都不能除零、不能崩。 */
    @Test fun degenerateInputsAreSafe() {
        assertEquals(bandWidth.toFloat(), DanmakuMotion.xAt(0L, 0L, bandWidth, textWidth), 0.001f)
        assertEquals(0f, DanmakuMotion.xAt(0L, lifetime, 0, textWidth), 0.001f)
        assertNull(DanmakuMotion.visibleTextRange(0L, 0L, bandWidth, textWidth))
        assertNull(DanmakuMotion.visibleTextRange(0L, lifetime, 0, textWidth))
        assertNull(DanmakuMotion.visibleTextRange(0L, lifetime, bandWidth, 0f))
    }
}
