// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/**
 * 一条弹幕。
 *
 * @param text 文本。平台把 `[昵称]` 之类的标记拼在正文里，那不是用户输入的一部分。
 * @param offsetMs 相对本集开头的偏移，**毫秒**。平台给的就是毫秒，当成秒用会整体偏 1000 倍。
 * @param digg 点赞数。
 * @param enterAtMs 什么时候开始飞进画面。默认跟 [offsetMs] 一样 —— 只有数据晚到了才会被往后挪，
 *  见 [DanmakuAnchors]。分开存是因为这两个时间不是一回事：内容时间是「这句话在第几秒说的」，
 *  入场时间是「屏幕上什么时候开始飘它」。晚到的时候只有后者能改，改了内容时间就跟画面对不上了。
 */
data class Danmaku(val text: String, val offsetMs: Long, val digg: Int, val enterAtMs: Long = offsetMs)

/**
 * 一次窗口请求的结果。
 *
 * @param items 这一窗里的弹幕。
 * @param nextStartMs 下一窗该从哪里开始问。
 * @param hasMore 后面还有没有。
 */
data class DanmakuWindow(val items: List<Danmaku>, val nextStartMs: Long, val hasMore: Boolean)

/** 平台一次给 30 秒窗口。 */
const val DANMAKU_WINDOW_MS = 30_000L

/**
 * 解析平台返回的一个弹幕窗口。
 *
 * 平台在被拒时回的是 HTTP 200 + `Code 110001`，所以错误在 body 里，不在状态码里 —— 只看状态码
 * 会把「被拒」当成「成功但没有弹幕」。
 *
 * @param startMs 这一窗是从哪儿开始的（用来算下一窗的起点）。
 */
internal fun parseDanmakuWindow(payload: String, startMs: Long): DanmakuWindow {
    val json=JSONObject(payload)
    val code=if(json.has("code")) json.optInt("code",-1) else json.optInt("Code",-1)
    if(code!=0) throw java.io.IOException("弹幕接口不可用（code=$code）")
    val data=json.optJSONObject("data") ?: return DanmakuWindow(emptyList(),startMs+DANMAKU_WINDOW_MS,false)
    val list=data.optJSONArray("data_list")
    val items=mutableListOf<Danmaku>()
    for(i in 0 until (list?.length() ?: 0)) {
        val comment=list!!.optJSONObject(i)?.optJSONObject("comment") ?: continue
        val text=comment.optJSONObject("common")?.optJSONObject("content")?.optString("text").orEmpty().trim()
        if(text.isEmpty()) continue
        val offset=comment.optJSONObject("expand")?.optLong("offset_time") ?: continue
        val digg=comment.optJSONObject("stat")?.optInt("digg_count") ?: 0
        items+=Danmaku(text,offset.coerceAtLeast(0),digg.coerceAtLeast(0))
    }
    val info=data.optJSONObject("common_list_info")
    val extra=data.optJSONObject("extra")
    var next=extra?.optLong("next_query_danmaku_list_time") ?: -1L
    // 平台有时给 0（那就是最后一窗），有时不给。那就按窗口长度往下推。
    if(next<=startMs) next=startMs+DANMAKU_WINDOW_MS
    return DanmakuWindow(items.sortedBy { it.offsetMs },next,info?.optBoolean("has_more") ?: false)
}

