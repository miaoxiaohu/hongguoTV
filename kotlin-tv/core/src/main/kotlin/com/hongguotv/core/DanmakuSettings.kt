// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 弹幕设置面板里的一行。
 *
 * 面板（app 里那个对话框）只负责画和收按键；有哪些行、当前是什么值、可以选什么，都由 core 说了算 ——
 * 这样「面板上该长什么样」是能测的，不必开电视看一眼。
 */
data class DanmakuSettingRow(val title: String, val value: String, val options: List<String>)

/**
 * 弹幕设置的条目、当前值和候选值。
 *
 * 放在 core 里、不依赖 Android，是为了能测：面板是电视上唯一改弹幕的地方，
 * 改错了用户在屏幕上只能看出「不对」，说不上哪里不对。
 *
 * 各组候选的**顺序**跟对应枚举的顺序一致 —— 面板把「第几个候选」原样交给调用方，
 * 调用方再按下标取枚举，两者必须对齐。
 */
object DanmakuSettings {
    /** 开关的候选。第 0 个是「开启」。 */
    val toggleOptions=listOf("开启","关闭")

    /** 字号档位。顺序与 [DanmakuSize.entries] 一致。 */
    val sizeOptions=DanmakuSize.entries.map { "${it.label} · ${(it.scale*100).toInt()}%" }

    /** 行数候选。顺序与 [DanmakuRows.options] 一致。 */
    val rowOptions=DanmakuRows.options.map { DanmakuRows.label(it) }

    /** 同屏条数候选。顺序与 [DanmakuDensity.entries] 一致。 */
    val densityOptions=DanmakuDensity.entries.map { "${it.label} · 同屏 ${it.maxOnScreen} 条" }

    /** 透明度候选。顺序与 [DanmakuAlpha.entries] 一致。 */
    val alphaOptions=DanmakuAlpha.entries.map { it.label }

    /** 入口按钮上的一行摘要。 */
    fun summary(enabled: Boolean,size: DanmakuSize,rowCount: Int,density: DanmakuDensity,alpha: DanmakuAlpha): String =
        listOf(if(enabled) "开" else "关",size.label,DanmakuRows.label(rowCount),density.label,alpha.label).joinToString(" · ")

    /** 面板里的五行。顺序就是面板从上到下的顺序。 */
    fun rows(enabled: Boolean,size: DanmakuSize,rowCount: Int,density: DanmakuDensity,alpha: DanmakuAlpha): List<DanmakuSettingRow> = listOf(
        DanmakuSettingRow("弹幕开关",if(enabled) "开启" else "关闭",toggleOptions),
        DanmakuSettingRow("弹幕大小",size.label,sizeOptions),
        DanmakuSettingRow("弹幕行数",DanmakuRows.label(rowCount),rowOptions),
        DanmakuSettingRow("弹幕数量",density.label,densityOptions),
        DanmakuSettingRow("弹幕透明度",alpha.label,alphaOptions))
}
