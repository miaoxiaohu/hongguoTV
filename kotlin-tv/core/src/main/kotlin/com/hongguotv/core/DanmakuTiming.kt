// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import kotlin.math.roundToLong

/**
 * 弹幕在横向上的速度与时长。
 *
 * 之前所有弹幕一律飞 8 秒（`DanmakuOverlay.LIFETIME_MS`）：飞行距离是「屏宽 + 文字宽」，
 * 于是 20 个字的长句和 4 个字的短句**视觉速度一样快** —— 长句根本读不完，
 * 这是用户说的「看不清 / 读不完」的第一来源。
 *
 * 现在改成**固定像素速度**：速度对所有弹幕一致（按屏宽定，约 7.5 秒过一屏），
 * 字越多飘的时间自然越长。20 个字能比 4 个字多拿约 2.43 秒。
 *
 * 尾随入场（`tailClearMs`）是同一套算式的副产品：弹幕的尾巴一离开屏幕右缘，
 * 它那条轨道就能接下一条 —— 水平方向错开，不会叠字。
 *
 * 放在 core 里、且不依赖 Android，是为了能测：速度模型这种「读起来对、屏幕上却不对」
 * 的差别，只有把算式拆出来测才抓得住。
 */
object DanmakuTiming {
    /** 一屏宽的字飘过去要多久（秒）。 */
    const val BASE_SECONDS = 7.5f

    /**
     * 量不到宽度时按这个比例估：一条普通弹幕大约占屏宽的 8%。
     *
     * core 里没有 `Paint`，量不出宽度 —— 探针（`Probe.kt`）和现有单测就走这条退路。
     */
    const val DEFAULT_WIDTH_RATIO = 0.08f

    /**
     * 固定像素速度下，一条弹幕飞完全程要多久（毫秒）。
     *
     * @param textWidth 这条文字有多宽（像素）。
     * @param bandWidth 飞行区间的宽度（整屏）。
     */
    fun lifetimeMs(textWidth: Float, bandWidth: Int): Long {
        if (bandWidth <= 0) return (BASE_SECONDS * 1000f).toLong()
        return (BASE_SECONDS * 1000f * (1f + widthRatio(textWidth, bandWidth))).roundToLong()
    }

    /**
     * 尾巴清场：入场后多久，这条弹幕的尾巴离开屏幕右缘（毫秒）。
     *
     * 推导：x(e) = 屏宽 − (e/全程) × (屏宽 + 文字宽)，尾巴越过右缘 ⟺ x + 文字宽 ≤ 屏宽
     * ⟺ e ≥ 全程 × 文字宽 / (屏宽 + 文字宽)；代入 `lifetimeMs` 后化简成
     * 「基准时间 × 文字宽 / 屏宽」。
     *
     * 宽 100 px 的弹幕尾巴 586 毫秒清场；按默认宽度比（屏宽 8%）算的普通弹幕约 102 px，
     * 约 600 毫秒 —— 520px 的长句要 3047 毫秒 ——
     * 长句在自己那条轨道上占得久，这是对的：它也宽，撞上后来的弹幕会更难看。
     */
    fun tailClearMs(textWidth: Float, bandWidth: Int): Long {
        if (bandWidth <= 0) return (BASE_SECONDS * 1000f * DEFAULT_WIDTH_RATIO).roundToLong()
        return (BASE_SECONDS * 1000f * widthRatio(textWidth, bandWidth)).roundToLong()
    }

    /**
     * 文字占屏宽的比例。量不到宽度就按普通弹幕估。
     *
     * 上界 1 只是兜底：app 层已把文字截到屏宽 90%，产品路径走不到这个分支。
     */
    private fun widthRatio(textWidth: Float, bandWidth: Int): Float =
        if (textWidth <= 0f) DEFAULT_WIDTH_RATIO else (textWidth / bandWidth).coerceIn(0f, 1f)
}
