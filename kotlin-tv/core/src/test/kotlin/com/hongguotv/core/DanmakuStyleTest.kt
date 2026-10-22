// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕的字号和行数。
 *
 * 字号、行高、行数是互相牵着的：字大了行就高，能塞下的行就少。所以这三件事一起算，
 * 算式放在 core 里（不依赖 Android），这里就能把「选了 12 行怎么只有 7 行」这种事测出来。
 */
class DanmakuStyleTest {
    /** 一台 1080p 的电视。 */
    private val height=1080

    @Test fun `the standard size is the size the screen asks for`() {
        // 「标准」不是某个写死的字号，是「按屏高算出来的那个字号」—— 换台电视跟着变。
        assertEquals(DanmakuLayouts.baseTextSize(height),DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO).textSize,0.01f)
    }

    @Test fun `a bigger font is a bigger font`() {
        val normal=DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO)
        val big=DanmakuLayouts.resolve(height,DanmakuSize.LARGE,DanmakuRows.AUTO)
        val huge=DanmakuLayouts.resolve(height,DanmakuSize.HUGE,DanmakuRows.AUTO)
        assertTrue(big.textSize>normal.textSize)
        assertTrue(huge.textSize>big.textSize)
    }

    @Test fun `a bigger font takes fewer rows on the same screen`() {
        // 字大了行就高，能塞下的行就少 —— 这不是 bug，是屏幕就那么大。
        // 要严格变少：全一样说明字号压根没起作用（曾经就是这个 bug 溜过去的）。
        val rows=DanmakuSize.entries.map { DanmakuLayouts.resolve(height,it,DanmakuRows.AUTO).trackCount }
        assertEquals(rows.sortedDescending(),rows)
        assertTrue("字号没有起作用，行数全是 $rows",rows.zipWithNext().all { (a,b) -> a>b })
    }

    @Test fun `the automatic row count fills the upper half of the picture`() {
        // 自动行数跟没这个设置时一样：画面上半，下面留给字幕和 HUD。
        val layout=DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO)
        val band=(height*DanmakuLayouts.AUTO_BAND).toInt()
        assertEquals(band/layout.trackHeight,layout.trackCount)
        assertEquals(7,layout.trackCount)
        assertFalse(layout.clamped)
    }

    @Test fun `a chosen row count is the row count you get`() {
        listOf(3,5,8,12).forEach { wanted ->
            val layout=DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,wanted)
            assertEquals(wanted,layout.trackCount)
            assertFalse(layout.clamped)
        }
    }

    @Test fun `a row count the screen cannot hold is cut, and it says so`() {
        // 特大字号 + 12 行：画面放不下那么多行。少几行是对的，但必须记下来 ——
        // 界面上写着 12 行而屏幕上只有 7 行，用户只会以为这个设置是坏的。
        val layout=DanmakuLayouts.resolve(height,DanmakuSize.HUGE,12)
        assertTrue(layout.trackCount<12)
        assertTrue(layout.clamped)
        val maxRows=((height*DanmakuLayouts.MAX_BAND).toInt()/layout.trackHeight).coerceAtLeast(1)
        assertEquals(maxRows,layout.trackCount)
    }

    @Test fun `the rows never grow past the band the subtitles need`() {
        // 不管要多少行，弹幕最多占画面七成 —— 下面得给字幕和 HUD 留地方。
        listOf(DanmakuSize.SMALL,DanmakuSize.NORMAL,DanmakuSize.LARGE,DanmakuSize.HUGE).forEach { size ->
            val layout=DanmakuLayouts.resolve(height,size,12)
            assertTrue("$size 用了 ${layout.trackCount} 行",layout.trackCount*layout.trackHeight<=height*DanmakuLayouts.MAX_BAND)
        }
    }

    @Test fun `an unknown size or row count falls back to the defaults`() {
        assertEquals(DanmakuSize.NORMAL,DanmakuSize.fromStored(null))
        assertEquals(DanmakuSize.NORMAL,DanmakuSize.fromStored("NOT-A-SIZE"))
        assertEquals(DanmakuSize.LARGE,DanmakuSize.fromStored("LARGE"))
        assertEquals(DanmakuRows.AUTO,DanmakuRows.normalize(7))
        assertEquals(5,DanmakuRows.normalize(5))
        assertEquals("自动",DanmakuRows.label(DanmakuRows.AUTO))
        assertEquals("5 行",DanmakuRows.label(5))
    }
}
