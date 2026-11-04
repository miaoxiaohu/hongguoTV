// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 系统手势区（屏幕左右边缘）的避让判定。
 *
 * 全面屏手势导航的设备上，从屏幕左右边缘起手的横滑会被**两件事同时**响应：
 * App 自己的横滑拖进度，和系统的返回手势。用户看到的是「进度拖了一半，页面又退了」。
 *
 * 这里只回答一个问题：**这一次按下是不是起手在系统手势区里**。
 * 系统返回手势也只看起手位置（从中间起手拖到边缘不会触发返回），
 * 所以判定只看落点，不必跟着手指一路改判。
 *
 * 手势区宽度为 0（三键导航、电视、没开手势导航）时一律不拦，
 * 行为与没有这套逻辑时完全一样 —— 不需要额外的开关。
 */
class SystemGestureEdge(leftDp: Float, rightDp: Float) {
    /** 左侧系统手势区宽度（dp）。 */
    val left: Float = leftDp.coerceAtLeast(0f)
    /** 右侧系统手势区宽度（dp）。 */
    val right: Float = rightDp.coerceAtLeast(0f)

    /**
     * 起手位置是不是落在系统手势区里。
     *
     * @param xDp 起手的 x（相对窗口左边缘，dp）
     * @param widthDp 窗口宽度（dp）
     */
    fun blocks(xDp: Float, widthDp: Float): Boolean {
        if(left<=0f && right<=0f) return false
        if(widthDp<=0f) return false
        // 手势区宽到离谱时不至于把整屏都让出去：左右各最多占一半。
        val l=left.coerceAtMost(widthDp/2f)
        val r=right.coerceAtMost(widthDp/2f)
        if(xDp<l) return true
        if(xDp>widthDp-r) return true
        return false
    }

    companion object {
        /** 没有系统手势区（三键导航 / 电视）。 */
        val NONE=SystemGestureEdge(0f,0f)
    }
}
