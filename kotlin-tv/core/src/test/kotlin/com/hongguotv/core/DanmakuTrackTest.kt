// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨道怎么排。
 *
 * 三件事：
 * 1. 尾随入场 —— 上一条的**尾巴**离开右缘，这条轨道就能接下一条（水平错开，不叠字）。
 * 2. 轨道起点轮转 —— 不再永远从第 0 条轨道开始找，否则弹幕全堆在最上面两行。
 * 3. 每条弹幕有自己的时长 —— 字越宽飘得越久，轨道被占得也越久。
 */
class DanmakuTrackTest {
    private val band=1280
    private val short=100f          // 普通弹幕的宽度
    private val wide=520f           // 长句的宽度
    private fun at(ms: Long,text: String="x")=Danmaku(text,ms,0)

    private fun timeline(
        items: List<Danmaku>,
        trackCount: Int = 7,
        maxOnScreen: Int = DanmakuDensity.MEDIUM.maxOnScreen,
        width: (Danmaku) -> Float = { short }
    )=DanmakuTimeline(items,trackCount,lifetimeMs=8_000L,maxOnScreen=maxOnScreen,
        bandWidth=band,textWidthOf=width)

    /**
     * 最要紧的一条：同一条轨道上，后一条不能跟前一条撞上。
     *
     * 撞上的判据是「后一条入场时，前一条的尾巴已经离开右缘」。
     */
    @Test fun twoDanmakuOnOneTrackNeverTouch() {
        val items=(0 until 20).map { at(it*300L,"第 $it 条") }
        val tl=timeline(items)
        val byTrack=tl.placed.groupBy { it.track }
        for((track,rows) in byTrack) {
            val ordered=rows.sortedBy { it.danmaku.enterAtMs }
            for(i in 1 until ordered.size) {
                val prev=ordered[i-1]
                val next=ordered[i]
                val prevClear=prev.danmaku.enterAtMs+DanmakuTiming.tailClearMs(short,band)
                assertTrue("轨道 $track 上第 $i 条撞上了前一条：${prev.danmaku.text} / ${next.danmaku.text}",
                    next.danmaku.enterAtMs>=prevClear)
            }
        }
    }

    /** 轨道起点轮转：弹幕不能全落在第 0 条轨道上。 */
    @Test fun theDanmakuSpreadOverTheTracks() {
        val items=(0 until 7).map { at(it*100L,"第 $it 条") }
        val tl=timeline(items)
        assertEquals("七条轨道都要用上",7,tl.placed.map { it.track }.toSet().size)
    }

    /** 字越宽，飘得越久。 */
    @Test fun aWiderCommentLivesLonger() {
        val tl=timeline(listOf(at(0,"短"),at(20_000,"长")),width={ if (it.text=="长") wide else short })
        val shortOne=tl.placed.first { it.danmaku.text=="短" }
        val longOne=tl.placed.first { it.danmaku.text=="长" }
        assertTrue(longOne.lifetimeMs>shortOne.lifetimeMs)
        assertEquals(DanmakuTiming.lifetimeMs(short,band),shortOne.lifetimeMs)
        assertEquals(DanmakuTiming.lifetimeMs(wide,band),longOne.lifetimeMs)
    }

    /**
     * 尾随入场之后，同一条轨道上可以同时有两条弹幕 —— 但它们是水平错开的。
     *
     * 这是尾随入场的意义：轨道不再是「一条弹幕飞完才空」，密度档位才真的有用
     * （以前轨道数 7 把同屏条数卡在 7，密度「多」形同虚设）。
     */
    @Test fun aTrackCanHostTwoDanmakuAtOnce() {
        val items=listOf(at(0,"第一条"),at(200,"第二条"))
        val tl=timeline(items,trackCount=1)
        assertEquals(2,tl.placed.size)
        assertEquals("两条得在同一条轨道上",1,tl.placed.map { it.track }.toSet().size)
        // 第二条晚一点入场：它进轨道时第一条的尾巴刚好离开右缘。
        assertEquals(DanmakuTiming.tailClearMs(short,band),tl.placed[1].danmaku.enterAtMs)
    }

