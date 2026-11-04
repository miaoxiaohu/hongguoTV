// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 播放页手势的意图。
 *
 * 手势判定与 Android 无关：坐标先换算成 dp，判定完只说「要做什么」，
 * 真正做事（seek、调音量、开关 HUD）留给上层。
 */
sealed class TouchIntent {
    /** 单击画面：显示或隐藏 HUD。 */
    object ToggleHud : TouchIntent()
    /** 双击画面：播放或暂停。 */
    object PlayPause : TouchIntent()
    /** 横向拖动松手：跳到目标位置。 */
    data class Scrub(val targetMs: Long) : TouchIntent()
    /** 纵向拖动：音量增减若干档。 */
    data class Volume(val steps: Int) : TouchIntent()
    /** 什么都不做。 */
    object None : TouchIntent()
}

/**
 * 手势判定机。
 *
 * 三件事必须在它这里做对：
 *
 * 1. **方向锁**：位移过 slop 后一次性定方向，之后不再改判。斜滑只能触发一个动作 ——
 *    否则一次斜滑既拖进度又调音量，用户自己都说不清发生了什么。
 * 2. **单击与双击不能同时成立**：二者的判定时间天然打架。这里取「单击晚发」：
 *    单击要等双击超时才确认（`onTimeAdvance` 里出），双击立刻出。代价是单击有 300ms 延迟，
 *    但不会出现「双击先触发一次 HUD 开关」。
 * 3. **拖动期间不真的 seek**：只在 `onUp` 时给一次目标位置 —— 拖一次 seek 一次会卡成幻灯片。
 *
 * 位置与时长由调用方在按下瞬间给进来，机器自己不持有播放器引用；
 * 拖动期间这两个值固定不变，否则手指停住时目标位置还在飘。
 */
class TouchGestureMachine {
    private enum class Axis { None, Horizontal, Vertical }

    private var axis=Axis.None
    private var downX=0f
    private var downY=0f
    private var basePositionMs=0L
    private var durationMs=0L
    /** 上一根手指抬起的时间；用来判双击。 */
    private var lastUpAt=Long.MIN_VALUE
    /** 音量已经发出的档数，拖动中只发增量。 */
    private var volumeStepsSent=0

    /** 拖动中的目标位置（预览用）；不在拖动时为 null。 */
    var pendingScrubTargetMs: Long?=null
        private set

    /** 现在是不是在拖动。 */
    val isDragging get() = axis!=Axis.None

    /**
     * 按下。`positionMs` 是当前播放位置，`durationMs` 是总时长（未知传 0）。
     * `nowMs` 是单调时钟，用来判双击。
     */
    fun onDown(xDp: Float,yDp: Float,positionMs: Long,durationMs: Long,nowMs: Long) {
        axis=Axis.None
        downX=xDp; downY=yDp
        basePositionMs=positionMs; this.durationMs=durationMs
        pendingScrubTargetMs=null
        volumeStepsSent=0
        // 离上次抬起不到双击超时，这一就是第二下。
        if(lastUpAt!=Long.MIN_VALUE && nowMs-lastUpAt<DOUBLE_TAP_MS) {
            // 双击的第二下算数了，挂着的单击作废。
            lastUpAt=Long.MIN_VALUE
            doubleTap=true
        }
    }

    /**
     * 移动。返回的意图只在纵向拖动时非空 —— 音量要边拖边调，不能等松手。
     */
    fun onMove(xDp: Float,yDp: Float): TouchIntent {
        val dx=xDp-downX; val dy=yDp-downY
        if(axis==Axis.None) {
            if(kotlin.math.abs(dx)<SLOP_DP && kotlin.math.abs(dy)<SLOP_DP) return TouchIntent.None
            // 方向只定一次：斜滑取位移较大的那个方向，此后不再改判。
            axis=if(kotlin.math.abs(dx)>=kotlin.math.abs(dy)) Axis.Horizontal else Axis.Vertical
        }
        return when(axis) {
            Axis.Horizontal -> {
                pendingScrubTargetMs=scrubTarget(dx)
                TouchIntent.None
            }
            Axis.Vertical -> {
                // 上滑为正（dy 是负的），按总位移算，slop 那点位移也一并算进去。
                val steps=kotlin.math.round((-dy/VOLUME_STEP_DP)).toInt()
                val delta=steps-volumeStepsSent
                if(delta==0) TouchIntent.None else { volumeStepsSent=steps; TouchIntent.Volume(delta) }
            }
            Axis.None -> TouchIntent.None
        }
    }

    /**
     * 抬起。横向拖动在这里给出目标位置；单击在这里记时间，不在这里确认。
     */
    fun onUp(xDp: Float,yDp: Float,nowMs: Long): TouchIntent {
        val isTap=axis==Axis.None
        val wasDouble=doubleTap
        doubleTap=false
        val intent: TouchIntent=when(axis) {
            Axis.Horizontal -> TouchIntent.Scrub(pendingScrubTargetMs ?: scrubTarget(xDp-downX))
            Axis.Vertical -> TouchIntent.None
            Axis.None -> if(wasDouble) TouchIntent.PlayPause else TouchIntent.None
        }
        axis=Axis.None
        pendingScrubTargetMs=null
        // 只有「点」才挂一个待确认的单击：拖动不是单击，双击之后也不能再补一个。
        lastUpAt=when {
            wasDouble -> Long.MIN_VALUE
            isTap -> nowMs
            else -> Long.MIN_VALUE
        }
        return intent
    }

    /**
     * 推进时钟。超过双击超时就把挂着的单击确认掉。
     */
    fun onTimeAdvance(nowMs: Long): TouchIntent {
        if(lastUpAt==Long.MIN_VALUE) return TouchIntent.None
        if(nowMs-lastUpAt<DOUBLE_TAP_MS) return TouchIntent.None
        lastUpAt=Long.MIN_VALUE
        return TouchIntent.ToggleHud
    }

    /** 复位：换集、退出播放、面板打开时调用。 */
    fun reset() {
        axis=Axis.None; pendingScrubTargetMs=null; doubleTap=false
        lastUpAt=Long.MIN_VALUE; volumeStepsSent=0
    }

    /**
     * 横向拖动算出来的目标位置。夹在 `[0, duration-500]` 里。
     */
    private fun scrubTarget(totalDxDp: Float): Long {
        if(durationMs<=0) return basePositionMs
        val delta=(totalDxDp*SECONDS_PER_DP*1000).toLong()
        val max=(durationMs-500).coerceAtLeast(0)
        return (basePositionMs+delta).coerceIn(0,max)
    }

    /** 这一下是不是双击的第二下。 */
    private var doubleTap=false

    companion object {
        /** 超过这个位移（dp）才算「拖」，否则算「点」。 */
        const val SLOP_DP=8f
        /** 横向拖动每 dp 对应多少秒。 */
        const val SECONDS_PER_DP=0.5f
        /** 纵向拖动每 dp 对应多少档音量。 */
        const val VOLUME_STEP_DP=24f
        /** 两次点击间隔超过这么久就不算双击。 */
        const val DOUBLE_TAP_MS=300L
    }
}
