// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/**
 * 一条弹幕。
 *
 * @param text 文本。平台把 `[昵称]` 之类的标记拼在正文里，那不是用户输入的一部分。
 * @param offsetMs 相对本集开头的偏移，**毫秒**。平台给的就是毫秒，当成秒用会整体偏 1000 倍。
 * @param digg 点赞数。
 */
data class Danmaku(val text: String, val offsetMs: Long, val digg: Int)

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
 * @param items 这一集的弹幕。
 * @param trackCount 屏幕上有几条轨道。
 * @param lifetimeMs 一条弹幕从右边走到左边要多久。
 */
class DanmakuTimeline(items: List<Danmaku>, val trackCount: Int, val lifetimeMs: Long) {
    /** 一条弹幕占的位置。 */
    data class Placed(val danmaku: Danmaku, val track: Int)

    /** 某一刻还看得见的弹幕，以及它已经走了多久。 */
    data class Visible(val danmaku: Danmaku, val track: Int, val elapsedMs: Long, val index: Int)

    /** 每条弹幕的轨道，按时间先后排好。 */
    val placed: List<Placed> = buildList {
        // 每条轨道记住「上一条什么时候走完」，空闲的轨道就是走完时间 <= 下一条的起点。
        val freeAt=LongArray(maxOf(1,trackCount))
        for(item in items.sortedBy { it.offsetMs }) {
            val start=item.offsetMs
            var chosen=-1
            for(t in freeAt.indices) if(freeAt[t]<=start) { chosen=t; break }
            if(chosen<0) {
                // 全都还占着：挤最早走完的那一条，前一条会被覆盖。
                chosen=freeAt.indices.minByOrNull { freeAt[it] } ?: 0
            }
            freeAt[chosen]=start+lifetimeMs
            add(Placed(item,chosen))
        }
    }

    /** 每条弹幕的起点，单独存一份是为了每帧能二分，而不是每帧扫全表。 */
    private val offsets=LongArray(placed.size) { placed[it].danmaku.offsetMs }

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
            out+=Visible(p.danmaku,p.track,((positionMs-p.danmaku.offsetMs)/factor).toLong(),i)
        }
        return out
    }
}