    /** 同屏条数守得住上限：这就是「挡屏幕的是条数」的兜底。 */
    @Test fun theScreenNeverHoldsMoreThanTheLimit() {
        val items=(0 until 40).map { at(0L,"挤进来的 $it") }
        val tl=timeline(items,trackCount=7,maxOnScreen=12)
        for(position in 0L..20_000L step 250L) {
            val visible=tl.visibleAt(position)
            assertTrue("位置 $position 上有 ${visible.size} 条，超了上限",visible.size<=12)
        }
    }

    /**
     * 量不到宽度时退回「整段独占」：轨道要等上一条整条飞完才放下一条。
     *
     * 这条兼容路径只有探针（`Probe.kt`）和不传宽度的既有单测在走，app 层总会量宽度。
     * 宁可这条路径上少飘几条，也不能让它同轨叠字 —— 按 8% 估出来的清场时刻会低估
     * 真实的尾巴离场时刻，轨道提前释放就会追上还在屏上的长句。
     */
    @Test fun withoutWidthsTheTrackIsHeldForTheWholeLife() {
        val items=listOf(at(0,"第一条"),at(200,"第二条"))
        val tl=DanmakuTimeline(items,trackCount=1,lifetimeMs=8_000L,maxOnScreen=12)
        assertEquals("等不到空位就丢（要等八秒，晚了两秒上限）",1,tl.placed.size)
    }

    /** 延迟飘进来的弹幕是从右边飞进来的，不是一露面就摆在画面中间。 */
    @Test fun aDelayedOneStillEntersFromTheRightEdge() {
        val items=(0 until 40).map { at(0L,"挤进来的 $it") }
        val tl=timeline(items,trackCount=7,maxOnScreen=12)
        val delayed=tl.placed.first { it.danmaku.enterAtMs>0L }
        assertTrue("被延后的要从右边飞进来",tl.visibleAt(delayed.danmaku.enterAtMs)
            .first { it.danmaku==delayed.danmaku }.elapsedMs==0L)
    }

    /**
     * `Visible` 要带着自己的时长 —— app 层靠它算弹幕的位置（统一 8 秒算不出长句的位置）。
     *
     * 同一起点、同一时刻：窄的已经飞完了，宽的还在，而且它带的是宽的那条自己的时长。
     */
    @Test fun theVisibleOneCarriesItsOwnLifetime() {
        val tl=timeline(listOf(at(0,"窄"),at(0,"宽")),width={ if (it.text=="宽") wide else short })
        val visible=tl.visibleAt(9_000L)
        assertEquals("窄的已经飞完，宽的还在",1,visible.size)
        assertEquals("宽",visible[0].danmaku.text)
        assertEquals(DanmakuTiming.lifetimeMs(wide,band),visible[0].lifetimeMs)
    }

    /**
     * 同屏上限不再被轨道数卡住 —— 这正是尾随入场存在的理由。
     *
     * 七条轨道（比上限 12 少）照样能放到 12 条：以前一条轨道要等上一条整条飞完，
     * 轨道数（7）直接成了同屏条数的硬顶，密度「多」（24）在默认行数下形同虚设。
     */
    @Test fun theOnScreenLimitIsNotCappedByTheTrackCount() {
        val items=(0 until 40).map { at(0L,"挤进来的 $it") }
        val tl=timeline(items,trackCount=7,maxOnScreen=12)
        assertEquals("七条轨道也能放到上限十二条",12,tl.placed.size)
        var peak=0
        for(position in 0L..2_000L step 50L) peak=maxOf(peak,tl.visibleAt(position).size)
        assertEquals("同屏峰值能到上限",12,peak)
    }

    /**
     * 轨道比上限还少时，同屏条数也不再等于轨道数。
     *
     * 两条轨道上同时飘着的不止两条 —— 轨道是「横向错开」，不是「一条弹幕独占一条轨道」。
     */
    @Test fun evenTwoTracksHoldMoreThanTwoAtOnce() {
        val items=(0 until 40).map { at(0L,"挤进来的 $it") }
        val tl=timeline(items,trackCount=2,maxOnScreen=12)
        assertTrue("两条轨道上同时飘着的不止两条（实际 ${tl.placed.size} 条）",tl.placed.size>2)
    }
}