/**
 * 弹幕的排布：哪条弹幕走哪一条轨道，在某个播放位置上还看不看得见。
 *
 * 放在 core 里、且不依赖 Android，是为了能测：轨道分配是那种「代码读起来对、屏幕上却挤成一团」
 * 的差别。同一时刻两条弹幕走同一条轨道就会叠字，所以分配时看的是上一条什么时候走完。
 *
 * 排布还管「同屏最多几条」（[maxOnScreen]）：弹幕一多，屏幕上就是一片白字 —— 实测一集同屏最高
 * 36 条，铺在 7 行轨道上，画面直接被糊住。所以满员的时候后来的弹幕要晚点飘进来，等不到就丢。
 *
 * @param items 这一集的弹幕。
 * @param trackCount 屏幕上有几条轨道。
 * @param lifetimeMs 一条弹幕从右边走到左边要多久。
 * @param maxOnScreen 同屏最多几条。
 *
 * 排布和可见性都按**入场时间**（[Danmaku.enterAtMs]）算，不是内容时间：入场时间才是
 * 「屏幕上什么时候开始飘它」。晚到的弹幕入场时间被往后挪过（见 [DanmakuAnchors]），
 * 挪完它们从画面右边飞进来，而不是一出现就摆在画面中间。
 */
class DanmakuTimeline(
    items: List<Danmaku>,
    val trackCount: Int,
    val lifetimeMs: Long,
    /**
     * 同屏最多几条。
     *
     * 满员的时候后来的弹幕不是硬塞进来，而是**晚点飘进来** —— 等一条旧的走完位置空出来。
     * 等不到（晚太多）就丢掉。
     */
    val maxOnScreen: Int = DanmakuDensity.MEDIUM.maxOnScreen
) {
    /** 一条弹幕占的位置。 */
    data class Placed(val danmaku: Danmaku, val track: Int)

    /** 某一刻还看得见的弹幕，以及它已经走了多久。 */
    data class Visible(val danmaku: Danmaku, val track: Int, val elapsedMs: Long, val index: Int)

    /**
     * 每条弹幕的轨道，按入场时间排好。
     *
     * 排轨之外还管一件事：**同屏最多几条**。满员的时候后来的弹幕不是硬塞进来，而是晚点飘进来 ——
     * 等一条旧的走完、位置空出来；等不到（晚太多）就丢掉。
     */
    val placed: List<Placed> = buildList {
        // 每条轨道记住「上一条什么时候走完」，空闲的轨道就是走完时间 <= 下一条的起点。
        val freeAt=LongArray(maxOf(1,trackCount))
        // 已经放行的弹幕的入场时间（升序）：同屏有几条就是从这里数出来的。
        val entered=ArrayList<Long>()
        for(item in items.sortedBy { it.enterAtMs }) {
            val want=item.enterAtMs
            var at=want
            var admitted=false
            while(at-want<=MAX_DELAY_MS) {
                // 同屏条数 = 入场时间落在 (at-全程, at] 里的那些。
                val alive=aliveRange(entered,at,lifetimeMs)
                if(alive.count()<maxOnScreen) { admitted=true; break }
                // 满员了：等最早离场的那条腾出位置，再挤进来。
                at=entered[alive.first]+lifetimeMs
            }
            // 等不到位置就丢。
            //
            // 晚太多还飘出来的弹幕，跟眼前画面对不上 —— 观众看到的是一句跟画面无关的话，
            // 比没有弹幕更糟（跟「错过太久的弹幕不往回捞」是同一个道理）。
            if(!admitted) continue
            var chosen=-1
            for(t in freeAt.indices) if(freeAt[t]<=at) { chosen=t; break }
            if(chosen<0) {
                // 全都还占着：挤最早走完的那一条，前一条会被覆盖。
                chosen=freeAt.indices.minByOrNull { freeAt[it] } ?: 0
            }
            freeAt[chosen]=at+lifetimeMs
            insertEntered(entered,at)
            // 入场时间改成真正入场的时刻：晚飘的弹幕必须从右边飞进来。不改的话它一露面就摆在
            // 画面中间 —— 那正是「凭空冒出来」那个毛病。
            add(Placed(item.copy(enterAtMs=at),chosen))
        }
    }.sortedBy { it.danmaku.enterAtMs }

    /** 每条弹幕的起点，单独存一份是为了每帧能二分，而不是每帧扫全表。 */
    private val offsets=LongArray(placed.size) { placed[it].danmaku.enterAtMs }

    /** 第一个起点大于 [value] 的下标（没有就是表长）。 */
    private fun firstAfter(value: Long): Int {
        var low=0; var high=offsets.size
        while(low<high) {
            val mid=(low+high)/2
            if(offsets[mid]<=value) low=mid+1 else high=mid
        }
        return low
    }

    /**
     * 某一刻看得见的弹幕。
     *
     * 位置用播放器的播放位置算，不用墙钟：暂停时弹幕跟着停，跳转后按新位置显示，倍速播放时
     * 弹幕也跟着快 —— 这几件事只要用墙钟就一定做不对。
     *
     * 看得见的弹幕在时间轴上是一段连续的区间（活得一样久），所以这里二分出那段区间，而不是
     * 每帧把整集扫一遍 —— 播放时每 33 毫秒就要问一次。
     *
     * @param positionMs 播放位置（**视频时间**轴）。
     * @param speed 播放倍速。弹幕在画面上飘过去要多久，按的是**真实时间**：2 倍速时画面内容
     *  走得快，弹幕也跟着飞快的话，一句话还没看完就出屏了 —— 倍速下就白看了。
     *  出现的时间点仍按视频时间轴算，所以弹幕和画面的内容对得上。
     */
    fun visibleAt(positionMs: Long, speed: Float = 1f): List<Visible> {
        val factor=if(speed>0.05f) speed else 1f
        val end=firstAfter(positionMs)                             // 第一个起点 > 位置
        // 存活窗口按倍速放大：8 秒真实时间，在 2 倍速下是 16 秒的**视频**时间。
        val begin=firstAfter(positionMs-(lifetimeMs*factor).toLong())
        if(end<=begin) return emptyList()
        val out=ArrayList<Visible>(end-begin)
        for(i in begin until end) {
            val p=placed[i]
            out+=Visible(p.danmaku,p.track,((positionMs-p.danmaku.enterAtMs)/factor).toLong(),i)
        }
        return out
    }

    /**
     * 已经放行的弹幕里，[at] 这一刻**还活着**的是哪一段。
     *
     * 活着的条件是「已经入场、但还没走完」：入场时间 <= at 且 入场时间 + 全程 > at。
     * 已经放行的弹幕的入场时间是升序的，所以二分两下就有了。
     *
     * @return 活着的那些在 [entered] 里的下标范围；空的说明这一刻画面上一条都没有。
     */
    private fun aliveRange(entered: List<Long>, at: Long, lifetimeMs: Long): IntRange {
        val floor=at-lifetimeMs
        var low=0; var high=entered.size
        while(low<high) {
            val mid=(low+high)/2
            if(entered[mid]<=floor) low=mid+1 else high=mid
        }
        val first=low
        var up=first; var top=entered.size
        while(up<top) {
            val mid=(up+top)/2
            if(entered[mid]<=at) up=mid+1 else top=mid
        }
        return first until up
    }

    /** 把入场时间插进已放行的那一串里，保持升序 —— 后面的二分靠的就是这个顺序。 */
    private fun insertEntered(entered: ArrayList<Long>, at: Long) {
        var low=0; var high=entered.size
        while(low<high) {
            val mid=(low+high)/2
            if(entered[mid]<at) low=mid+1 else high=mid
        }
        entered.add(low,at)
    }

    companion object {
        /**
         * 同屏满了的时候，后来的弹幕最多能晚多少才飘进来。
         *
         * 两秒。晚一点是「网络慢了点」，观众还等得起；晚太多飘出来的弹幕跟眼前画面对不上，
         * 那就不如不飘。
         */
        const val MAX_DELAY_MS=2_000L
    }
}

