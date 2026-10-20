package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hot list cache is written and read at the same time.
 *
 * A list is fetched on a worker thread and handed to the main thread, and the main thread
 * reads the list it already holds while that fetch is still running. The map behind the cache
 * was a plain LinkedHashMap, which is not safe for that.
 */
class HotRankCacheConcurrencyTest {
    private fun list(type: ContentType, id: String)=HotRank.combine(
        listOf(HotRank.Board(Route.REAL_DRAMA,listOf(Series(id,"剧 $id","https://example.com/a.jpg","","","")),mapOf(id to RankPosition(1,"100万热度")),1,"10月20日已更新")),type)

    @Test fun `the cache holds every list that was put into it while it is read`() {
        val cache=HotRank.Cache()
        val workers=8; val each=200
        val start=CountDownLatch(1); val done=CountDownLatch(workers)
        val failures=java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads=(0 until workers).map { worker ->
            Thread {
                try {
                    start.await()
                    repeat(each) { i ->
                        val type=if(i%2==0) ContentType.SHORT else ContentType.COMIC
                        cache.put(list(type,"$worker-$i"))
                        cache.get(type)
                        cache.fresh(type,System.currentTimeMillis())
                    }
                } catch(e: Throwable) { failures+=e } finally { done.countDown() }
            }
        }
        threads.forEach { it.start() }; start.countDown()
        assertTrue(done.await(60,TimeUnit.SECONDS))
        assertEquals("no worker failed",emptyList<Throwable>(),failures.toList())
        // Both shelves kept a list, and every list that was put in can be read back.
        assertNotNull(cache.get(ContentType.SHORT)); assertNotNull(cache.get(ContentType.COMIC))
        repeat(each) { i ->
            val type=if(i%2==0) ContentType.SHORT else ContentType.COMIC
            assertNotNull(cache.get(type))
        }
    }
}
