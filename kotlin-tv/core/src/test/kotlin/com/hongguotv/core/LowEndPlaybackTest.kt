package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LowEndPlaybackTest {
    private class FakeCall: Call {
        var started=false
        @Volatile var cancelled=false
        override fun request()=Request.Builder().url("https://example.invalid/video").build()
        override fun execute(): Response {
            started=true
            if(cancelled) throw IOException("cancelled")
            return Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("data".toResponseBody()).build()
        }
        override fun enqueue(responseCallback: Callback)=error("not used")
        override fun cancel() { cancelled=true }
        override fun isExecuted()=started
        override fun isCanceled()=cancelled
        override fun timeout()=Timeout.NONE
        override fun clone(): Call=FakeCall()
    }
    @Test fun cancelledNavigationDoesNotStartQueuedNetworkWork() {
        val scope=RequestScope(); val call=FakeCall(); scope.cancel()
        assertThrows(IOException::class.java) { scope.run { RequestScope.execute(call) { it.body!!.string() } } }
        assertFalse(call.started)
    }
    @Test fun cancelStillOwnsCallWhileResponseBodyIsBeingConsumed() {
        val scope=RequestScope(); val call=FakeCall(); val reading=CountDownLatch(1); val finish=CountDownLatch(1)
        val worker=Thread { scope.run { RequestScope.execute(call) { reading.countDown(); finish.await(2,TimeUnit.SECONDS) } } }
        worker.start()
        try {
            assertTrue(reading.await(2,TimeUnit.SECONDS)); scope.cancel(); assertTrue(call.cancelled)
        } finally { finish.countDown(); worker.join(2000) }
        assertFalse(worker.isAlive)
    }
    @Test fun cancellingOldPageDoesNotCancelAnotherPagesOrCompletedCalls() {
        val old=RequestScope(); val current=RequestScope(); val first=FakeCall(); val next=FakeCall()
        assertEquals("data",old.run { RequestScope.execute(first) { it.body!!.string() } })
        old.cancel(); assertFalse(first.cancelled)
        assertEquals("data",current.run { RequestScope.execute(next) { it.body!!.string() } })
        assertFalse(next.cancelled)
    }
    @Test fun prefetchStartsAsSoonAsTheCurrentEpisodeSettles() {
        // 原来是「本集还剩 30 秒」才动手。取签名地址加预热 1MB 在慢机器上跑不完，
        // 下一集开始时往往还没准备好 —— 每次自动连播都要先等一下。现在本集一播稳就准备。
        assertFalse("刚开始播不算稳", PrefetchPolicy.shouldPrepare(5_000,30_000))
        assertTrue("播了十秒就该准备了", PrefetchPolicy.shouldPrepare(10_000,30_000))
        // 但本集得先缓冲起来，否则预取抢的是画面的带宽。
        assertFalse("本集没缓冲就不能抢带宽", PrefetchPolicy.shouldPrepare(10_000,1_000))
        assertTrue(PrefetchPolicy.shouldPrepare(10_000,PrefetchPolicy.BUFFER_AHEAD_MS))
        // 准备好的下一集要能等过本集播完：短剧的集子一两分钟。
        assertTrue("准备好的下一集得比一集长", PrefetchPolicy.TTL_MS > 3*60_000L)
    }
    @Test fun thePrefetchWaitsForThePlayersOwnBufferToFill() {
        // 播放器自己的缓冲目标是 15~30 秒（MainActivity 里那个 DefaultLoadControl）。
        // 预取在首帧后八九秒就发起，那时播放器还在把自己的缓冲填上去 —— 预取那 1MB 抢的就是
        // 这段填充期。门槛低于播放器自己的目标就等于没有门槛：它挡不住任何东西。
        assertTrue("门槛要对齐播放器的最小缓冲", PrefetchPolicy.BUFFER_AHEAD_MS >= 15_000L)
        assertFalse("缓冲没到位就不预取", PrefetchPolicy.shouldPrepare(20_000,PrefetchPolicy.BUFFER_AHEAD_MS-1))
        assertTrue("缓冲到位了才预取", PrefetchPolicy.shouldPrepare(20_000,PrefetchPolicy.BUFFER_AHEAD_MS))
    }
    @Test fun theTransportThePreparationBorrowsIsThePlaybackTransport() {
        // 准备下一集用的客户端，命中之后就是这一集自己的传输客户端。所以它必须带得住整集下载：
        // 播放器按 512 KiB 一段拉，每一段都是一次带 callTimeout 的调用。准备自己的预算另有
        // 地方管（PrefetchPolicy.BUDGET_MS），不该靠把这里的超时调紧来给准备限时。
        val client=ContentRepository.playbackHttpClient()
        assertEquals(15_000,client.connectTimeoutMillis)
        assertEquals(30_000,client.readTimeoutMillis)
        assertEquals(45_000,client.callTimeoutMillis)
    }
    @Test fun onePreparationHasADeadlineOfItsOwn() {
        // 预取不能一直占着工作线程和上行。预算到了就放弃，而且不再试第二第三次 —— 链路既然
        // 掏不出这 1MB，再试就是拿本集的带宽去换。
        assertTrue("准备要有自己的期限", PrefetchPolicy.BUDGET_MS in 1_000L..120_000L)
        assertTrue("准备不该比准备好的东西还活得久", PrefetchPolicy.BUDGET_MS < PrefetchPolicy.TTL_MS)
    }
    @Test fun expiredPrefetchCanBeReplacedAfterLongPauseWithoutReturningOldResource() {
        var now=0L
        class Resource: Closeable { var closed=false; override fun close() { closed=true } }
        val slot=PreparedSlot<Resource>({now}); val old=Resource()
        slot.complete(slot.begin("next"),"next",old); assertTrue(slot.isFresh("next"))
        now=120_001; assertFalse(slot.isFresh("next"))
        val fresh=Resource(); slot.complete(slot.begin("next"),"next",fresh)
        assertTrue(old.closed); assertSame(fresh,slot.take("next")); assertFalse(fresh.closed)
    }
}
