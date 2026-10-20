package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * The hot boards, and the shelf each board belongs to.
 *
 * The shelf is the part that used to be missing, and it is the part that bites: the two shelves
 * are separate boards, and a list that does not say which one it is cannot be handed to a shelf
 * without guessing. Guessing there is how 真人剧 ended up showing the comic board, and how the
 * real board stopped being fetched at all.
 */
class HotRankTest {
    private fun show(id: String, title: String) = Series(id, title, "https://example.com/$id.jpg", "", "全 80 集", "")

    private fun board(route: Route, heat: Map<String, String>, vararg ids: String): HotRank.Board {
        val items = ids.map { show(it, "剧 $it") }
        val positions = items.associate { it.id to RankPosition(null, heat[it.id].orEmpty()) }
        return HotRank.Board(route, items, positions, 1, "10月20日已更新")
    }

    @Test fun `one shelf's boards become one heat order with the ranks counted again`() {
        // 漫剧这一档是两个榜：comic-drama 和 ai-drama。站方各自的「第 1 名」并排放会出现两个第一名，
        // 合成之后的名次必须是这一档自己的热度序。
        val comic = board(Route.COMIC_DRAMA, mapOf("1" to "5500万热度", "2" to "5437万热度"), "1", "2")
        val ai = board(Route.AI_DRAMA, mapOf("3" to "6000万热度", "4" to "4000万热度"), "3", "4")
        val list = HotRank.combine(listOf(comic, ai), ContentType.COMIC)
        assertEquals(listOf("3", "1", "2", "4"), list.items.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), list.items.map { list.positions[it.id]!!.rank })
    }

    @Test fun `a list says which shelf it belongs to`() {
        val real = HotRank.combine(listOf(board(Route.REAL_DRAMA, mapOf("1" to "9000万热度"), "1")), ContentType.SHORT)
        val comic = HotRank.combine(listOf(board(Route.COMIC_DRAMA, mapOf("2" to "9000万热度"), "2")), ContentType.COMIC)
        assertEquals(ContentType.SHORT, real.type)
        assertEquals(ContentType.COMIC, comic.type)
    }

    @Test fun `a list written to disk keeps its shelf`() {
        val list = HotRank.combine(listOf(board(Route.AI_DRAMA, mapOf("7" to "1000万热度"), "7")), ContentType.COMIC)
        val decoded = HotRank.decode(HotRank.encode(list))
        assertNotNull(decoded)
        assertEquals(ContentType.COMIC, decoded!!.type)
        assertEquals(listOf("7"), decoded.items.map { it.id })
        assertEquals(1, decoded.positions["7"]!!.rank)
    }

    @Test fun `a list written before shelves existed is refused rather than guessed at`() {
        // 旧版本存下来的榜单没有「属于哪一档」这一栏。猜一个（比方说一律当漫剧）会让真人剧那一档
        // 画出漫剧的榜，而且一猜就错一整天。宁可当作没有。
        val legacy = """{"fetchedAt":1000,"updatedText":"10月20日已更新","items":[{"i":"1","t":"剧 1","c":"","b":"","g":"","r":1,"h":"9000万热度"}]}"""
        assertNull(HotRank.decode(legacy))
    }

    @Test fun `one shelf's list is never handed to the other shelf`() {
        val cache = HotRank.Cache()
        val real = HotRank.combine(listOf(board(Route.REAL_DRAMA, mapOf("1" to "9000万热度"), "1")), ContentType.SHORT)
        val comic = HotRank.combine(listOf(board(Route.COMIC_DRAMA, mapOf("2" to "8000万热度"), "2")), ContentType.COMIC)
        cache.put(real); cache.put(comic)
        assertEquals(listOf("1"), cache.get(ContentType.SHORT)!!.items.map { it.id })
        assertEquals(listOf("2"), cache.get(ContentType.COMIC)!!.items.map { it.id })
    }

    @Test fun `a shelf with no list of its own has nothing to show`() {
        val cache = HotRank.Cache()
        cache.put(HotRank.combine(listOf(board(Route.COMIC_DRAMA, mapOf("2" to "8000万热度"), "2")), ContentType.COMIC))
        assertNull(cache.get(ContentType.SHORT))
        assertNull(cache.fresh(ContentType.SHORT, 1_000L))
        assertNotNull(cache.fresh(ContentType.COMIC, 1_000L))
    }

    @Test fun `a list older than a day is not served as fresh`() {
        val cache = HotRank.Cache()
        val list = HotRank.combine(listOf(board(Route.REAL_DRAMA, mapOf("1" to "9000万热度"), "1")), ContentType.SHORT)
        cache.put(list)
        val fetched = list.fetchedAt
        assertNotNull(cache.fresh(ContentType.SHORT, fetched + HotRank.FRESH_MS - 1))
        assertNull(cache.fresh(ContentType.SHORT, fetched + HotRank.FRESH_MS + 1))
    }

    @Test fun `the two shelves are written down under their own keys`() {
        assertEquals("ranking-short", HotRank.Cache.key(ContentType.SHORT))
        assertEquals("ranking-comic", HotRank.Cache.key(ContentType.COMIC))
        assertNotEquals(HotRank.Cache.key(ContentType.SHORT), HotRank.Cache.key(ContentType.COMIC))
    }
}
