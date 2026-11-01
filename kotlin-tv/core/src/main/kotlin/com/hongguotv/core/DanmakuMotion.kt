// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 浮点区间。
 *
 * Kotlin 2.1 的标准库里还没有 `FloatRange`（它到 2.2 才加），浮点区间只有 `ClosedFloatingPointRange`。
 * 这里给它起个名字，接口里就能写 `FloatRange`，升级 Kotlin 之后删掉这一行即可。
 */
typealias FloatRange = ClosedFloatingPointRange<Float>

/**
 * 弹幕在**横向**上怎么飞。
 *
 * 用户 2026-11-01 的要求是：「弹幕应该是从右边第一个字出来，左最后一个字结束」。
 * 这句话钉死了两件事：
 *
 * - 飞行区间是**整个屏幕**，不是视频画面那一块矩形。竖屏短剧在 16:9 电视上左右留黑边，
 *   按画面矩形算的话弹幕根本碰不到屏幕的右缘和左缘，谈不上「从右边出来」。
 * - 起手时整条弹幕在屏幕**外面**（一个字都看不见），飞行中第一个字先从右缘露头，
 *   收尾时最后一个字最后在左缘收尾。
 *
 * 0.9.50 那次改成「跟画面矩形走」（见 `git show 3eecda9`）的副作用是：画的时候没裁剪，
 * 起点落在画面右缘，文字整条画在右边那条黑边上 —— 用户看到的是「整条突然出现在右边」。
 * 这里把起止都放在屏外，顺手把这个毛病一起治了。
 *
 * 竖向（轨道、字号、行数、密度、透明度）不在这里管。
 */
object DanmakuMotion {
    /**
     * 文字左边缘的 x。
     *
     * @param elapsedMs 这条弹幕已经飞了多久（毫秒）。
     * @param lifetimeMs 一条弹幕飞完全程要多久（毫秒）。
     * @param bandWidth 飞行区间的宽度 —— 整屏（overlay）的宽度。
     * @param textWidth 这条文字有多宽。
     */
    fun xAt(elapsedMs: Long, lifetimeMs: Long, bandWidth: Int, textWidth: Float): Float {
        // 没有宽度就没地方飞。
        if (bandWidth <= 0) return 0f
        // 零时长会除零。这种情况当作「刚起步」：整条还在屏外。
        if (lifetimeMs <= 0) return bandWidth.toFloat()
        val travel = bandWidth + textWidth
        return bandWidth - (elapsedMs.toFloat() / lifetimeMs) * travel
    }

    /**
     * 文字**自身坐标**里看得见的那一段。
     *
     * 0 是第一个字的左边缘，[textWidth] 是最后一个字的右边缘。
     *
     * @return 看得见的那一段；整条都在屏外时是 null（一个字都不露）。
     */
    fun visibleTextRange(elapsedMs: Long, lifetimeMs: Long, bandWidth: Int, textWidth: Float): FloatRange? {
        if (bandWidth <= 0) return null
        if (textWidth <= 0) return null
        val x = xAt(elapsedMs, lifetimeMs, bandWidth, textWidth)
        // 屏幕左边切掉的：x 是负的时，文字左边一截在屏外。
        val from = maxOf(0f, -x)
        // 屏幕右边切掉的：文字右边一截越过右缘了。
        val to = minOf(textWidth, bandWidth - x)
        return if (to > from) from..to else null
    }
}
