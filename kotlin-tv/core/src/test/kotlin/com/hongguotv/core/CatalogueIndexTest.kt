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

    @Test fun `the index round-trips through disk without carrying descriptions`() {
        val rows = listOf(show("7694896136963755032", "雾锁情迷", "爱情 · 总裁"), show("7694896136963755033", "京夜诱温"))
        val decoded = CatalogueIndexDisk.decode(CatalogueIndexDisk.encode(rows, 1_000L), 2_000L)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.size)
        assertEquals("雾锁情迷", decoded.search("雾锁情迷").single().title)
        assertEquals("全 80 集", decoded.search("雾锁情迷").single().badge)
        // The detail page fetches the blurb on open; keeping 1600 of them would bloat the file.
        assertEquals("", decoded.search("雾锁情迷").single().description)
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
        val all = CatalogueBuilder(repo).build(onPage = { fresh, collected -> seen += collected })
        // Pages 1-3 per catalogue, 3 shows each; hasMore stops the walk after page three.
        assertEquals(18, all.size)
        assertEquals(listOf(3, 6, 9, 12, 15, 18), seen)
        assertEquals(all.map { it.id }.distinct().size, all.size)
        assertTrue(all.all { it.id.length in 3..4 })
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
        val all = CatalogueBuilder(repo).build(isCancelled = { true })
        assertTrue("取消后不应再发请求", all.isEmpty() || all.size <= 1)
    }

    @Test fun `one failing page does not throw away the pages that worked`() {
        val repo = repository { request ->
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            if (slug.startsWith("real") && page == 2) throw IOException("boom")
            val prefix = if (slug.startsWith("comic")) "9" else "1"
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"${prefix}${page}","series_name":"$slug"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val all = CatalogueBuilder(repo).build()
        assertEquals("real-drama 首页 + comic-drama 首页", 2, all.size)
    }

    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()
    )
}
