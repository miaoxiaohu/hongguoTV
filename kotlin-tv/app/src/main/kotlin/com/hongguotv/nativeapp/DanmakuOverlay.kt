// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuTimeline

/**
 * 播在画面上的弹幕。
 *
 * 自绘，不用 TextView：一条弹幕要在移动中不断重画，几百个 TextView 会把这台老机器拖垮，
 * 而真正画出来的东西只是几个字。
 *
 * 位置跟着**播放位置**走，不用墙钟。所以暂停时弹幕跟着停，跳转之后按新的位置显示，倍速播放
 * 时弹幕也跟着快 —— 用墙钟算位移这三件事一定做不对其中一件。
 *
 * 画面上只留两条粗边：电视上看到的背景五花八门，没有描边的白字在亮画面上就没了。
 */
class DanmakuOverlay(context: Context) : View(context) {
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE }
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Color.argb(220,0,0,0) }
    private var timeline: DanmakuTimeline?=null
    /** 每条弹幕的宽度，算一次就够 —— 每帧 measureText 在这台机器上是白白的开销。 */
    private var widths=FloatArray(0)
    /** 尺寸还没定下来时先存着，定下来再排 —— 字号是 0 时量出来的宽度全是 0。 */
    private var pending: List<Danmaku>?=null
    private var positionSource:()->Long={ 0L }
    private var running=false
    private var textSizePx=0f
    private var trackHeightPx=0
    private var trackCount=1

    private val tick=object: Runnable {
        override fun run() {
            if(!running) return
            invalidate()
            postDelayed(this,FRAME_MS)
        }
    }

    /**
     * 换一集的弹幕。
     *
     * 轨道数是按屏幕高度算的，所以宽度和高度定下来之前先记着，等尺寸到了再排。
     */
    fun setItems(items: List<Danmaku>) {
        if(items.isEmpty()) { timeline=null; widths=FloatArray(0); pending=null; invalidate(); return }
        if(textSizePx<=0) { pending=items; invalidate(); return }
        pending=null
        fill.textSize=textSizePx
        edge.textSize=textSizePx
        edge.strokeWidth=(textSizePx*.1f).coerceAtLeast(1f)
        val laid=DanmakuTimeline(items,trackCount,lifetimeMs())
        timeline=laid
        widths=FloatArray(laid.placed.size) { fill.measureText(laid.placed[it].danmaku.text) }
        invalidate()
    }

    /** 弹幕的位置从哪儿来。 */
    fun setPositionSource(source: ()->Long) { positionSource=source }

    fun start() {
        if(running) return
        running=true
        post(tick)
    }

    fun stop() {
        running=false
        removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w<=0||h<=0) return
        // 字号按屏高定：电视上看得清，又不至于糊住画面。
        textSizePx=(h*.036f).coerceIn(18f,64f)
        trackHeightPx=(textSizePx*1.5f).toInt()
        // 轨道只占画面上半：下面要给字幕和 HUD 留地方。
        trackCount=((h*.42f)/trackHeightPx).toInt().coerceAtLeast(1)
        // 尺寸变了，轨道数和字号都变了，重排一次。
        val items=pending ?: timeline?.placed?.map { it.danmaku }
        if(items!=null) setItems(items)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val laid=timeline ?: return
        val position=positionSource()
        val visible=laid.visibleAt(position)
        if(visible.isEmpty()) return
        val baseline0=textSizePx
        for(v in visible) {
            val w=if(v.index<widths.size) widths[v.index] else 0f
            val travel=width+w
            val x=width-(v.elapsedMs.toFloat()/laid.lifetimeMs)*travel
            if(x>width||x+w<0) continue
            val baseline=baseline0+v.track*trackHeightPx
            canvas.drawText(v.danmaku.text,x,baseline,edge)
            canvas.drawText(v.danmaku.text,x,baseline,fill)
        }
    }

    /** 一条弹幕从右边走到左边要多久。 */
    private fun lifetimeMs(): Long = LIFETIME_MS

    companion object {
        /** 约 8 秒走完全屏。 */
        const val LIFETIME_MS=8_000L
        /** 30 帧一秒：这个画面的滚动速度用不着 60 帧。 */
        private const val FRAME_MS=33L
    }
}
