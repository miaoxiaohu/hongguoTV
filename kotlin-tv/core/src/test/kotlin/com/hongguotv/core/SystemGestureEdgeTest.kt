package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class SystemGestureEdgeTest {
    /** 全面屏手势导航的典型值：左右各 16dp 手势区，窗口 568dp 宽。 */
    private val edge=SystemGestureEdge(16f,16f)

    @Test fun 左边缘起手命中系统手势区() {
        assertTrue(edge.blocks(0f,568f))
        assertTrue(edge.blocks(8f,568f))
    }

    @Test fun 右边缘起手命中系统手势区() {
        assertTrue(edge.blocks(568f,568f))
        assertTrue(edge.blocks(560f,568f))
    }

    @Test fun 屏幕中间起手不命中() {
        assertFalse(edge.blocks(284f,568f))
        assertFalse(edge.blocks(100f,568f))
        assertFalse(edge.blocks(450f,568f))
    }

    @Test fun 没有手势区时一律不命中() {
        val none=SystemGestureEdge.NONE
        assertFalse(none.blocks(0f,568f))
        assertFalse(none.blocks(568f,568f))
        assertFalse(none.blocks(284f,568f))
    }

    @Test fun 负数手势区按没有处理() {
        val bad=SystemGestureEdge(-10f,-10f)
        assertFalse(bad.blocks(0f,568f))
        assertFalse(bad.blocks(568f,568f))
    }

    @Test fun 边界点不算命中() {
        // 左侧手势区是 [0,16)，右侧是 (552,568]。
        assertFalse(edge.blocks(16f,568f))
        assertFalse(edge.blocks(552f,568f))
        assertTrue(edge.blocks(552.5f,568f))
    }

    @Test fun 宽度未知时不拦() {
        assertFalse(edge.blocks(0f,0f))
        assertFalse(edge.blocks(0f,-10f))
    }

    @Test fun 手势区过宽时最多让出一半屏() {
        val wide=SystemGestureEdge(1000f,1000f)
        // 左右各占一半，中间那一线不命中。
        assertTrue(wide.blocks(100f,568f))
        assertTrue(wide.blocks(468f,568f))
        assertFalse(wide.blocks(284f,568f))
    }
}
