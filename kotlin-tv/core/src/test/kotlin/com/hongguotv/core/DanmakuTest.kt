// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕的两件核心事：平台回的东西怎么读，弹幕怎么排到轨道上。
 *
 * 断言用的都是实测拿到的真实结构（2026-10-21，`Probe --danmu`）：
 * `data_list[].comment.common.content.text` 是正文，`comment.expand.offset_time` 是**毫秒**偏移，
 * `comment.stat.digg_count` 是点赞数。
 */
class DanmakuTest {
    /** 一段平台真实回包的形状，截两条。 */
    private val payload="""
        {"BaseResp":{"StatusCode":0,"StatusMessage":"success"},"code":0,"data":{
          "common_list_info":{"cursor":"{\"start_offset_time\":0,\"end_offset_time\":30000}","has_more":false,"total":85},
          "data_list":[
            {"comment":{"comment_id":"1","common":{"content":{"text":"哈哈哈"}},"expand":{"offset_time":0},"stat":{"digg_count":24}}},
            {"comment":{"comment_id":"2","common":{"content":{"text":"太快了"}},"expand":{"offset_time":203},"stat":{"digg_count":12}}}
          ],
          "extra":{"next_query_danmaku_list_time":30000}}}
    """.trimIndent()

    @Test fun theTextTheOffsetAndTheLikesAreRead() {
        val window=parseDanmakuWindow(payload,0L)
        assertEquals(2,window.items.size)
        assertEquals("哈哈哈",window.items[0].text)
        assertEquals(24,window.items[0].digg)
        // 毫秒。平台给的就是毫秒，当成秒用会让弹幕整体偏 1000 倍。
        assertEquals(0L,window.items[0].offsetMs)
        assertEquals(203L,window.items[1].offsetMs)
    }

    @Test fun theWindowSaysWhereTheNextOneStarts() {
        assertEquals(30_000L,parseDanmakuWindow(payload,0L).nextStartMs)
    }

    /** 平台有时给 0（那就是最后一窗），有时根本不给这个字段。 */
    @Test fun theNextWindowIsPushedForwardWhenThePlatformDoesNotSay() {
        val noExtra="""{"code":0,"data":{"data_list":[]}}"""
        assertEquals(30_000L,parseDanmakuWindow(noExtra,0L).nextStartMs)
        assertEquals(60_000L,parseDanmakuWindow(noExtra,30_000L).nextStartMs)
    }

    /**
     * 被拒的时候是 HTTP 200 加一个 `Code 110001` —— 只看状态码会把「被拒」当成「成功但没弹幕」。
     */
    @Test(expected=java.io.IOException::class)
    fun aRefusedRequestIsNotAnEmptyWindow() {
        parseDanmakuWindow("""{"Code":110001,"Message":"未知异常","BaseResp":{"StatusCode":110001}}""",0L)
    }

    @Test fun aRowWithoutTextIsSkipped() {
        val blank="""{"code":0,"data":{"data_list":[{"comment":{"common":{"content":{"text":"  "}},"expand":{"offset_time":10}}}]}}"""
        assertEquals(0,parseDanmakuWindow(blank,0L).items.size)
    }

    @Test fun theRowsComeBackInTimeOrder() {
        val shuffled="""{"code":0,"data":{"data_list":[
          {"comment":{"common":{"content":{"text":"晚"}},"expand":{"offset_time":900}}},
          {"comment":{"common":{"content":{"text":"早"}},"expand":{"offset_time":100}}}]}}"""
        val items=parseDanmakuWindow(shuffled,0L).items
        assertEquals("早",items[0].text)
        assertEquals("晚",items[1].text)
    }

    // ---- 轨道 ----

    private fun at(ms: Long)=Danmaku("x",ms,0)

    @Test fun twoDanmakuAtTheSameMomentGetDifferentTracks() {
        // 同一时刻的两条弹幕走同一条轨道就会叠字。
        val timeline=DanmakuTimeline(listOf(at(0),at(100)),trackCount=4,lifetimeMs=8_000L)
        val tracks=timeline.placed.map { it.track }
        assertEquals(2,tracks.toSet().size)
    }

    @Test fun danmakuThatDoNotOverlapShareATrack() {
        // 上一条 8 秒就走了，下一条 8 秒后才来 —— 它们可以走同一条。
        val timeline=DanmakuTimeline(listOf(at(0),at(8_000)),trackCount=1,lifetimeMs=8_000L)
        assertEquals(0,timeline.placed[0].track)
        assertEquals(0,timeline.placed[1].track)
    }

