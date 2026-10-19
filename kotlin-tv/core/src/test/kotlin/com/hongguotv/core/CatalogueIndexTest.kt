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

    @Test fun `browse walks the catalogue in pages for an empty search box`() {
        val library = index(show("1", "第一部"), show("2", "第二部"), show("3", "第三部"), show("4", "第四部"), show("5", "第五部"))
        assertEquals(listOf("1", "2"), library.browse(0, 2).map { it.id })
        // 翻页是连续的、不重不漏的：用户要能一路翻到全库最后一部，而不是只看得到头一屏。
        assertEquals(listOf("3", "4"), library.browse(2, 2).map { it.id })
        assertEquals(listOf("5"), library.browse(4, 2).map { it.id })
        assertTrue("翻过头就该是空的", library.browse(5, 2).isEmpty())
        assertTrue(library.browse(0, 0).isEmpty())
        assertTrue("负数偏移不该倒着取", library.browse(-1, 2).isEmpty())
        // 一路翻到底应当正好是全库：不重、不漏、不乱序。
        // 偏移必须由 library.size 界定。generateSequence(0) { it + 2 } 在这里会永远翻下去
        // ——它的 lambda 从不返回 null，序列就没有终点。
        val paged = (0 until library.size step 2).flatMap { offset -> library.browse(offset, 2) }.map { it.id }
        assertEquals(listOf("1", "2", "3", "4", "5"), paged)
    }

    @Test fun `the last page can be pulled back so it lands whole`() {
        // 每页 18 部 = 6 张 x 3 行。1600 部：末页只剩 4 部，摆出来是「两整行加四张」，右侧空两格。
        // 把末页起点退到「最后 18 部」就永远是满的 —— 代价只是和上一页重叠几部。
        val pageSize=18
        val columns=6
        val total=1_600
        val pages=(total+pageSize-1)/pageSize
        val library=CatalogueIndex((1..total).map { show(it.toString(), "第 $it 部") })

        fun window(page: Int): Int {
            if(total<=pageSize || page<=1) return (page-1)*pageSize
            val nominal=(page-1)*pageSize
            return if(page==pages && nominal+pageSize>total) (total-pageSize).coerceAtLeast(0) else nominal
        }

        // 中间每页都必须是整行 —— 页大小整除行宽，这一条比末页更要紧。
        for(page in listOf(1,2,53,88)) {
            assertEquals("第 $page 页应满", pageSize, library.browse(window(page),pageSize).size)
            assertEquals("第 $page 页应收整行", 0, library.browse(window(page),pageSize).size % columns)
        }
        val last=library.browse(window(pages),pageSize)
        assertEquals("末页必须是满的一页", pageSize, last.size)
        assertEquals("末页落在索引末尾", "第 1600 部", last.last().title)
        assertEquals("第 1 页从第一部开始", "第 1 部", library.browse(window(1),pageSize).first().title)
        // 索引还不足一屏时退无可退，从头摆。
        val small=index(show("1", "甲"), show("2", "乙"))
        assertEquals(listOf("甲","乙"), small.browse(0,pageSize).map { it.title })
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
        val json = CatalogueIndexDisk.encode(rows, emptyList(), mapOf(Route.REAL_DRAMA to 12), setOf(Route.COMIC_DRAMA), 1_000L)
        val decoded = CatalogueIndexDisk.decode(json, 2_000L)!!
        assertEquals(12, decoded.cursor[Route.REAL_DRAMA])
        assertEquals(setOf(Route.COMIC_DRAMA), decoded.done)
        assertEquals(false, decoded.complete)
        // A finished walk must not be re-requested on the next launch.
        assertEquals(true, CatalogueIndexDisk.decode(
            CatalogueIndexDisk.encode(
                rows, List(rows.size) { Route.REAL_DRAMA },
                mapOf(Route.REAL_DRAMA to 34), Route.entries.toSet(), 1_000L
            ), 2_000L
        )!!.complete)
    }

    @Test fun `an index that finished without labelling its shows is not finished`() {
        // 游标记着「三条货架都到头了」，但每部属于哪条货架是空的 —— 分类按钮加上来之前存下的那份。
        // 搜索是照着这些标签分档的，缺标签的行只能算「哪边都算」，于是分开搜会静悄悄地不起作用；
        // 而 complete 只看游标，这份索引就永远不会被重走查修好。
        val rows = listOf(show("1", "真人剧甲"), show("2", "真人剧乙"), show("3", "漫剧丙"))
        val unlabelled = CatalogueIndexDisk.decode(
            CatalogueIndexDisk.encode(rows, emptyList(), mapOf(Route.REAL_DRAMA to 34), Route.entries.toSet(), 1_000L), 2_000L
        )!!
        assertTrue("游标确实走完了", unlabelled.walkedToEnd)
        assertFalse("索引自己也知道它没标签", unlabelled.index.labelled)
        assertFalse("没记下分类的索引不算完成", unlabelled.complete)
        // decode 会把 routes 补齐到和 entries 一样长，所以光比长度抓不住这个状态 —— 每个位置都得
        // 真的记下分类才行。这正是 labelled 两条都要查的原因。
        assertEquals(unlabelled.entries.size, unlabelled.routes.size)
        assertTrue("全是空标签", unlabelled.routes.all { it == null })

        val labelled = unlabelled.copy(routes = listOf(Route.REAL_DRAMA, Route.REAL_DRAMA, Route.COMIC_DRAMA))
        assertTrue(labelled.index.labelled); assertTrue(labelled.complete)
    }

    @Test fun `a rewalk that labels a show fixes the copy an older index left unlabelled`() {
        // 补标签这一趟会把同一部剧再抓一次，同一个 id 于是出现两次：先是没标签的老行，后是记了标签
        // 的新行。老的去重留的是先到的那份，也就是没标签的那份 —— 标签补回来了、存下去了，分开搜
        // 却仍然把这一行算进两档，补多少次都补不好。
        val old = listOf(show("1", "真人剧甲"), show("2", "漫剧乙"))
        val fresh = listOf(show("1", "真人剧甲"), show("2", "漫剧乙"))
        val carried = CatalogueIndex(old + fresh, listOf(null, null) + listOf(Route.REAL_DRAMA, Route.COMIC_DRAMA))
        assertEquals(2, carried.size)
        assertTrue("补完之后每部都该有分类", carried.labelled)
        assertEquals(1, carried.sizeOf(ContentType.SHORT))
        assertEquals(1, carried.sizeOf(ContentType.COMIC))
        assertEquals(listOf("真人剧甲"), carried.browse(0, 5, ContentType.SHORT).map { it.title })
        assertEquals(listOf("漫剧乙"), carried.browse(0, 5, ContentType.COMIC).map { it.title })
        // 没抓到标签的那一遍仍然算「哪边都算」，老索引不至于变成一片空白。
        assertFalse(CatalogueIndex(old, emptyList()).labelled)
        assertEquals(2, CatalogueIndex(old, emptyList()).sizeOf(ContentType.COMIC))
    }

    @Test fun `a search can be narrowed to one catalogue`() {
        // 分开搜的依据：同一个题材词两边都命中，混在一起就找不到想要的那一档。
        // 实测全库（2400 部）：「都市」1042 部里 670 部是漫剧，「穿越」545 部里 476 部，「重生」358 部里 289 部。
        val real=listOf(show("r1","都市爱情"),show("r2","都市商战"),show("r3","都市逆袭"),show("r4","都市风云"))
        val comic=listOf(show("c1","都市冒险"),show("c2","都市传说"),show("c3","都市怪谈"))
        val library=CatalogueIndex(real + comic, List(4) { Route.REAL_DRAMA } + List(3) { Route.COMIC_DRAMA })

        assertEquals(7, library.search("都市", 20).size)
        assertEquals(real.map { it.title }, library.search("都市", 20, ContentType.SHORT).map { it.title })
        assertEquals(comic.map { it.title }, library.search("都市", 20, ContentType.COMIC).map { it.title })
        // 错字容错也得守同一个过滤，否则打错字的漫剧会以真人剧的身份回来，而页面声称在过滤。
        assertEquals(listOf("都市传说"), library.searchAllowingTypo("都市传说明", 20, ContentType.COMIC).first.map { it.title })
        assertEquals(emptyList<String>(), library.searchAllowingTypo("都市传说明", 20, ContentType.SHORT).first.map { it.title })
        assertEquals("真人剧", library.routeOf("r1")?.label)
        assertEquals("漫剧", library.routeOf("c1")?.label)
        assertEquals(null, library.routeOf("nope"))
        // 没有标签的索引不分档，但也不能因此变成搜不到 —— 缺标签的行仍算命中。
        val bare=CatalogueIndex(real + comic, emptyList())
        assertFalse(bare.labelled)
        assertEquals(7, bare.search("都市", 20, ContentType.COMIC).size)
    }

    @Test fun `an index written before bookmarks existed still reads, and costs one extra walk`() {
        val legacy = """{"savedAt":1000,"count":1,"items":[{"i":"1","t":"雾锁情迷"}]}"""
        val decoded = CatalogueIndexDisk.decode(legacy, 2_000L)
        assertNotNull(decoded)
        assertEquals("没写断点的老索引应当从头再走", emptyMap<Route, Int>(), decoded!!.cursor)
        assertEquals(emptySet<Route>(), decoded.done)
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

    @Test fun `browse keeps the two shelves apart so each filter starts at its own front page`() {
        // 两套目录是两个站点、两种排序。混在一个列表里按类型过滤的话，漫剧那一档得翻过 800 行
        // 真人剧才见得到第一行漫剧 —— 用户看到的「随便看看」就全是真人剧。
        val real = (1..5).map { show("1$it", "真人剧 $it") }
        val comic = (1..4).map { show("9$it", "漫剧 $it") }
        val library = CatalogueIndex(real + comic, List(5) { Route.REAL_DRAMA } + List(4) { Route.COMIC_DRAMA })

        assertEquals(listOf("真人剧 1", "真人剧 2"), library.browse(0, 2, ContentType.SHORT).map { it.title })
        assertEquals(listOf("真人剧 5"), library.browse(4, 2, ContentType.SHORT).map { it.title })
        assertEquals(listOf("漫剧 1", "漫剧 2"), library.browse(0, 2, ContentType.COMIC).map { it.title })
        assertEquals(listOf("漫剧 4"), library.browse(3, 2, ContentType.COMIC).map { it.title })
        assertTrue("翻过头就该是空的", library.browse(5, 2, ContentType.SHORT).isEmpty())
        assertEquals(5, library.sizeOf(ContentType.SHORT))
        assertEquals(4, library.sizeOf(ContentType.COMIC))
        // 不过滤就是全部，按入库顺序。
        assertEquals((real + comic).map { it.id }, library.browse(0, 20).map { it.id })
    }

    @Test fun `the comic shelf holds both of its routes and the real shelf only one`() {
        // AI 剧并进漫剧之后，「漫剧」这一档是 comic-drama 加 ai-drama，两条货架各自 800 部。
        // 搜到 AI 剧时它必须算作漫剧，而真人剧那一档不能混进任何一部。
        val real = (1..3).map { show("1$it", "真人剧 $it") }
        val comic = (1..4).map { show("9$it", "漫剧 $it") }
        val ai = (1..5).map { show("7$it", "AI剧 $it") }
        val library = CatalogueIndex(
            real + comic + ai,
            List(3) { Route.REAL_DRAMA } + List(4) { Route.COMIC_DRAMA } + List(5) { Route.AI_DRAMA }
        )

        assertEquals(3, library.sizeOf(ContentType.SHORT))
        assertEquals(9, library.sizeOf(ContentType.COMIC))
        // 漫剧这一档先走 comic-drama，再走 ai-drama：两条货架的排序原样保留，拼接而不是交错。
        assertEquals(
            (comic + ai).map { it.title },
            library.browse(0, 20, ContentType.COMIC).map { it.title }
        )
        assertEquals("AI剧 1", library.browse(4, 1, ContentType.COMIC).first().title)
        assertEquals("AI剧 5", library.browse(8, 1, ContentType.COMIC).first().title)
        assertTrue("翻过漫剧这一档就该空了", library.browse(9, 5, ContentType.COMIC).isEmpty())
        // 真人剧那一档不掺任何 AI 剧。
        assertTrue(library.browse(0, 20, ContentType.SHORT).none { it.title.startsWith("AI剧") })
        // 题材词两边都命中时，按档分开才找得到想要的那一档。
        val tagged = CatalogueIndex(
            listOf(show("r1", "都市爱情"), show("c1", "都市冒险"), show("a1", "都市推理")),
            listOf(Route.REAL_DRAMA, Route.COMIC_DRAMA, Route.AI_DRAMA)
        )
        assertEquals(listOf("都市爱情"), tagged.search("都市", 20, ContentType.SHORT).map { it.title })
        assertEquals(listOf("都市冒险", "都市推理"), tagged.search("都市", 20, ContentType.COMIC).map { it.title })
    }

    @Test fun `each catalogue is paged on its own count, so its last page still lands whole`() {
        // 「随便看看」按 18 部一页翻，18 又必须整除行宽 6，否则每一页都收在半排上。
        // 两套目录部数不同（真人剧 40、漫剧 25），所以页数和末页起点都只能按各自的总数算。
        // 拿全库 65 去除：漫剧会被说有 4 页，用户翻两页就见底，第三页一片空白 —— 正是「翻着翻着
        // 就白了」的来源，而页码本身不会报错。
        val pageSize=18
        val columns=6
        val real=(1..40).map { show("r$it", "真人剧 $it") }
        val comic=(1..25).map { show("c$it", "漫剧 $it") }
        val library=CatalogueIndex(real + comic, List(40) { Route.REAL_DRAMA } + List(25) { Route.COMIC_DRAMA })

        // 按 18 部一页、页大小整除行宽的方式翻，每一页都得是满的。这段 window 是那段翻页算法的
        // 独立副本：应用里已经不按目录逐页翻了，但 Probe 报「每套目录多少部、能翻几页」还靠它，
        // 两边算法一旦有一处不同，这里就会红。
        fun window(total: Int,page: Int): Int {
            if(total<=pageSize || page<=1) return (page-1)*pageSize
            val pages=(total+pageSize-1)/pageSize
            val nominal=(page-1)*pageSize
            return if(page==pages && nominal+pageSize>total) (total-pageSize).coerceAtLeast(0) else nominal
        }

        for((type,other) in listOf(ContentType.SHORT to "漫剧", ContentType.COMIC to "真人剧")) {
            val total=library.sizeOf(type)
            assertTrue("$type 的片库不该是空的", total>pageSize)
            val pages=(total+pageSize-1)/pageSize
            for(page in 1..pages) {
                val shown=library.browse(window(total,page),pageSize,type)
                assertEquals("$type 第 $page 页应满 $pageSize 部", pageSize, shown.size)
                assertEquals("$type 第 $page 页应收整行", 0, shown.size % columns)
                assertTrue("$type 第 $page 页混进了另一套目录", shown.none { it.title.startsWith(other) })
            }
            // 翻过自己那一档的最后一页就该什么都没有。
            assertTrue("$type 翻过头还剩东西", library.browse(window(total,pages+1),pageSize,type).isEmpty())
        }
        assertEquals("真人剧 40", library.browse(window(40,3),pageSize,ContentType.SHORT).last().title)
        assertEquals("漫剧 25", library.browse(window(25,2),pageSize,ContentType.COMIC).last().title)
        // 共用全库总数会把漫剧那档的页数多说一倍。
        assertEquals(4, (library.size+pageSize-1)/pageSize)
        assertEquals(2, (library.sizeOf(ContentType.COMIC)+pageSize-1)/pageSize)
        // 把上面那句错误做实：拿全库总数给漫剧翻，第 3 页就已经是空的了。
        assertTrue("共用全库总数会让漫剧提前见底", library.browse(window(65,3),pageSize,ContentType.COMIC).isEmpty())
    }

    @Test fun `an index with no catalogue labels still browses under every filter`() {
        // 老索引没有逐条的分类字段。如果把它当成「一条都不属于任何分类」，用户升级后就一片空白。
        val library = CatalogueIndex(listOf(show("1", "第一部"), show("2", "第二部")))
        assertEquals(2, library.browse(0, 5, ContentType.SHORT).size)
        assertEquals(2, library.browse(0, 5, ContentType.COMIC).size)
    }

    @Test fun `each entry keeps its route across a disk round trip`() {
        val entries = listOf(show("11", "真人剧甲"), show("99", "漫剧乙"), show("77", "AI剧丙"))
        val routes = listOf(Route.REAL_DRAMA, Route.COMIC_DRAMA, Route.AI_DRAMA)
        val decoded = CatalogueIndexDisk.decode(CatalogueIndexDisk.encode(entries, routes, mapOf(Route.REAL_DRAMA to 3), setOf(Route.COMIC_DRAMA), 1_000L), 2_000L)!!
        assertEquals(listOf("真人剧甲"), decoded.index.browse(0, 5, ContentType.SHORT).map { it.title })
        assertEquals(listOf("漫剧乙", "AI剧丙"), decoded.index.browse(0, 5, ContentType.COMIC).map { it.title })
        assertEquals(Route.AI_DRAMA, decoded.index.routeOf("77"))
    }

    @Test fun `an index written before AI dramas were folded in keeps its bookmark`() {
        // 落盘的 id 沿用旧的（short / comic），所以电视上已有的 1600 部索引和断点继续有效：
        // 走完的两条货架仍然是走完的，只剩 AI 剧那一档要补 34 页，而不是把 102 页重走一遍。
        val legacy = """{"savedAt":1000,"count":2,"kinds":["short","comic"],
            "cursor":{"short":34,"comic":34},"done":["short","comic"],
            "items":[{"i":"1","t":"真人剧甲"},{"i":"2","t":"漫剧乙"}]}"""
        val decoded = CatalogueIndexDisk.decode(legacy, 2_000L)!!
        assertEquals(mapOf(Route.REAL_DRAMA to 34, Route.COMIC_DRAMA to 34), decoded.cursor)
        assertEquals(setOf(Route.REAL_DRAMA, Route.COMIC_DRAMA), decoded.done)
        // 两条走完了，AI 剧还没走，所以这份索引不算建完 —— 但也不该重走已经走完的两条。
        assertFalse(decoded.complete)
        assertFalse(decoded.walkedToEnd)
        // 走完的这两条仍然是漫剧/真人剧，AI 剧那一条留空，等补走。
        assertEquals(Route.REAL_DRAMA, decoded.index.routeOf("1"))
        assertEquals(Route.COMIC_DRAMA, decoded.index.routeOf("2"))
    }

    @Test fun `the builder labels every show with the route it came from`() {
        val walk = CatalogueBuilder(CountingCatalogue().repository()).build()
        assertEquals(walk.shows.size, walk.routes.size)
        // 三条各 5 页 x 2 部，标签必须和走查时的归属一致。
        assertEquals(10, walk.shows.indices.count { walk.routes[it] == Route.REAL_DRAMA })
        assertEquals(10, walk.shows.indices.count { walk.routes[it] == Route.COMIC_DRAMA })
        assertEquals(10, walk.shows.indices.count { walk.routes[it] == Route.AI_DRAMA })
        assertEquals(walk.shows.map { it.title.substringBefore(' ').take(4) }, walk.routes.map { it.categorySlug.take(4) })
    }

    @Test fun `the builder walks every page, never repeats a show and can be stopped`() {
        val requested = mutableListOf<String>()
        val repo = repository { request ->
            requested += "${request.url.encodedPath}?${request.url.queryParameter("page")}"
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            // Ids must be digits: valid() drops anything else, which would empty the index.
            // Each route needs its own prefix, or the two shelves would collide into one.
            val prefix = when { slug.startsWith("comic") -> "9"; slug.startsWith("ai") -> "7"; else -> "1" }
            val rows = (1..3).map { i -> """{"series_id":"${prefix}${page}$i","series_name":"$slug p$page n$i"}""" }
            val pages = if (page >= 3) 3 else 9
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[${rows.joinToString(",")}],"pagination":{"totalPages":$pages}}}};</script>"""
        }
        val seen = mutableListOf<Int>()
        val walk = CatalogueBuilder(repo).build(onPage = { step -> seen += step.total })
        // Pages 1-3 per route, 3 shows each; hasMore stops the walk after page three.
        assertEquals(27, walk.shows.size)
        assertEquals((3..27 step 3).toList(), seen)
        assertEquals(walk.shows.map { it.id }.distinct().size, walk.shows.size)
        assertTrue(walk.shows.all { it.id.length in 3..4 })
        assertTrue("三条货架都走到了最后一页", walk.complete)
        // Page one is requested without a query string, later pages with it, and one route is
        // walked to its end before the next one starts.
        assertEquals("/category/real-drama?null", requested[0])
        assertEquals("/category/real-drama?2", requested[1])
        assertEquals("/category/real-drama?3", requested[2])
        assertEquals("/category/comic-drama?null", requested[3])
        assertEquals("/category/ai-drama?null", requested[6])
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
            val prefix = when { slug.startsWith("comic") -> "9"; slug.startsWith("ai") -> "7"; else -> "1" }
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"${prefix}${page}","series_name":"$slug p$page"}],"pagination":{"totalPages":3}}}};</script>"""
        }
        val walk = CatalogueBuilder(repo, retryPauseMillis = 0).build()
        assertTrue("重试成功就不该留下缺口", walk.gaps.isEmpty())
        assertEquals(9, walk.shows.size)
        assertEquals(3, calls["real-drama?2"])
        assertTrue(walk.complete)
    }

    @Test fun `a page that never answers is one gap, not the end of the catalogue`() {
        val repo = repository { request ->
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            if (slug.startsWith("real") && page == 2) throw IOException("boom")
            val prefix = when { slug.startsWith("comic") -> "9"; slug.startsWith("ai") -> "7"; else -> "1" }
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"${prefix}${page}","series_name":"$slug p$page"}],"pagination":{"totalPages":3}}}};</script>"""
        }
        val walk = CatalogueBuilder(repo, retryPauseMillis = 0).build()
        // real-drama keeps pages 1 and 3, the other two keep all three: the gap costs one page.
        assertEquals(
            listOf("ai-drama p1", "ai-drama p2", "ai-drama p3", "comic-drama p1", "comic-drama p2", "comic-drama p3", "real-drama p1", "real-drama p3"),
            walk.shows.map { it.title }.sorted()
        )
        assertEquals(1, walk.gaps.size)
        assertTrue("缺口要指名道姓", walk.gaps.single().contains("real-drama 第 2 页"))
        assertFalse(walk.complete)
        // 断点越过坏页，下一趟不会在同一页上死循环。
        assertEquals(3, walk.cursor[Route.REAL_DRAMA])
    }

    @Test fun `an offline box gives up on the pass instead of hammering 102 pages three times over`() {
        var requests = 0
        val repo = repository { _ -> requests++; throw IOException("no network") }
        val walk = CatalogueBuilder(repo, retryPauseMillis = 0).build()
        // Three tries on the first page, three on the second, and the pass is abandoned rather
        // than worked through all 102 pages x 3.
        assertEquals(CatalogueBuilder.MAX_CONSECUTIVE_FAILURES * 3, requests)
        assertEquals("断网只该留一条说明，不是三条一模一样的", 1, walk.gaps.size)
        assertTrue(walk.gaps.single().contains("提前结束"))
        assertFalse(walk.complete)
    }

    @Test fun `a resumed walk requests only the pages it has not seen`() {
        // The whole point of the bookmark: on a slow box the 102 requests outlast one session, so a
        // walk that restarts at page one can never finish and is never worth storing.
        val site = CountingCatalogue()
        val repo = site.repository()
        var pages = 0
        val first = CatalogueBuilder(repo).build(onPage = { pages++ }, isCancelled = { pages >= 6 })
        assertFalse("被中断的走查不算完成", first.complete)
        assertEquals(6, pages)

        val json = CatalogueIndexDisk.encode(first.shows, first.routes, first.cursor, first.done, 1_000L)
        val saved = CatalogueIndexDisk.decode(json, 2_000L)!!
        val beforeResume = site.requests
        val again = CatalogueBuilder(repo).build(resume = saved.cursor, done = saved.done)

        assertTrue("续建之后应该走到头", again.complete)
        assertTrue("续建不该重复抓已经拿到的页", again.shows.all { it.id !in first.shows.map { show -> show.id } })
        // 第一趟走到 real-drama 第 5 页（收尾）+ comic-drama 第 1 页；续建只补 comic-drama 剩下
        // 4 页，再把 AI 剧那 5 页走完，也就是 9 个请求、18 部。5 页 x 2 部 x 3 条货架 = 30 部全齐。
        assertEquals(9, site.requests - beforeResume)
        assertEquals(18, again.shows.size)
        assertEquals(30, (first.shows + again.shows).map { it.id }.distinct().size)
    }

    @Test fun `a completed catalogue is never requested again`() {
        val site = CountingCatalogue()
        val repo = site.repository()
        val first = CatalogueBuilder(repo).build()
        assertTrue(first.complete)
        val requestsAfterFirst = site.requests
        val again = CatalogueBuilder(repo).build(resume = first.cursor, done = first.done)
        assertEquals("三条货架都完成了，第二趟不该发一个请求", requestsAfterFirst, site.requests)
        assertEquals(0, again.shows.size)
    }

    /** A five-page-per-route site whose ids identify the route and page they came from. */
    private class CountingCatalogue {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            val slug = request.url.encodedPath.substringAfterLast('/')
            val page = request.url.queryParameter("page")?.toInt() ?: 1
            val prefix = when { slug.startsWith("comic") -> "9"; slug.startsWith("ai") -> "7"; else -> "1" }
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
