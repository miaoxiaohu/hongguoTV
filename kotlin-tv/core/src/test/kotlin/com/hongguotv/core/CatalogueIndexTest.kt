package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CatalogueIndexTest {
    private fun show(id: String, title: String, tags: String = "") = Series(id, title, "https://example.com/$id.jpg", "很长的简介不应进索引", "全 80 集", tags)

    private fun index(vararg rows: Series) = CatalogueIndex(rows.toList())

    @Test fun `an exact title outranks a prefix one, which outranks a mere mention`() {
        val result = index(
            show("1", "总裁归来：从天桥开始"),
            show("2", "总裁归来第二季"),
            show("3", "总裁归来"),
            show("4", "我的老婆居然是总裁")
        ).search("总裁归来")
        // 「谁让这个文盲修仙的」 must come before that show's other seasons: exact first, then by
        // length, so the one the user typed leads and its sequels stay reachable behind it.
        assertEquals(listOf("3", "2", "1"), result.map { it.id })
    }

    @Test fun `a tag hit still counts as a match`() {
        // 《雾锁情迷》 carries a 「总裁」 tag and does not contain the word in its title.
        val result = index(show("1", "雾锁情迷", "爱情 · 都市爱情 · 总裁")).search("总裁")
        assertEquals(listOf("1"), result.map { it.id })
        assertEquals(1, index(show("1", "雾锁情迷", "爱情 · 总裁")).count("总裁"))
    }

    @Test fun `a blank keyword matches nothing rather than the whole library`() {
        val library = index(show("1", "总裁归来"), show("2", "雾锁情迷"))
        assertTrue(library.search("").isEmpty())
        assertTrue(library.search("   ").isEmpty())
        assertEquals(0, library.count(" "))
    }

    @Test fun `punctuation and spacing do not decide a match`() {
        val result = index(show("1", "分手四年，傅机长失了控")).search("分手四年 傅机长失了控")
        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test fun `results are capped and duplicate ids collapse`() {
        val rows = (1..50).map { show(it.toString(), "总裁归来") }
        assertEquals(10, index(*rows.toTypedArray()).search("总裁归来", limit = 10).size)
        // The catalogue walk must not surface the same show twice if two routes ever overlap.
        assertEquals(1, index(show("1", "雾锁情迷"), show("1", "雾锁情迷")).size)
    }

    @Test fun `a term the site never carried matches nothing`() {
        assertTrue(index(show("1", "雾锁情迷"), show("2", "京夜诱温")).search("庆余年").isEmpty())
    }

    @Test fun `newest hands back the front of the catalogue for an empty search box`() {
        val library = index(show("1", "第一部"), show("2", "第二部"), show("3", "第三部"))
        assertEquals(listOf("1", "2"), library.newest(2).map { it.id })
        assertTrue(library.newest(0).isEmpty())
    }

    @Test fun `a mistyped character still finds the show`() {
        val (rows, relaxed) = index(show("1", "雾锁情迷"), show("2", "京夜诱温")).searchAllowingTypo("雾锁请迷")
        assertEquals(listOf("1"), rows.map { it.id })
        assertTrue(relaxed)
    }

    @Test fun `the typo fallback covers a dropped or an extra character too`() {
        // 一个字被吞掉，或者多打了一个字，都是同一种手误。
        val swallowed = index(show("1", "分手四年，傅机长失了控")).searchAllowingTypo("分手四年傅机长失控")
        assertEquals(listOf("1"), swallowed.first.map { it.id })
        val doubled = index(show("1", "分手四年，傅机长失了控")).searchAllowingTypo("分手四年傅机长失了了控")
        assertEquals(listOf("1"), doubled.first.map { it.id })
    }

    @Test fun `a literal hit is never displaced by the typo fallback`() {
        val (rows, relaxed) = index(show("1", "雾锁情迷"), show("2", "雾锁请迷真番")).searchAllowingTypo("雾锁情迷")
        assertEquals(listOf("1"), rows.map { it.id })
        assertFalse(relaxed)
    }

    @Test fun `short queries get no typo fallback at all`() {
        // 两三个字错一个字就是另一个词。给一个自信的错误答案，比给一个空页面糟得多。
        val (rows, relaxed) = index(show("1", "总裁归来"), show("2", "总裁回来")).searchAllowingTypo("总载")
        assertTrue(rows.isEmpty())
        assertFalse(relaxed)
        // 但四字及以上就该兜底：把「总裁归来」打成「总裁回来」，仍然找得到。
        val (rows4, relaxed4) = index(show("1", "总裁归来")).searchAllowingTypo("总裁回来")
        assertEquals(listOf("1"), rows4.map { it.id })
        assertTrue(relaxed4)
    }

    @Test fun `a season suffix is three edits away, not one`() {
        // 反过来测：把季号也打进去，差的是「第五季」三个字，远超一次编辑，不该被当错字拉进来。
        val (rows, relaxed) = index(show("1", "谁让这个文盲修仙的")).searchAllowingTypo("谁让这个文盲修仙的第五季")
        assertTrue(rows.isEmpty())
        assertFalse(relaxed)
    }

    @Test fun `two differing characters stay two edits apart`() {
        // 「总裁归来」写成「总裁回未」：回≠归 且 未≠来，是两处，不是错一个字。
        // 早期的手写距离在这里会给 1，于是把无关的剧报成了「相近剧名」。
        val (rows, relaxed) = index(show("1", "总裁归来")).searchAllowingTypo("总裁回未")
        assertTrue(rows.isEmpty())
        assertFalse(relaxed)
    }

    @Test fun `the index round-trips through disk without carrying descriptions`() {
        val rows = listOf(show("7694896136963755032", "雾锁情迷", "爱情 · 总裁"), show("7694896136963755033", "京夜诱温"))
        val decoded = CatalogueIndexDisk.decode(CatalogueIndexDisk.encode(rows, 1_000L), 2_000L)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.size)
        assertEquals("雾锁情迷", decoded.index.search("雾锁情迷").single().title)
        assertEquals("全 80 集", decoded.index.search("雾锁情迷").single().badge)
        // The detail page fetches the blurb on open; keeping 1600 of them would bloat the file.
        assertEquals("", decoded.index.search("雾锁情迷").single().description)
    }

    @Test fun `the walk bookmark survives disk so a slow box can carry on`() {
        val rows = listOf(show("1", "雾锁情迷"), show("2", "京夜诱温"))
        val json = CatalogueIndexDisk.encode(rows, mapOf(ContentType.SHORT to 12), setOf(ContentType.COMIC), 1_000L)
        val decoded = CatalogueIndexDisk.decode(json, 2_000L)!!
        assertEquals(12, decoded.cursor[ContentType.SHORT])
        assertEquals(setOf(ContentType.COMIC), decoded.done)
        assertEquals(false, decoded.complete)
        // A finished walk must not be re-requested on the next launch.
        assertEquals(true, CatalogueIndexDisk.decode(
            CatalogueIndexDisk.encode(rows, mapOf(ContentType.SHORT to 34), ContentType.entries.toSet(), 1_000L), 2_000L
        )!!.complete)
    }

    @Test fun `an index written before bookmarks existed still reads, and costs one extra walk`() {
        val legacy = """{"savedAt":1000,"count":1,"items":[{"i":"1","t":"雾锁情迷"}]}"""
        val decoded = CatalogueIndexDisk.decode(legacy, 2_000L)
        assertNotNull(decoded)
        assertEquals("没写断点的老索引应当从头再走", emptyMap<ContentType, Int>(), decoded!!.cursor)
        assertEquals(emptySet<ContentType>(), decoded.done)
    }

    @Test fun `a stale, corrupt or oversized index is dropped rather than surfaced`() {
        val fresh = CatalogueIndexDisk.encode(listOf(show("1", "雾锁情迷")), 1_000L)
        val week = 7 * 24 * 60 * 60 * 1000L
        assertNotNull(CatalogueIndexDisk.decode(fresh, 1_000L + week - 1))
        assertNull("过期索引不该被用", CatalogueIndexDisk.decode(fresh, 1_000L + week + 1))
        assertNull("损坏数据不该抛异常", CatalogueIndexDisk.decode("{not json", 1_000L))
        assertNull(CatalogueIndexDisk.decode("""{"savedAt":1000,"count":0,"items":[]}""", 1_000L))
        // A clock that went backwards must not read as infinitely fresh.
        assertNull(CatalogueIndexDisk.decode(fresh, 999L))
    }

    @Test fun `the builder walks every page, never repeats a show and can be stopped`() {
        val requested = mutableListOf<String>()
        val repo = repository { request ->
            requested += "${request.url.encodedPath}?${request.url.queryParameter("page")}"
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            // Ids must be digits: valid() drops anything else, which would empty the index.
            val prefix = if (slug.startsWith("comic")) "9" else "1"
            val rows = (1..3).map { i -> """{"series_id":"${prefix}${page}$i","series_name":"$slug p$page n$i"}""" }
            val pages = if (page >= 3) 3 else 9
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[${rows.joinToString(",")}],"pagination":{"totalPages":$pages}}}};</script>"""
        }
        val seen = mutableListOf<Int>()
        val walk = CatalogueBuilder(repo).build(onPage = { step -> seen += step.total })
        // Pages 1-3 per catalogue, 3 shows each; hasMore stops the walk after page three.
        assertEquals(18, walk.shows.size)
        assertEquals(listOf(3, 6, 9, 12, 15, 18), seen)
        assertEquals(walk.shows.map { it.id }.distinct().size, walk.shows.size)
        assertTrue(walk.shows.all { it.id.length in 3..4 })
        assertTrue("两套目录都走到了最后一页", walk.complete)
        // Page one is requested without a query string, later pages with it.
        assertEquals("/category/real-drama?null", requested[0])
        assertEquals("/category/real-drama?2", requested[1])
        assertEquals("/category/real-drama?3", requested[2])
        assertEquals("/category/comic-drama?null", requested[3])
    }

    @Test fun `the builder stops on demand and keeps what it already has`() {
        val repo = repository { request ->
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"$page","series_name":"第${page}页"}],"pagination":{"totalPages":9}}}};</script>"""
        }
        val walk = CatalogueBuilder(repo).build(isCancelled = { true })
        assertTrue("取消后不应再发请求", walk.shows.isEmpty() || walk.shows.size <= 1)
        assertFalse("被取消的走查不算完成", walk.complete)
    }

    @Test fun `a page that times out once is retried instead of lost`() {
        // The old walk gave up on the whole catalogue here. On the projector that capped the index
        // at whatever landed before the first WiFi hiccup - the same place, on every launch, forever.
        var calls = mutableMapOf<String, Int>()
        val repo = repository { request ->
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            calls.merge("$slug?$page", 1, Int::plus)
            if (slug.startsWith("real") && page == 2 && calls["$slug?$page"]!! < 3) throw IOException("timeout")
            val prefix = if (slug.startsWith("comic")) "9" else "1"
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"${prefix}${page}","series_name":"$slug p$page"}],"pagination":{"totalPages":3}}}};</script>"""
        }
        val walk = CatalogueBuilder(repo, retryPauseMillis = 0).build()
        assertTrue("重试成功就不该留下缺口", walk.gaps.isEmpty())
        assertEquals(6, walk.shows.size)
        assertEquals(3, calls["real-drama?2"])
        assertTrue(walk.complete)
    }

    @Test fun `a page that never answers is one gap, not the end of the catalogue`() {
        val repo = repository { request ->
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            if (slug.startsWith("real") && page == 2) throw IOException("boom")
            val prefix = if (slug.startsWith("comic")) "9" else "1"
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"${prefix}${page}","series_name":"$slug p$page"}],"pagination":{"totalPages":3}}}};</script>"""
        }
        val walk = CatalogueBuilder(repo, retryPauseMillis = 0).build()
        // real-drama keeps pages 1 and 3, comic-drama keeps all three: the gap costs one page.
        assertEquals(listOf("comic-drama p1", "comic-drama p2", "comic-drama p3", "real-drama p1", "real-drama p3"), walk.shows.map { it.title }.sorted())
        assertEquals(1, walk.gaps.size)
        assertTrue("缺口要指名道姓", walk.gaps.single().contains("real-drama 第 2 页"))
        assertFalse(walk.complete)
        // 断点越过坏页，下一趟不会在同一页上死循环。
        assertEquals(3, walk.cursor[ContentType.SHORT])
    }

    @Test fun `an offline box gives up on the pass instead of hammering 68 pages three times over`() {
        var requests = 0
        val repo = repository { _ -> requests++; throw IOException("no network") }
        val walk = CatalogueBuilder(repo, retryPauseMillis = 0).build()
        // Three tries on the first page, three on the second, and the pass is abandoned rather
        // than worked through all 68 pages x 3.
        assertEquals(CatalogueBuilder.MAX_CONSECUTIVE_FAILURES * 3, requests)
        assertEquals("断网只该留一条说明，不是三条一模一样的", 1, walk.gaps.size)
        assertTrue(walk.gaps.single().contains("提前结束"))
        assertFalse(walk.complete)
    }

    @Test fun `a resumed walk requests only the pages it has not seen`() {
        // The whole point of the bookmark: on a slow box the 68 requests outlast one session, so a
        // walk that restarts at page one can never finish and is never worth storing.
        val site = CountingCatalogue()
        val repo = site.repository()
        var pages = 0
        val first = CatalogueBuilder(repo).build(onPage = { pages++ }, isCancelled = { pages >= 6 })
        assertFalse("被中断的走查不算完成", first.complete)
        assertEquals(6, pages)

        val json = CatalogueIndexDisk.encode(first.shows, first.cursor, first.done, 1_000L)
        val saved = CatalogueIndexDisk.decode(json, 2_000L)!!
        val beforeResume = site.requests
        val again = CatalogueBuilder(repo).build(resume = saved.cursor, done = saved.done)

        assertTrue("续建之后应该走到头", again.complete)
        assertTrue("续建不该重复抓已经拿到的页", again.shows.all { it.id !in first.shows.map { show -> show.id } })
        // 第一趟走到 real-drama 第 5 页（收尾）+ comic-drama 第 1 页；续建只补 comic 剩下 4 页，
        // 也就是 4 个请求、8 部。5 页 x 2 部 x 2 套目录 = 20 部全齐。
        assertEquals(4, site.requests - beforeResume)
        assertEquals(8, again.shows.size)
        assertEquals(20, (first.shows + again.shows).map { it.id }.distinct().size)
    }

    @Test fun `a completed catalogue is never requested again`() {
        val site = CountingCatalogue()
        val repo = site.repository()
        val first = CatalogueBuilder(repo).build()
        assertTrue(first.complete)
        val requestsAfterFirst = site.requests
        val again = CatalogueBuilder(repo).build(resume = first.cursor, done = first.done)
        assertEquals("两套目录都完成了，第二趟不该发一个请求", requestsAfterFirst, site.requests)
        assertEquals(0, again.shows.size)
    }

    /** A five-page-per-catalogue site whose ids identify the catalogue and page they came from. */
    private class CountingCatalogue {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            val prefix = if (slug.startsWith("comic")) "9" else "1"
            val rows = (1..2).map { i -> """{"series_id":"${prefix}${page}$i","series_name":"$slug p$page n$i"}""" }
            val body = """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[${rows.joinToString(",")}],"pagination":{"totalPages":5}}}};</script>"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        fun repository() = ContentRepository(http)
    }

    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()
    )
}