    @Test fun onlyTheDanmakuAliveAtThatMomentAreVisible() {
        val timeline=DanmakuTimeline(listOf(at(0),at(20_000)),trackCount=4,lifetimeMs=8_000L)
        assertEquals(1,timeline.visibleAt(1_000L).size)
        assertEquals(0,timeline.visibleAt(10_000L).size)
        assertEquals(1,timeline.visibleAt(21_000L).size)
    }

    @Test fun aDanmakuDiesExactlyWhenItsTimeIsUp() {
        val timeline=DanmakuTimeline(listOf(at(0)),trackCount=2,lifetimeMs=8_000L)
        assertEquals(1,timeline.visibleAt(7_999L).size)
        assertEquals(0,timeline.visibleAt(8_000L).size)
    }

    /**
     * 跳过去之后，画面的位置变了，看得见的也得跟着变。
     *
     * 这是「用播放位置算、不用墙钟算」的理由：墙钟不知道用户跳到了哪。
     */
    @Test fun seekingMovesTheDanmakuWithThePlayhead() {
        val timeline=DanmakuTimeline(listOf(at(0),at(20_000),at(40_000)),trackCount=4,lifetimeMs=8_000L)
        assertEquals("跳到 20 秒该只剩那一条",1,timeline.visibleAt(20_500L).size)
        assertEquals(20_000L,timeline.visibleAt(20_500L)[0].danmaku.offsetMs)
        assertEquals(1,timeline.visibleAt(40_100L).size)
    }

    @Test fun theVisibleRowsCarryTheirElapsedTime() {
        val timeline=DanmakuTimeline(listOf(at(1_000)),trackCount=2,lifetimeMs=8_000L)
        assertEquals(500L,timeline.visibleAt(1_500L)[0].elapsedMs)
    }

    /** 弹幕很多的时候，可见的那一段不能把整集都扫一遍。 */
    @Test fun theVisibleRowsAreOnlyTheOnesInTheWindow() {
        val many=(0 until 500).map { at(it*100L) }
        val timeline=DanmakuTimeline(many,trackCount=4,lifetimeMs=8_000L)
        val visible=timeline.visibleAt(30_000L)
        assertTrue("30 秒那一处最多八十来条",visible.size<=90)
        assertEquals(30_000L,visible.last().danmaku.offsetMs)
    }

    // ---- 倍速 ----

    /**
     * 2 倍速时弹幕不该跟着飞两倍快。
     *
     * 弹幕出现在哪一刻仍按视频时间轴（跟画面对得上），但它**飘过去的时间按真实时间算**：
     * 不然 2 倍速下一句话还没看完就出屏了，倍速就白看了。
     */
    @Test fun atDoubleSpeedTheDanmakuStillTakesItsTimeToCross() {
        val timeline=DanmakuTimeline(listOf(at(10_000)),trackCount=2,lifetimeMs=8_000L)
        // 视频第 10 秒这条弹幕出现。2 倍速下真实经过 5 秒时，播放位置到了 10 秒。
        val atTenSeconds=timeline.visibleAt(10_000L,2f)
        assertEquals(1,atTenSeconds.size)
        assertEquals("刚出现时真实经过的时间是 0",0L,atTenSeconds[0].elapsedMs)
        // 真实经过 4 秒（视频第 18 秒）：横着走了 4 秒。
        assertEquals(4_000L,timeline.visibleAt(18_000L,2f)[0].elapsedMs)
    }

    @Test fun atDoubleSpeedTheDanmakuLivesLongerOnScreen() {
        val timeline=DanmakuTimeline(listOf(at(10_000)),trackCount=2,lifetimeMs=8_000L)
        // 真实 8 秒 = 视频 16 秒，所以它到视频第 26 秒才走完。
        assertTrue("8 秒真实时间里它还在",timeline.visibleAt(25_000L,2f).isNotEmpty())
        assertTrue("8 秒真实时间走完了它就没了",timeline.visibleAt(26_000L,2f).isEmpty())
    }

    /** 1 倍速时行为不能变 —— 免得为了倍速把正常的也改了。 */
    @Test fun atNormalSpeedNothingChanges() {
        val timeline=DanmakuTimeline(listOf(at(0)),trackCount=2,lifetimeMs=8_000L)
        assertEquals(1_000L,timeline.visibleAt(1_000L,1f)[0].elapsedMs)
        assertTrue(timeline.visibleAt(7_999L,1f).isNotEmpty())
        assertTrue(timeline.visibleAt(8_000L,1f).isEmpty())
    }
}
