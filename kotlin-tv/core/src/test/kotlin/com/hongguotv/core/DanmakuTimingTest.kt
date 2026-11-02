// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定像素速度。
 *
 * 今天是「所有弹幕一律 8 秒」：20 个字的长句和 4 个字的短句飞得一样快，长句根本读不完。
 * 改成固定像素速度后速度对所有弹幕一致，字越多飘的时间自然越长。
 */
class DanmakuTimingTest {
    private val band=1280

    /** 字越多，飘得越久。 */
    @Test fun aWiderCommentTakesLongerToCross() {
        val short=DanmakuTiming.lifetimeMs(100f,band)
        val long=DanmakuTiming.lifetimeMs(520f,band)
        assertTrue("宽弹幕必须飘得更久",long>short)
    }

    /**
     * 时长与宽度是线性关系：除掉「屏宽」那一项之后必须相等（同一个速度 v）。
     */
    @Test fun theTimeFollowsTheWidthLinearly() {
        val a=DanmakuTiming.lifetimeMs(100f,band)/(1f+100f/band)
        val b=DanmakuTiming.lifetimeMs(520f,band)/(1f+520f/band)
        assertEquals(a,b,1f)
    }

    /**
     * 一屏宽的字要飘 15 秒（基准 7.5 秒 + 文字本身占的那一屏）。
     *
     * 别写成 7.5 秒 —— 「一屏宽」是文字的宽度，飞行距离是「屏宽 + 文字宽」= 两屏。
     */
    @Test fun aScreenWideCommentTakesTwiceTheBaseTime() {
        assertEquals(15_000L,DanmakuTiming.lifetimeMs(band.toFloat(),band))
    }

    /**
     * 尾巴清场：入场后多久，这条弹幕的尾巴离开屏幕右缘。
     *
     * 算式是「基准时间 × 宽/屏宽」：100px 宽的弹幕 586 毫秒就清场，
     * 520px 宽的要 3047 毫秒 —— 长句在自己那条轨道上占得久。
     */
    @Test fun theTailClearsInProportionToTheWidth() {
        assertEquals(586L,DanmakuTiming.tailClearMs(100f,band))
        assertEquals(3_047L,DanmakuTiming.tailClearMs(520f,band))
    }

    /** 尾巴清场一定早于整条走完 —— 否则这条轨道就白占了。 */
    @Test fun theTailAlwaysClearsBeforeTheCommentIsGone() {
        for(w in listOf(50f,100f,300f,520f,900f)) {
            assertTrue("宽 $w 的弹幕",DanmakuTiming.tailClearMs(w,band)<DanmakuTiming.lifetimeMs(w,band))
        }
    }

    /**
     * 量不到宽度时按「普通弹幕约占屏宽 8%」估。
     *
     * core 里没有 `Paint`，量不出宽度 —— 探针和现有单测就走这条退路，
     * 不能让它们的行为跟真机差太远。
     */
    @Test fun anUnknownWidthIsEstimatedAsAnOrdinaryComment() {
        val estimated=DanmakuTiming.DEFAULT_WIDTH_RATIO*band
        assertEquals(DanmakuTiming.lifetimeMs(estimated,band),DanmakuTiming.lifetimeMs(0f,band))
        assertEquals(DanmakuTiming.tailClearMs(estimated,band),DanmakuTiming.tailClearMs(0f,band))
    }

    /** 屏宽还没定下来时（0）只能退回基准值，不能除零。 */
    @Test fun withoutAScreenWidthItFallsBackToTheBaseTime() {
        assertEquals(7_500L,DanmakuTiming.lifetimeMs(100f,0))
        assertEquals(600L,DanmakuTiming.tailClearMs(100f,0))
    }
}
