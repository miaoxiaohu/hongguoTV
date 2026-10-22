// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 弹幕的字号档位。
 *
 * 存的是档位而不是倍数：倍数是相对「按屏高算出来的基准字号」而言的，那个基准跟着屏幕走，
 * 存倍数等于把「多大」记成了「相对于这台电视多大」。档位跟着机器走，换台电视还是同一个档。
 */
enum class DanmakuSize(val scale: Float, val label: String) {
    SMALL(0.75f,"小"), NORMAL(1f,"标准"), LARGE(1.3f,"大"), HUGE(1.7f,"特大");
    companion object {
        /** 认不出来的名字按「标准」算：旧备份、旧记录里没有这个字段。 */
        fun fromStored(value: String?)=entries.firstOrNull { it.name==value } ?: NORMAL
    }
}

/**
 * 弹幕占几行。
 *
 * [AUTO] 是按屏幕高度自己算：填满画面上半，下面留给字幕和 HUD。
 * 其余是用户定的行数 —— 字号和行数是两个独立的旋钮，「小字多行」和「大字少行」都能要到。
 */
object DanmakuRows {
    /** 按屏幕高度自己算。 */
    const val AUTO=0
    val options=listOf(AUTO,3,5,8,12)
    fun normalize(value: Int)=options.firstOrNull { it==value } ?: AUTO
    fun label(value: Int)=if(value==AUTO) "自动" else "$value 行"
}

/**
 * 弹幕在屏幕上的排版：字多大、一行多高、有几行。
 *
 * 放在 core 里、且不依赖 Android，是为了能测：字号、行高、行数三者互相牵着
 * （字大了行就高，能塞下的行就少），屏幕上「我明明选了 12 行，怎么只有 7 行」
 * 这种差别，只有把算式拆出来测才抓得住。
 *
 * @param textSize 一行字多大（像素）。
 * @param trackHeight 一行占多高（像素）。
 * @param trackCount 实际有几行。
 * @param clamped 想要的行数是不是被屏幕截了 —— 截了得告诉用户，不能让他对不上。
 */
data class DanmakuLayout(
    val textSize: Float,
    val trackHeight: Int,
    val trackCount: Int,
    val clamped: Boolean
)

/** 弹幕的排版算式。 */
object DanmakuLayouts {
    /** 基准字号按屏高定：太小看不清，太大糊住画面。 */
    fun baseTextSize(heightPx: Int):Float=(heightPx*0.036f).coerceIn(18f,64f)
    /** 自动行数时，弹幕只占画面上半。 */
    const val AUTO_BAND=0.42f
    /** 弹幕最多能占画面多高：下面要给字幕和 HUD 留地方。 */
    const val MAX_BAND=0.7f

    /**
     * 排一次版。
     *
     * @param heightPx 画面高度（像素）。
     * @param size 字号档位。
     * @param requestedRows 想要的行数，[DanmakuRows.AUTO] 表示按屏幕高度自己算。
     */
    fun resolve(heightPx: Int,size: DanmakuSize,requestedRows: Int):DanmakuLayout {
        val textSize=(baseTextSize(heightPx)*size.scale).coerceIn(14f,88f)
        val trackHeight=(textSize*1.5f).toInt().coerceAtLeast(1)
        if(requestedRows==DanmakuRows.AUTO) {
            val band=(heightPx*AUTO_BAND).toInt()
            return DanmakuLayout(textSize,trackHeight,(band/trackHeight).coerceAtLeast(1),false)
        }
        // 用户定了行数就照他说的排，但画面就那么多高 —— 放不下就少几行，并且记下来被截过。
        val maxRows=((heightPx*MAX_BAND).toInt()/trackHeight).coerceAtLeast(1)
        val rows=requestedRows.coerceIn(1,maxRows)
        return DanmakuLayout(textSize,trackHeight,rows,rows<requestedRows)
    }
}
