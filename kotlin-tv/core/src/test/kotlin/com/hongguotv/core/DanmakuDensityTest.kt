// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同屏密度。
 *
 * 弹幕一多，屏幕上就是一片白字 —— 实测一集 15 分钟 649 条，最密的十秒 153 条，同屏最高 36 条，
 * 铺在 7 行轨道上，画面直接被糊住（用户说的「弹幕太多太挡屏幕」）。
 *
 * 这里测的就是：同屏条数有上限；满员时后来的弹幕晚点飘进来而不是硬塞；等不到位置就丢。
 */
class DanmakuDensityTest {
    private val lifetime=8_000L
    /**
     * 100px 宽的普通弹幕在 1280 宽的屏幕上飞完全程要多久。
     *
     * 传了宽度之后每条弹幕都有自己的时长（`DanmakuTiming`），不再是那个统一的八秒 ——
     * 离场时刻要按这个算，所以满员时「等一条走完」的入场时刻也是这个。
     */
    private val life=DanmakuTiming.lifetimeMs(100f,1280)
    private fun at(ms: Long, text: String = "x")=Danmaku(text,ms,0)

    /**
     * 每一处构造都传宽度，让它走 app 层真正走的那条路径（已量宽度 → 尾随入场）。
     *
     * 不传宽度会退回「整段独占」（轨道要等上一条整条飞完才空），那样同屏条数由轨道数（7）决定，
     * 密度档位（12）就守不住了 —— 而密度上限本来就是给用户选的旋钮。
     */
    private fun timeline(items: List<Danmaku>, trackCount: Int, maxOnScreen: Int = DanmakuDensity.MEDIUM.maxOnScreen) =
        DanmakuTimeline(items,trackCount,lifetimeMs=lifetime,maxOnScreen=maxOnScreen,
            bandWidth=1280,textWidthOf={ 100f })

    /**
     * 最要紧的一条：不管来多少条，屏幕上同时飘的永远不超过上限。
     *
     * 三十六条一起涌进来，上限十二，屏幕上就只该有十二条。
     */
    @Test fun theScreenNeverHoldsMoreThanTheLimit() {
        val items=(0 until 36).map { at(0L,"挤进来的 $it") }
        val timeline=timeline(items,trackCount=7,maxOnScreen=12)
        assertEquals(12,timeline.placed.size)
        // 不只是入场那一刻：整段时间里都得守住这个上限。
        for(position in 0L..20_000L step 250L) {
            val visible=timeline.visibleAt(position)
            assertTrue("位置 $position 上有 ${visible.size} 条，超了上限",visible.size<=12)
        }
    }

    /** 弹幕不多的时候，一条都不能少、也不能被挪 —— 密度上限不该影响正常的弹幕。 */
    @Test fun aQuietSceneKeepsEveryOne() {
        val items=(0 until 5).map { at(it*1_000L,"安静的 $it") }
        val timeline=timeline(items,trackCount=7,maxOnScreen=12)
        assertEquals(5,timeline.placed.size)
        // 入场时间一根手指都没动。
        assertEquals(listOf(0L,1_000L,2_000L,3_000L,4_000L),timeline.placed.map { it.danmaku.enterAtMs })
    }

    /**
     * 满员的时候，后来的那条不是硬塞进来，而是**晚点飘进来**。
     *
     * 场上那两条马上就要走完了（一条走完、一条稍晚走完），所以第三条等一下就能进来。
     */
    @Test fun aCrowdedOneIsHeldBackInsteadOfBeingDropped() {
        val items=listOf(at(0L,"先走的"),at(100L,"后走的"),at(7_000L,"挤不下的"))
        val timeline=timeline(items,trackCount=7,maxOnScreen=2)
        assertEquals(3,timeline.placed.size)
        // 等到第一条走完才进场（走完时刻按它自己的时长算，不是那个统一的八秒）。
        assertEquals(life,timeline.placed.last { it.danmaku.text=="挤不下的" }.danmaku.enterAtMs)
        // 关键是它从右边飞进来，不是一露面就摆在画面中间。
        assertEquals(0L,timeline.visibleAt(life).first { it.danmaku.text=="挤不下的" }.elapsedMs)
    }

