// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuAlpha
import com.hongguotv.core.DanmakuDensity
import com.hongguotv.core.DanmakuLayout
import com.hongguotv.core.DanmakuLayouts
import com.hongguotv.core.DanmakuMotion
import com.hongguotv.core.DanmakuRows
import com.hongguotv.core.DanmakuSize
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
    /**
     * 文字宽度的测量缓存：同一串字只量一次。
     *
     * 截断（`fit`）和排版量的是同一批字，共用一个缓存就不用把同一串字量两遍。
     * 字号一改，量出来的宽度就作废 —— `setItems` 每次都先把它清空。
     */
    private val measured=HashMap<String,Float>()
    /** 尺寸还没定下来时先存着，定下来再排 —— 字号是 0 时量出来的宽度全是 0。 */
    private var pending: List<Danmaku>?=null
    private var positionSource:()->Long={ 0L }
    private var speedSource:()->Float={ 1f }
    private var running=false
    private var textSizePx=0f
    private var trackHeightPx=0
    private var trackCount=1
    /** 用户要的字号档位和行数。定下来之前先记着，等画面有了尺寸再排。 */
    private var size=DanmakuSize.NORMAL
    private var requestedRows=DanmakuRows.AUTO
    /** 同屏最多几条。弹幕一多，画面就是一片白字（实测同屏最高 36 条）。 */
    private var density=DanmakuDensity.MEDIUM
    /** 弹幕有多透明。描边比填充实：亮画面上白字全靠那道黑边才看得清。 */
    private var alpha=DanmakuAlpha.HALF
    /**
     * 最近一次交给本层的全部弹幕。
     *
     * 重排要从这里重来，不能从「已经排好的」重来：被密度上限丢掉的那几条，得留着 ——
     * 用户把密度调高时它们该回来。
     */
    private var source: List<Danmaku> = emptyList()
    /** 画面的高度。字号和行数都是按它算的。 */
    private var viewHeight=0
    private var layout: DanmakuLayout?=null

    /**
     * 屏幕放不下用户要的那么多行时告诉外面实际有几行。
     *
     * 只有「刚被截过」的那一刻才喊：界面上写着 12 行而屏幕上只有 7 行，用户只会以为这个设置
     * 是坏的 —— 得说出来。但也不该一直喊，所以截过一次之后要等它不再被截才肯再喊。
     */
    var onClamped: ((Int) -> Unit)? = null
    private var reportedClamp=false

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
        source=items
        if(items.isEmpty()) { timeline=null; widths=FloatArray(0); pending=null; invalidate(); return }
        if(textSizePx<=0||width<=0) { pending=items; invalidate(); return }
        pending=null
        // 量宽度要用当前字号，所以画笔先设好，再去量、去截。
        fill.textSize=textSizePx
        edge.textSize=textSizePx
        // 描边粗一点：白字在电视上那种五花八门的画面里，全靠这道黑边才看得清。
        edge.strokeWidth=(textSizePx*.14f).coerceAtLeast(1f)
        // 字号变了，之前量过的宽度就作废：缓存先清掉，别拿旧字号的宽度去算时长。
        measured.clear()
        // 超长的先截短：一条比屏还宽的弹幕要飘两屏宽才走完，看着就是一大坨。
        val fitted=items.map { it.copy(text=fit(it.text)) }
        // 每条有多宽，core 要靠它算飞行时长和尾巴清场的时间。量一次就够 —— 每帧 measureText
        // 在这台机器上是白白的开销。
        val laid=DanmakuTimeline(fitted,trackCount,lifetimeMs(),density.maxOnScreen,
            bandWidth=width,textWidthOf={ widthOf(it.text) })
        timeline=laid
        widths=FloatArray(laid.placed.size) { widthOf(laid.placed[it].danmaku.text) }
        invalidate()
    }

    /**
     * 太宽的文字截短。
     *
     * 超过屏宽九成的文字要飘两屏宽才走完，挡着画面看不清 —— 截掉加省略号。
     */
    private fun fit(text:String):String {
        val limit=width*.9f
        if(limit<=0f) return text
        if(widthOf(text)<=limit) return text
        var low=0
        var high=text.length
        while(low<high) {
            val mid=(low+high+1)/2
            if(widthOf(text.substring(0,mid)+"…")<=limit) low=mid else high=mid-1
        }
        // 二分是按 UTF-16 码元切的，可能落在代理对中间：半个 emoji 画出来是一块豆腐。
        if(low>0&&text[low-1].isHighSurrogate()) low--
        return text.substring(0,low)+"…"
    }

    /** 量一段文字有多宽（像素）。同一串字只量一次 —— 走 `measured` 缓存。 */
    private fun widthOf(text:String):Float = measured.getOrPut(text) { fill.measureText(text) }

    /** 弹幕的位置从哪儿来。 */
    fun setPositionSource(source: ()->Long) { positionSource=source }

    /**
     * 弹幕的字号、行数、同屏条数和透明度。
     *
     * 轨道是排的时候定死的，所以改了要重排：不重排的话，字变大了还是按老的行高摆，
     * 上一行的字会压到下一行头上，看起来就是「调大了反而更挤」。密度也一样 —— 上限变了，
     * 哪些弹幕该被丢、哪些该晚点飘，都要重算。
     *
     * @return 是不是真的改了。
     */
    fun setStyle(size: DanmakuSize,rows: Int,density: DanmakuDensity,alpha: DanmakuAlpha): Boolean {
        if(size==this.size && rows==requestedRows && density==this.density && alpha==this.alpha) return false
        this.size=size; requestedRows=rows; this.density=density; this.alpha=alpha
        applyLayout()
        return true
    }

    /**
     * 按现在的字号和行数排一遍版。
     *
     * 排版的算式在 core 里（[DanmakuLayouts]），这里只负责把算出来的结果用到画布上：
     * 字号给画笔，行高给行距，行数给轨道数，然后重排一次弹幕。
     */
    private fun applyLayout() {
        if(viewHeight<=0) return
        val laid=DanmakuLayouts.resolve(viewHeight,size,requestedRows,density)
        layout=laid
        textSizePx=laid.textSize
        trackHeightPx=laid.trackHeight
        trackCount=laid.trackCount
        fill.textSize=textSizePx
        edge.textSize=textSizePx
        edge.strokeWidth=(textSizePx*.14f).coerceAtLeast(1f)
        // 透明度：填充是白字，描边是黑边，两者按档位走。
        fill.color=Color.argb(alpha.fill,255,255,255)
        edge.color=Color.argb(alpha.edge,0,0,0)
        if(laid.clamped&&!reportedClamp) { reportedClamp=true; onClamped?.invoke(laid.trackCount) }
        else if(!laid.clamped) reportedClamp=false
        val items=pending ?: if(source.isEmpty()) null else source
        if(items!=null) setItems(items)
    }

    /**
     * 现在的播放倍速。
     *
     * 弹幕在画面上飘过去要多久按的是真实时间：2 倍速时画面内容走得快，弹幕也跟着飞快的话，
     * 一句话还没看完就出屏了 —— 倍速下就白看了。它**出现在哪一刻**仍按视频时间轴算，
     * 所以弹幕和画面的内容还是对得上。
     */
    fun setSpeedSource(source: ()->Float) { speedSource=source }

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
        // 字号按屏高定，行数按字号和屏高一起定 —— 都在 core 的算式里，这里只管用结果。
        viewHeight=h
        applyLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val laid=timeline ?: return
        val position=positionSource()
        val visible=laid.visibleAt(position,speedSource())
        if(visible.isEmpty()) return
        // 飞行区间是**整个屏幕**，不是视频画面那一块矩形。
        //
        // 用户 2026-11-01 要的是「从右边第一个字出来，左最后一个字结束」。竖屏短剧在 16:9 电视上
        // 左右留黑边，按画面矩形算的话弹幕根本碰不到屏幕的右缘和左缘。
        //
        // 0.9.50 那次改成跟画面矩形走（git show 3eecda9）的副作用是：画的时候没裁剪，起点落在画面
        // 右缘，整条文字直接摆在右边那条黑边上 —— 用户看到的是「整条突然出现在右边」。所以这次
        // 起止都放在屏外：刚起步时整条在屏幕右边外面，第一个字才从右缘露头；收尾时最后一个字
        // 最后在屏幕左缘收尾。
        val bandWidth=width
        val baseline0=textSizePx
        for(v in visible) {
            val w=if(v.index<widths.size) widths[v.index] else 0f
            val x=DanmakuMotion.xAt(v.elapsedMs,v.lifetimeMs,bandWidth,w)
            // 整条都在屏外就不画：一个字都不露的时候画了也没用。
            if(DanmakuMotion.visibleTextRange(v.elapsedMs,v.lifetimeMs,bandWidth,w)==null) continue
            val baseline=baseline0+v.track*trackHeightPx
            canvas.drawText(v.danmaku.text,x,baseline,edge)
            canvas.drawText(v.danmaku.text,x,baseline,fill)
        }
    }

    /**
     * 一条弹幕从右边走到左边要多久。
     *
     * core 的兜底值：产品路径每条都按自己的宽度算时长（core 的基准是 7.5 秒过一屏），
     * 这个值在 app 里走不到 —— 留着是因为 core 的老接口要一个统一时长。
     */
    private fun lifetimeMs(): Long = LIFETIME_MS

    companion object {
        /** core 的兜底值：产品路径每条按自己的宽度算时长，用不到它。 */
        const val LIFETIME_MS=8_000L
        /** 30 帧一秒：这个画面的滚动速度用不着 60 帧。 */
        private const val FRAME_MS=33L
    }
}
