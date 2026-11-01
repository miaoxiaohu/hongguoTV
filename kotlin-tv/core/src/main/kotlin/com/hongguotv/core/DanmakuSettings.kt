// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 弹幕设置面板里的一行。
 *
 * 面板（app 里那个对话框）只负责画和收按键；有哪些行、当前是什么值、可以选什么，都由 core 说了算 ——
 * 这样「面板上该长什么样」是能测的，不必开电视看一眼。
 *
 * @param title 行标题（如「弹幕大小」）。
 * @param value 当前值的**短标签**（如「标准」），面板上拼成「弹幕大小 · 标准」。
 * @param options 候选值，从上到下就是面板上从上到下。
 * @param selectedIndex 当前值对应 [options] 里的**下标**。面板拿它标出「当前是哪个候选」，
 *   不用拿 [value] 去 [options] 里找 —— 短标签「标准」在候选「标准 · 100%」里并不是原样出现的，
 *   面板自己去字符串匹配会找不到，于是没有任何候选项被标成当前。
 */
data class DanmakuSettingRow(
    val title: String,
    val value: String,
    val options: List<String>,
    val selectedIndex: Int
)

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

    /**
     * 字号档位。顺序与 [DanmakuSize.entries] 一致。
     *
     * **新增字号档位时的约束**：[DanmakuSize.scale] 乘 100 必须精确落在整数上。
     * 百分比是按 `(scale*100).toInt()` 算的 —— 那是**截断**不是四舍五入，
     * 而 `scale` 是单精度浮点：1.05f 乘 100 得到 104.99999，截断后就是 `104`，面板上会显示成
     * 「大 · 104%」这样掉一档的百分比。当前四档（0.75f/1f/1.3f/1.7f）实测都精确落在整数上。
     */
    val sizeOptions=DanmakuSize.entries.map { "${it.label} · ${(it.scale*100).toInt()}%" }

    /** 行数候选。顺序与 [DanmakuRows.options] 一致。 */
    val rowOptions=DanmakuRows.options.map { DanmakuRows.label(it) }

    /** 同屏条数候选。顺序与 [DanmakuDensity.entries] 一致。 */
    val densityOptions=DanmakuDensity.entries.map { "${it.label} · 同屏 ${it.maxOnScreen} 条" }

    /** 透明度候选。顺序与 [DanmakuAlpha.entries] 一致。 */
    val alphaOptions=DanmakuAlpha.entries.map { it.label }

    /**
     * 入口按钮上的一行摘要。
     *
     * 行数先经 [DanmakuRows.normalize] 归一化：摘要里的行数必须是自己候选表里的一项，
     * 不然摘要会显示一个面板上选不到的档位。
     */
    fun summary(enabled: Boolean,size: DanmakuSize,rowCount: Int,density: DanmakuDensity,alpha: DanmakuAlpha): String =
        listOf(if(enabled) "开" else "关",size.label,DanmakuRows.label(DanmakuRows.normalize(rowCount)),density.label,alpha.label).joinToString(" · ")

    /**
     * 面板里的五行。顺序就是面板从上到下的顺序。
     *
     * 行数先经 [DanmakuRows.normalize] 归一化：core 兜住「当前值一定在自己的候选表里」这条不变量 ——
     * 传一个 7 这种不存在的行数进来，落到面板上的就是「自动」，而不是一个选不回去的「7 行」。
     */
    fun rows(enabled: Boolean,size: DanmakuSize,rowCount: Int,density: DanmakuDensity,alpha: DanmakuAlpha): List<DanmakuSettingRow> {
        val rows=DanmakuRows.normalize(rowCount)
        return listOf(
            DanmakuSettingRow("弹幕开关",if(enabled) "开启" else "关闭",toggleOptions,if(enabled) 0 else 1),
            DanmakuSettingRow("弹幕大小",size.label,sizeOptions,DanmakuSize.entries.indexOf(size)),
            DanmakuSettingRow("弹幕行数",DanmakuRows.label(rows),rowOptions,DanmakuRows.options.indexOf(rows)),
            DanmakuSettingRow("弹幕数量",density.label,densityOptions,DanmakuDensity.entries.indexOf(density)),
            DanmakuSettingRow("弹幕透明度",alpha.label,alphaOptions,DanmakuAlpha.entries.indexOf(alpha)))
    }
}