    /**
     * 等不到位置就丢。
     *
     * 场上那两条要八秒才走完 —— 等那么久再飘出来，这条弹幕跟眼前画面对不上，
     * 比没有弹幕更糟（跟「错过太久的弹幕不往回捞」是同一个道理）。
     */
    @Test fun oneThatWouldWaitTooLongIsDropped() {
        val items=listOf(at(0L,"先走的"),at(100L,"后走的"),at(200L,"等不及的"))
        val timeline=timeline(items,trackCount=7,maxOnScreen=2)
        assertEquals(2,timeline.placed.size)
        assertTrue(timeline.placed.none { it.danmaku.text=="等不及的" })
    }

    /**
     * 延后最多两秒：晚一点是「网络慢了点」，观众还等得起。
     *
     * 场上那两条一条零点进场、一条一百毫秒时进场。第三条六千一百时来 ——
     * 等到第一条走完才空出位置，晚了不到两秒，能等。
     */
    @Test fun theWaitIsCappedAtTwoSeconds() {
        val items=listOf(at(0L,"a"),at(100L,"b"),at(6_100L,"c"))
        val timeline=timeline(items,trackCount=7,maxOnScreen=2)
        assertEquals(3,timeline.placed.size)
        assertEquals(life,timeline.placed.last { it.danmaku.text=="c" }.danmaku.enterAtMs)
    }

    /** 差一点点就不行：多等了一百八十六毫秒就超过两秒上限，丢。 */
    @Test fun aWaitJustOverTwoSecondsIsDropped() {
        val items=listOf(at(0L,"a"),at(100L,"b"),at(5_900L,"c"))
        val timeline=timeline(items,trackCount=7,maxOnScreen=2)
        assertEquals(2,timeline.placed.size)
        assertTrue(timeline.placed.none { it.danmaku.text=="c" })
    }

    /** 没写上限时用的是「中」档：十二条。 */
    @Test fun theDefaultLimitIsTheMediumOne() {
        val items=(0 until 30).map { at(0L,"挤进来的 $it") }
        val timeline=timeline(items,trackCount=7)
        assertEquals(DanmakuDensity.MEDIUM.maxOnScreen,timeline.placed.size)
    }

    /** 档位存的是名字：旧记录、旧备份里没有这个字段，按「中」算。 */
    @Test fun anUnknownStoredDensityFallsBackToTheDefault() {
        assertEquals(DanmakuDensity.MEDIUM,DanmakuDensity.fromStored(null))
        assertEquals(DanmakuDensity.MEDIUM,DanmakuDensity.fromStored("别的"))
        assertEquals(DanmakuDensity.LOW,DanmakuDensity.fromStored("LOW"))
    }

    /** 透明度同理：旧记录按「半透明」算 —— 现在的默认就是半透明。 */
    @Test fun anUnknownStoredAlphaFallsBackToTheDefault() {
        assertEquals(DanmakuAlpha.HALF,DanmakuAlpha.fromStored(null))
        assertEquals(DanmakuAlpha.HALF,DanmakuAlpha.fromStored("别的"))
        assertEquals(DanmakuAlpha.LIGHT,DanmakuAlpha.fromStored("LIGHT"))
    }

    /**
     * 描边不能淡到看不清。
     *
     * 电视上的画面五花八门，白字全靠那道黑边才看得清 —— 边淡了字就真的糊在画面里了。
     * 「不透明」那档填充本来就是实的，不用靠边撑；淡的那两档，边必须比填充更实。
     */
    @Test fun theEdgeStaysReadableWhenTheFillFades() {
        for(alpha in DanmakuAlpha.entries) assertTrue(alpha.label,alpha.edge>=160)
        assertTrue(DanmakuAlpha.HALF.edge>DanmakuAlpha.HALF.fill)
        assertTrue(DanmakuAlpha.LIGHT.edge>DanmakuAlpha.LIGHT.fill)
    }
}