/**
 * 晚到的弹幕怎么安置。
 *
 * 弹幕在画面上的位置是按「播放位置减去内容时间」算的 —— 这样弹幕和画面对得上：说到哪句，弹幕就在哪句。
 * 但弹幕**只有数据到了才画得出来**。数据是每 30 秒一窗从站方拉的，拉一次一秒多；这一窗的数据
 * 到手时，这一窗开头那几秒已经过去了 —— 那些弹幕的「播放位置减内容时间」一下变得很大，
 * 于是它们一出现就整条摆在画面中间，**像凭空冒出来，而不是从右边飞进来**。
 *
 * 所以这里把「入场时间」和「内容时间」分开：内容时间不动（跟画面对得上），入场时间往后挪。
 * 挪的方式是**整批一起挪**：批里那几条弹幕彼此的间隔不变，只是整体晚一点开始飞 —— 这样它们
 * 是一条条飞进来的，不会挤在画面右边上叠成一团。
 *
 * 挪多少：晚到超过 [LATE_MS] 才算晚到。早到的（数据在手时它还没到点）一根手指都不用动。
 */
object DanmakuAnchors {
    /**
     * 晚到多少毫秒才算晚到。
     *
     * 零 —— 内容时间**已经过去**就算晚到。
     *
     * 原来这里是八百毫秒：晚到不足八百毫秒的算「没晚到」，按内容时间照飞。听着像小事，实际不是：
     * 弹幕全程八秒，八百毫秒就是十分之一 —— 这么飞过去，四个字那条一露面整条已经摆在画面右边，
     * 看着就是「整条突然出现在右边」，而不是第一个字贴在右边缘慢慢飘。
     * 2026-11-01 实测（探针量「第一次露脸时已经飞了多久 / 落在屏宽百分之几」）：晚到四百三十四
     * 毫秒的那条，一露面就落在屏宽的百分之九十三 —— 整条都在画面里，正是用户说的那个样子。
     */
    const val LATE_MS=0L
    /**
     * 晚到超过这么多就算「错过了」，不往回捞。
     *
     * 错过了还捞回来是错的：那句话（那一行画面）早就过去了，把它的弹幕补放出来，观众看到的是
     * 一条跟眼前画面无关的弹幕 —— 比没有弹幕更糟。差几秒是「网络慢了点」，差几分钟是「压根不是
     * 这一段的内容」。
     */
    const val MISSED_MS=15_000L

