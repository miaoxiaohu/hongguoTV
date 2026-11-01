// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕设置面板上该有哪些行、每行现在是什么值、可以选什么。
 *
 * 面板是电视上唯一改弹幕的地方 —— 改错了用户在屏幕上只能看出「不对」，说不上哪里不对。
 * 所以这里把「面板上该长什么样」钉死，不必把电视搬过来才能验。
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

    @Test fun everySizeScaleIsShownExactly() {
        // 百分比是 (scale*100).toInt() —— 截断，不是四舍五入。单精度浮点上 1.05f*100=104.99999，
        // 截断就是 104。所以这里把四档完整串钉死：新增档位时若 scale*100 不精确落在整数上，
        // 这条测试就会失败，提示该换 scale 而不是接受掉一档的百分比。
        assertEquals(listOf("小 · 75%","标准 · 100%","大 · 130%","特大 · 170%"),DanmakuSettings.sizeOptions)
    }

    @Test fun everyRowOffersItsOwnCandidateList() {
        // 这一行钉死「哪一行配哪一组候选」：把 densityOptions 和 alphaOptions 写反，
        // 面板上「弹幕数量」就会列出透明度、「弹幕透明度」反而列出条数 —— 而光看各组自己的内容是发现不了的。
        val rows=DanmakuSettings.rows(true,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF)
        assertEquals(listOf(
            DanmakuSettings.toggleOptions,
            DanmakuSettings.sizeOptions,
            DanmakuSettings.rowOptions,
            DanmakuSettings.densityOptions,
            DanmakuSettings.alphaOptions),rows.map { it.options })
    }

    @Test fun everyRowKnowsWhichOptionIsSelected() {
        // 面板要标「当前是哪个候选」。短标签（「标准」）在候选（「标准 · 100%」）里并不是原样出现的，
        // 面板自己去字符串匹配会找不到，于是一个候选项都没被标上 —— 所以 core 把下标算好给它。
        val rows=DanmakuSettings.rows(false,DanmakuSize.SMALL,5,DanmakuDensity.LOW,DanmakuAlpha.LIGHT)
        assertEquals(1,rows[0].selectedIndex)   // 关闭
        assertEquals(0,rows[1].selectedIndex)   // 小
        assertEquals(2,rows[2].selectedIndex)   // 5 行
        assertEquals(0,rows[3].selectedIndex)   // 少
        assertEquals(2,rows[4].selectedIndex)   // 淡
        // 下标必须就是各枚举里的位置 —— 面板按下标回取枚举，两者必须对齐。
        assertEquals(DanmakuSize.entries.indexOf(DanmakuSize.SMALL),rows[1].selectedIndex)
        assertEquals(DanmakuRows.options.indexOf(5),rows[2].selectedIndex)
        assertEquals(DanmakuDensity.entries.indexOf(DanmakuDensity.LOW),rows[3].selectedIndex)
        assertEquals(DanmakuAlpha.entries.indexOf(DanmakuAlpha.LIGHT),rows[4].selectedIndex)
    }

    @Test fun theSelectedOptionAlwaysMatchesTheValue() {
        // 不变量：每一行的 options[selectedIndex] 必须存在，且跟该行语义对得上。
        // 开关行的候选就是短标签本身（「开启」/「关闭」），所以要**相等**；
        // 四个样式行的候选是「短标签 · 补充说明」（如「标准 · 100%」），所以要以短标签**开头**。
        val rows=DanmakuSettings.rows(true,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF)
        rows.forEach { row ->
            assertTrue("越界:${row.title}",row.selectedIndex in row.options.indices)
        }
        assertEquals("开关行候选应等于当前值",rows[0].value,rows[0].options[rows[0].selectedIndex])
        rows.drop(1).forEach { row ->
            assertTrue("候选应与当前值对得上:${row.title}",row.options[row.selectedIndex].startsWith(row.value))
        }
        val styled=DanmakuSettings.rows(false,DanmakuSize.SMALL,5,DanmakuDensity.LOW,DanmakuAlpha.LIGHT)
        assertEquals("关闭",styled[0].options[styled[0].selectedIndex])
        styled.drop(1).forEach { row ->
            assertTrue("候选应与当前值对得上:${row.title}",row.options[row.selectedIndex].startsWith(row.value))
        }
    }

    @Test fun anImpossibleRowCountFallsBackToAuto() {
        // 「7 行」不在候选表里（自动/3/5/8/12）。core 兜住这条不变量：当前值一定在自己的候选表里，
        // 否则面板上显示着一个选不回去的档位。
        val rows=DanmakuSettings.rows(true,DanmakuSize.NORMAL,7,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF)
        assertEquals("自动",rows[2].value)
        assertEquals(0,rows[2].selectedIndex)
        assertEquals("自动",DanmakuSettings.summary(true,DanmakuSize.NORMAL,7,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF).split(" · ")[2])
    }
}
