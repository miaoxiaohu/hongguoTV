// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕设置面板上该有哪些行、每行现在是什么值、可以选什么。
 *
 * 面板是电视上唯一改弹幕的地方 —— 改错了用户在屏幕上只能看出「不对」，说不上哪里不对。
 * 所以这里把「面板上长什么样」钉死，不必把电视搬过来才能验。
 */
class DanmakuSettingsTest {
    @Test fun theSummaryReadsAsOneLine() {
        assertEquals("开 · 标准 · 自动 · 中 · 半透明",
            DanmakuSettings.summary(true,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF))
    }

    @Test fun aClosedDanmakuShowsUpInTheSummary() {
        assertEquals("关 · 标准 · 自动 · 中 · 半透明",
            DanmakuSettings.summary(false,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF))
    }

    @Test fun theSummaryFollowsEveryKnob() {
        assertEquals("开 · 特大 · 8 行 · 多 · 不透明",
            DanmakuSettings.summary(true,DanmakuSize.HUGE,8,DanmakuDensity.HIGH,DanmakuAlpha.SOLID))
    }

    @Test fun thePanelHasFiveRowsInOrder() {
        val rows=DanmakuSettings.rows(true,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF)
        assertEquals(listOf("弹幕开关","弹幕大小","弹幕行数","弹幕数量","弹幕透明度"),rows.map { it.title })
    }

    @Test fun everyRowShowsItsCurrentValue() {
        val rows=DanmakuSettings.rows(false,DanmakuSize.SMALL,5,DanmakuDensity.LOW,DanmakuAlpha.LIGHT)
        assertEquals(listOf("关闭","小","5 行","少","淡"),rows.map { it.value })
    }

    @Test fun theToggleOffersOnAndOff() {
        assertEquals(listOf("开启","关闭"),DanmakuSettings.toggleOptions)
    }

    @Test fun theSizeOptionShowsTheScale() {
        assertEquals("标准 · 100%",DanmakuSettings.sizeOptions[DanmakuSize.NORMAL.ordinal])
        assertEquals("小 · 75%",DanmakuSettings.sizeOptions[DanmakuSize.SMALL.ordinal])
    }

    @Test fun theOptionOrderMatchesTheEnums() {
        // 面板把「第几个候选」原样交给调用方，调用方再按下标取枚举 —— 两者必须对齐，
        // 否则在面板上选了「特大」，落进枚举里的却是「大」。
        DanmakuSize.entries.forEachIndexed { index,size ->
            assertTrue(DanmakuSettings.sizeOptions[index].startsWith(size.label))
        }
        DanmakuDensity.entries.forEachIndexed { index,density ->
            assertTrue(DanmakuSettings.densityOptions[index].startsWith(density.label))
            assertTrue(DanmakuSettings.densityOptions[index].contains("同屏 ${density.maxOnScreen} 条"))
        }
        DanmakuAlpha.entries.forEachIndexed { index,alpha ->
            assertEquals(alpha.label,DanmakuSettings.alphaOptions[index])
        }
        DanmakuRows.options.forEachIndexed { index,count ->
            assertEquals(DanmakuRows.label(count),DanmakuSettings.rowOptions[index])
        }
    }
}