    /**
     * 把一批刚到的弹幕里「已经晚到」的那些重新锚定。
     *
     * @param items 这一批新到的弹幕。
     * @param nowMs 数据到手时的播放位置（毫秒，视频时间轴）。
     * @param lateMs 晚到多少算晚到。
     * @param missedMs 晚到多少就算错过了（不再捞）。
     * @return 入场时间被挪过的副本；没晚到的原样返回。
     */
    fun reanchor(
        items: List<Danmaku>,
        nowMs: Long,
        lateMs: Long = LATE_MS,
        missedMs: Long = MISSED_MS
    ): List<Danmaku> {
        if(items.isEmpty()) return items
        // 到点才该飞的那些：内容时间还没到的，一条都不动。
        val limit=nowMs-lateMs
        // 错过太久的也不动：那一段的内容已经过去了，补放出来是错的。
        val floor=nowMs-missedMs
        // 挪过的（入场时间已经跟内容时间不一样）不再挪第二次 —— 换集重来时同一条弹幕可能再出现一次。
        val late=items.filter { it.enterAtMs==it.offsetMs && it.offsetMs in floor until limit }
        if(late.isEmpty()) return items
        // 整批一起挪：最早的那条**从现在**开始飞，其余按原来的间隔跟在后面。
        //
        // 挪到「现在减去晚到阈值」是不行的 —— 那样它们一露面就已经飞了那么久，整条文字摆在画面右边，
        // 用户看着就是凭空冒出来（2026-11-01 实测：落在屏宽百分之八十八）。
        val delta=nowMs-late.minOf { it.offsetMs }
        return items.map {
            if(it.enterAtMs==it.offsetMs && it.offsetMs in floor until limit) it.copy(enterAtMs=it.offsetMs+delta) else it
        }
    }
}
