package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    /** What the retired multi_video_model endpoint answers today. */
    private val REFUSED = """{"code":110001,"message":"未知异常"}"""

    // Captured from a live video_model response; the ciphertext only decrypts with this seed.
    private val KEY_SEED = "0CTOAF4mtdifakrNt5sx7ANxYgZhW1MmguzeJcs6D3E="
    private val SPADE_A = "krwc9VC5HcNjvy31ZLgsxma9HPRNjAbxV74b8Ve7KvRguCihoQ=="
    private val MAIN_URL = "qAABAPdNq0KM5TsIOWwt0Jv4s3l5HGqIhhtwB4AtzaAtPvdhR+ZSyjNbUuS61n6oqlcrcHe//qEIZ8P2tPlRsTuuaOgt1NTU/m9L+qJ2R7Bt5dbJ5Q9kRSJlwODf7Ri3QEGJx9B5JZirp+5HLWx+OkxNMSwoLrcSyBPGD6/GP7SgEtolefySfVfmhJMa7zOfXUOmmSctTz2QoESQLblos5WrvONT6JvxGyH+dlHDTrO3vgtGAHxxu/Y+F3tJpi3ihBhM0cd+XzRvk4YJAE4BzgObWi/jSnVQTlT9BQB3DW9kIujrbz9+x4VOUG2Syj0NTtbYP9obhRMavrzBNwY9IoZJrBHBUxQccYPGNc03D4nv4A4LNViNVKUd+T4XEK97XUhWG/HPTNHooboMfT2e1H5s0XNW6w24ZyLOgBzJQfPkusfmiqSIlqGVuXSP69lTiQ28SWoc0KSScZL86/RxqCWIbmpXdI/EB3YvBy+WdWO/pY76pto8Y/OzkB+CFevCZqP8pTBzqRR37AYDob/BLeYIpyZwc8OsxVObvOpuTXXr0zKioDstY84EPH9nU/j/+2qfpUrem5fq0xrtUv2p5zihPNYOKBk3JL/4u5VHUgXLzsIaH6iaFDbmcLghx3mfImOFJJOhQ6OuS90B/RXmgZstXhu1zOeZDROQj9mJ0RWJzYd5ugxqSWxgH4/wgTJh8Fe6aIKtOcvPQLZxfSND/KxHm9rRIVE0eczb9nT8qpCz06U6UGGPQiPFe1c4R+WsaqnbZF6yC4ri913PdcDOKfd/ZDH1Oe2NLNQHCFcIG0zc3UFi31vURjjH68VIa0q7YdFMRzh2yu+OnTPA52Yco/2B5hLxel6/bjQLhqpQ3YJAzT3CisTXx1IEb6gVBkmMAB8gwzY7bAaTQH6g7vEkkbwnGZJBlFSGEgUQwEF1q22rvIZLdM6iMwGXR1GgVas0a5Fi3g=="
    private val PLAIN_HOST = "https://v26-reading-videocdn302.qznovelvod.com"
    private val FALLBACK = "https://vas-lf-x.snssdk.com/video/fplay/1/x"
    private val MODEL_PATH = "/novel/player/video_model/v1/"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun rendition(definition: String, codec: String, mainUrl: String = MAIN_URL, spade: String = SPADE_A) =
        """{"definition":"$definition","codec_type":"$codec","main_url":"$mainUrl","spade_a":"$spade"}"""

    /** Serves the signed endpoint end to end; anything else is a routing bug. */
    private fun signedPlayerRepository(videoList: String, seen: MutableList<String> = mutableListOf()) = repository { request ->
        seen += request.url.encodedPath
        when (request.url.encodedPath) {
            MODEL_PATH -> """{"code":0,"data":{"video_model":"{\"fallback_api\":\"$FALLBACK\"}"}}"""
            "/video/fplay/1/x" -> """{"video_info":{"data":{"key_seed":"$KEY_SEED","video_list":$videoList}}}"""
            else -> throw AssertionError("unexpected request ${request.url}")
        }
    }

    private fun playerRepository(page: String) = repository { request ->
        when (request.url.encodedPath) {
            MODEL_PATH -> REFUSED
            "/player/456/123" -> page
            else -> throw AssertionError("unexpected request ${request.url}")
        }
    }

    @Test fun streamResolvesTheHevcRenditionFromTheSignedEndpoint() {
        val repo=signedPlayerRepository("""{"video_1":${rendition("360p","bytevc2")},"video_5":${rendition("1080p","bytevc1")}}""")
        val result=repo.stream("456","123")
        // bytevc2 is skipped, so the only survivor is the 1080p ByteVC1/HEVC rendition.
        assertEquals("1080P",result.quality)
        // The spade ciphertext must come back as a real CDN URL, not as base64 noise.
        assertTrue(result.url,result.url.startsWith(PLAIN_HOST))
        assertTrue(result.url,result.url.contains("mime_type=video_mp4"))
        assertArrayEquals(hex("33566cff73160f1ec29a3e884732d606"),result.key)
    }

    @Test fun streamAsksTheEndpointThatStillAnswersForATopLevelVideoId() {
        val bodies=mutableListOf<String>()
        val repo=repository { request ->
            if(request.url.encodedPath==MODEL_PATH) {
                bodies+=Buffer().also { request.body?.writeTo(it) }.readUtf8()
                """{"code":0,"data":{"video_model":"{\"fallback_api\":\"$FALLBACK\"}"}}"""
            } else """{"video_info":{"data":{"key_seed":"$KEY_SEED","video_list":{"video_5":${rendition("1080p","bytevc1")}}}}}}"""
        }
        repo.stream("456","123")
        assertEquals(1,bodies.size)
        assertTrue(bodies.single(),bodies.single().contains(""""video_id":"123""""))
        // The retired shape is what 110001 came from; it must not creep back in.
        assertFalse(bodies.single(),bodies.single().contains("mixed_video_id_map"))
    }

    @Test fun streamRefusesRenditionsAndroidCannotExtractAVideoTrackFrom() {
        val list="""{"video_4":${rendition("720p","bytevc2")},"video_1":${rendition("360p","bytevc2")}}"""
        val model="""{"code":0,"data":{"video_model":"{\"fallback_api\":\"$FALLBACK\"}"}}"""
        val info="""{"video_info":{"data":{"key_seed":"$KEY_SEED","video_list":$list}}}"""
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); val path=request.url.encodedPath
            val payload=if(path==MODEL_PATH) model else if(path=="/video/fplay/1/x") info else ""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if(payload.isEmpty()) 404 else 200).message("r").body(payload.toResponseBody()).build()
        }.build())
        val e=assertThrows(IOException::class.java) { repo.stream("456","123") }
        // The signed attempt's own diagnosis is what must reject these, not the public page.
        assertTrue(e.suppressed.orEmpty().joinToString { it.message.orEmpty() },
            e.suppressed.orEmpty().any { it.message.orEmpty().contains("没有电视可播放的清晰度") })
    }

    @Test fun streamTriesTheSignedEndpointBeforeFallingBackToThePublicPage() {
        val paths=mutableListOf<String>()
        val repo=repository { request -> paths+=request.url.encodedPath; if(request.url.encodedPath==MODEL_PATH) REFUSED else """{"contentUrl":"https://hongguoduanju.com/video/public/123/360p.mp4"}""" }
        repo.stream("456","123")
        assertEquals(listOf(MODEL_PATH,"/player/456/123"),paths)
    }

    @Test fun streamFallsBackToThePublicPlayerPage() {
        val repo=playerRepository("""{"contentUrl":"https://hongguoduanju.com/video/public/123/360p.mp4"}""")
        val result=repo.stream("456","123",1080)
        assertEquals("https://hongguoduanju.com/video/public/123/360p.mp4",result.url)
        assertEquals("360P",result.quality)
        assertNull("public source is not CENC encrypted",result.key)
    }

    /**
     * The signed endpoint now answers HTTP 200 with a *zero-byte* body rather than a parseable
     * error. `JSONObject("")` throws org.json.JSONException, which is a RuntimeException - an
     * `IOException`-only catch let it escape and the public-page fallback below never ran, so the
     * TV showed nothing at all instead of the first three episodes.
     */
    @Test fun emptySignedBodyStillReachesThePublicPageFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            when(request.url.encodedPath) {
                MODEL_PATH -> ""      // exactly what the server sends today
                "/player/456/123" -> """{"contentUrl":"https://hongguoduanju.com/video/public/123/360p.mp4"}"""
                else -> throw AssertionError("unexpected request ${request.url}")
            }
        }
        val result=repo.stream("456","123",1080)
        assertEquals(listOf(MODEL_PATH,"/player/456/123"),paths)
        assertEquals("https://hongguoduanju.com/video/public/123/360p.mp4",result.url)
        assertEquals("360P",result.quality)
    }

    /** Whatever the signed attempt died of, the failure the user sees must be the fallback's. */
    @Test fun emptySignedBodyReportsTheFallbackFailureNotTheJsonError() {
        val repo=repository { request ->
            if(request.url.encodedPath==MODEL_PATH) "" else "<html>没有资源</html>"
        }
        val e=assertThrows(IOException::class.java) { repo.stream("456","123") }
        assertEquals("播放地址暂不可用",e.message)
        assertTrue(e.suppressed.orEmpty().joinToString { it.message.orEmpty() },
            e.suppressed.orEmpty().any { it is org.json.JSONException })
    }

    @Test fun streamUnescapesJsonSlashEscapesInEmbeddedUrl() {
        val repo=playerRepository("""{"contentUrl":"https:\u002F\u002Fhongguoduanju.com\u002Fvideo\u002Fpublic\u002F123\u002F360p.mp4"}""")
        assertEquals("https://hongguoduanju.com/video/public/123/360p.mp4",repo.stream("456","123").url)
    }

    @Test fun streamDropsRangeParameterThatWouldTruncatePlayback() {
        val repo=playerRepository("""{"contentUrl":"https://example.com/v.mp4?a=1&Range=bytes%3D0-1"}""")
        assertEquals("https://example.com/v.mp4?a=1",repo.stream("456","123").url)
    }

    @Test fun streamFallsBackToMainUrlAndLabelsOverLimitResolution() {
        val repo=playerRepository("""{"main_url":"https://hongguoduanju.com/video/public/123/720p.mp4"}""")
        val result=repo.stream("456","123",480)
        assertEquals("https://hongguoduanju.com/video/public/123/720p.mp4",result.url)
        assertEquals("720P（兼容资源）",result.quality)
    }

    @Test fun playerPageWithoutAnyPlayableUrlIsAnErrorNotSilentAudio() {
        assertThrows(IOException::class.java) { playerRepository("<html>没有资源</html>").stream("456","123") }
    }

    @Test fun missingEpisodeExplainsThatOnlyTheFirstThreeAreHosted() {
        val e=assertThrows(IOException::class.java) { failingRepository(404).stream("456","123") }
        assertEquals("该来源每部剧只提供前 3 集，本集暂无资源",e.message)
    }

    @Test fun otherStatusesKeepThePlainHttpError() {
        val e=assertThrows(IOException::class.java) { failingRepository(503).stream("456","123") }
        assertEquals("内容请求失败（HTTP 503）",e.message)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )

    private fun failingRepository(code: Int) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("error").body("".toResponseBody()).build()
        }.build()
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2, id: String = "123", title: String = "测试漫剧", rank: Int? = null, heat: String = "") = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId",id).put("title",title)
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))
            .apply { if(rank!=null) put("rank",rank); if(heat.isNotEmpty()) put("heatText",heat) }))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun show(id: String, title: String) = Series(id, title, "https://example.com/$id.jpg", "", "", "")

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject, route: Route = Route.COMIC_DRAMA) = script("mergeLoaderData",JSONArray()
        .put(HotRank.routeKey(route)).put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    /** A board as the site serves it: the router data says which board it is, then the payload. */
    private fun boardPage(route: Route, payload: JSONObject, page: Int = 1, updated: String = "") =
        """<script>_ROUTER_DATA={"loaderData":{"${HotRank.routeKey(route)}":{"rankKey":"${route.rankKey}","pageNum":$page${if(updated.isEmpty()) "" else ",\"updatedText\":\"$updated\""}}}};</script>"""+currentRank(payload,route)

    @Test fun theBoardKeepsItsSourceRankHeatAndUpdate() {
        val payload=rankPayload(2,5,rank=23,heat="5746万热度")
        val board=HotRank.parse(boardPage(Route.COMIC_DRAMA,payload,2,"9月22日已更新"),2,Route.COMIC_DRAMA)
        assertEquals(RankPosition(23,"5746万热度"),board.positions["123"])
        assertEquals(5,board.totalPages)
        assertEquals("9月22日已更新",board.updatedText)
    }

    @Test fun missingRankIsNotInvented() {
        val board=HotRank.parse(boardPage(Route.COMIC_DRAMA,rankPayload(5,5)),5,Route.COMIC_DRAMA)
        assertEquals(RankPosition(null,""),board.positions["123"])
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        val board=HotRank.parse(html,2,Route.COMIC_DRAMA)
        assertEquals(RankPosition(21,"5746万热度"),board.positions["123"])
        assertEquals(5,board.totalPages)
        assertEquals("今日已更新",board.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        assertThrows(IOException::class.java) {
            HotRank.parse(boardPage(Route.COMIC_DRAMA,rankPayload(1,5)),2,Route.COMIC_DRAMA)
        }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        val board=HotRank.parse(html,5,Route.COMIC_DRAMA)
        assertEquals(5,board.totalPages)
    }

    @Test fun legacyRankIsStillSupported() {
        val html=script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload()))
        assertTrue(HotRank.parse(html,1,Route.COMIC_DRAMA).items.isNotEmpty())
    }

    @Test fun aBoardOfAnotherRouteIsNotReadAsThisOne() {
        val html=script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload()))
        assertThrows(IOException::class.java) { HotRank.parse(html,1,Route.COMIC_DRAMA) }
    }

    @Test fun aRejectedBoardRemainsAnError() {
        val html=boardPage(Route.COMIC_DRAMA,rankPayload().put("isSuccess",false))
        assertThrows(IOException::class.java) { HotRank.parse(html,1,Route.COMIC_DRAMA) }
    }

    @Test fun renderedBoardWorksWhenStreamedPayloadIsMissing() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        val board=HotRank.parse(html,2,Route.COMIC_DRAMA)
        assertEquals("测试 & 漫剧",board.items.single().title)
        assertEquals("https://example.com/a.jpg",board.items.single().cover)
        assertEquals(3,board.totalPages)
    }

    @Test fun theRealBoardIsReachedAtItsOwnPath() {
        val repo=repository { request ->
            assertEquals("/rank/hot-real-drama",request.url.encodedPath)
            val page=request.url.queryParameter("page")?.toInt() ?: 1
            boardPage(Route.REAL_DRAMA,boardRows(page,20,9000-(page-1)*20,1+(page-1)*20))
        }
        val page=HotRank.page(repo.rankingList(ContentType.SHORT),1,18)
        assertEquals("第 1 部",page.items.first().title)
        assertEquals("https://example.com/1.jpg",page.items.first().cover)
        assertEquals("全 10 集",page.items.first().badge)
        assertTrue("还有下一页",page.hasMore)
    }

    /** A board page with [rows] shows, their heats falling from [topHeat] 万 one by one. */
    private fun boardRows(page: Int, rows: Int, topHeat: Int, idBase: Int, totalPages: Int = 5) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray((0 until rows).map { i ->
            JSONObject().put("seriesId",(idBase+i).toString()).put("title","第 ${idBase+i} 部")
                .put("cover","https://example.com/${idBase+i}.jpg").put("episodeCount",10)
                .put("rank",(page-1)*rows+i+1).put("heatText","${topHeat-i}万热度")
        }))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    @Test fun theShelfBoardIsFetchedFromTheSitesOwnPathsAndEveryPageOfIt() {
        val requested=mutableListOf<String>()
        val repo=repository { request ->
            val page=request.url.queryParameter("page")?.toInt() ?: 1
            requested += "${request.url.encodedPath}?$page"
            boardPage(Route.REAL_DRAMA,boardRows(page,20,9000-(page-1)*20,page*100))
        }
        val page=HotRank.page(repo.rankingList(ContentType.SHORT),1,18)
        // 整份榜单是取回来才排得出一个顺序的，所以它的每一页都要取。
        assertEquals(listOf("/rank/hot-real-drama?1","/rank/hot-real-drama?2","/rank/hot-real-drama?3","/rank/hot-real-drama?4","/rank/hot-real-drama?5"),requested)
        assertEquals(18,page.items.size)
        assertEquals("第 100 部",page.items.first().title)
    }

    @Test fun theComicShelfBoardIsItsTwoBoardsMergedIntoOneHeatOrder() {
        // 漫剧这一档是两条货架，站方没有把它们合成一张榜 —— 但热度是每部剧的数字，在每张榜上
        // 读到的都一样，所以两张榜能合成一个顺序。而且必须合成整份：分榜看是错的（漫剧榜的第 21 名
        // 比 AI 榜的第 30 名还热，分开排就把它排到后面去了）。
        val repo=repository { request ->
            val page=request.url.queryParameter("page")?.toInt() ?: 1
            when(request.url.encodedPath) {
                "/rank/hot-comic-drama" -> boardPage(Route.COMIC_DRAMA,boardRows(page,20,9000-(page-1)*20,1+(page-1)*20))
                "/rank/hot-ai-drama" -> boardPage(Route.AI_DRAMA,boardRows(page,20,8000-(page-1)*20,1001+(page-1)*20))
                else -> throw AssertionError("unexpected path "+request.url.encodedPath)
            }
        }
        val page=HotRank.page(repo.rankingList(ContentType.COMIC),1,18)
        // 一页 18 张，正好三行六列 —— 网格永远不缺最后一行。
        assertEquals(18,page.items.size)
        assertEquals(0,page.items.size%6)
        // 一页之内热度递减。
        val heats=page.items.map { HotRank.heatValue(page.ranking!!.positions[it.id]!!.heat)!! }
        assertTrue(heats.zipWithNext().all { (a,b) -> a>b })
        // 名次是这一档里的连续名次。
        assertEquals(1,page.ranking!!.positions[page.items.first().id]!!.rank)
        assertEquals(18,page.ranking!!.positions[page.items.last().id]!!.rank)
        // 第二页接着 19，不重置 —— 名次是一个顺序，不是一页一页的。
        val list=repo.rankingList(ContentType.COMIC); val second=HotRank.page(list,2,18)
        assertEquals(19,second.ranking!!.positions[second.items.first().id]!!.rank)
        // 合成的是整份榜单：AI 榜最热的那部排在漫剧榜 100 部之后，而不是排在前面。
        val all=HotRank.page(list,1,200)
        assertEquals(200,all.items.size)
        assertEquals(101,all.ranking!!.positions["1001"]!!.rank)
    }

    @Test fun aHeatTheSiteBrokeIsStillRead() {
        // 站方偶尔在数字里塞一个控制字符（实测：「5997\u0000万热度」）。读不出来的话这一行
        // 就排不进去 —— 榜单是按热度排的，排不进去等于把它扔了。
        assertEquals(59970000L,HotRank.heatValue("5997\u0000万热度"))
        assertEquals(83700000L,HotRank.heatValue("8370万热度"))
        assertEquals(120000000L,HotRank.heatValue("1.2亿热度"))
        assertEquals(null,HotRank.heatValue("热度暂无"))
        assertEquals(null,HotRank.heatValue(null))
    }

    @Test fun aBoardThatFailsDoesNotTakeTheWholeTabDown() {
        // 漫剧这一档有两个榜，一个挂了不该让整页空白：另一个照旧能看。
        val repo=repository { request ->
            val page=request.url.queryParameter("page")?.toInt() ?: 1
            when(request.url.encodedPath) {
                "/rank/hot-comic-drama" -> boardPage(Route.COMIC_DRAMA,boardRows(page,20,9000-(page-1)*20,1+(page-1)*20))
                "/rank/hot-ai-drama" -> throw IOException("boom")
                else -> throw AssertionError("unexpected path "+request.url.encodedPath)
            }
        }
        val page=HotRank.page(repo.rankingList(ContentType.COMIC),1,18)
        assertEquals(18,page.items.size)
        assertEquals("第 1 部",page.items.first().title)
    }

    @Test fun aShelfWithNoWorkingBoardAtAllFails() {
        val repo=repository { throw IOException("boom") }
        assertThrows(IOException::class.java) { repo.rankingList(ContentType.COMIC) }
    }

    @Test fun theRankingSurvivesBeingWrittenDownAndReadBack() {
        // 榜单落盘是为了「下次打开就有东西看」：用户不必等网络，也不必按刷新。
        val list=HotRank.combine(listOf(
            HotRank.Board(Route.COMIC_DRAMA,listOf(show("1","漫剧甲")),mapOf("1" to RankPosition(1,"8370万热度")),5,"10月20日已更新"),
            HotRank.Board(Route.AI_DRAMA,listOf(show("2","AI剧甲")),mapOf("2" to RankPosition(2,"7406万热度")),5,"10月20日已更新")),ContentType.COMIC)
        val decoded=HotRank.decode(HotRank.encode(list))
        assertNotNull(decoded)
        assertEquals(listOf("漫剧甲","AI剧甲"),decoded!!.items.map { it.title })
        assertEquals(RankPosition(1,"8370万热度"),decoded.positions["1"])
        assertEquals(RankPosition(2,"7406万热度"),decoded.positions["2"])
        assertEquals("10月20日已更新",decoded.updatedText)
        // 属于哪一档也要一起存下来：存不下来的话，落盘那份榜就分不清是给谁看的了。
        assertEquals(ContentType.COMIC,decoded.type)
    }

    @Test fun aRankingIsGoodForADayAndThenWorthFetchingAgain() {
        // 站方的榜一天只更新一次，所以一天取一次就够了 —— 一天取四次只是白花站的调用频率。
        val now=1_000_000L
        val list=HotRank.HotList(listOf(show("1","甲")),emptyMap(),"10月20日已更新",now,ContentType.SHORT)
        assertFalse(list.isStale(now+23*60*60*1000L))
        assertTrue(list.isStale(now+25*60*60*1000L))
    }

    @Test fun aRankingPageIsCutOutOfTheWholeList() {
        val items=(1..100).map { show(it.toString(),"第 $it 部") }
        val list=HotRank.HotList(items,items.mapIndexed { index,series -> series.id to RankPosition(index+1,"") }.toMap(),"",0L,ContentType.COMIC)
        val page=HotRank.page(list,1,36)
        assertEquals(36,page.items.size)
        assertEquals("第 1 部",page.items.first().title)
        assertEquals(3,page.ranking!!.totalPages)
        val last=HotRank.page(list,3,36)
        assertEquals(28,last.items.size)
        assertFalse(last.hasMore)
    }

    /**
     * 站方搜索接口的回应。
     *
     * 2026-10-22 起站方把搜索页改成了纯前端渲染，数据改由 `/api/search/result?query=…` 下发，
     * 字段形状和当年 SSR 那份一样（`searchList[].video_data.*`）。
     */
    private fun searchPage(vararg rows: String) =
        """{"searchList":[${rows.joinToString(",")}],"totalCount":"${rows.size}","pageNum":1,"hasMore":false}"""

    private fun searchRow(id: String, title: String, episodes: Int = 8) =
        """{"video_data":{"series_id":"$id","series_title":"$title","episode_cnt":$episodes}}"""

    @Test fun searchReadsSeriesIdFromNestedVideoData() {
        val repo=repository { searchPage(searchRow("456","测试短剧",12)) }
        val result=repo.search("测试",1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertEquals("测试短剧",result.items.single().title)
        assertEquals("全 12 集",result.items.single().badge)
    }

    @Test fun theSearchAsksTheSitesOwnApiWithTheQueryParameter() {
        // 2026-10-22：站方把搜索页改成了纯前端渲染，数据改由 /api/search/result 下发。
        // **参数名是 query，不是 keyword** —— 用 keyword 回 400，用 query 才回 200。
        val asked=mutableListOf<Pair<String,String?>>()
        val repo=repository { request ->
            asked+=(request.url.encodedPath to request.url.queryParameter("query"))
            searchPage(searchRow("456","测试短剧"))
        }
        repo.search("测试",1)
        assertEquals(1,asked.size)
        assertEquals("/api/search/result",asked.single().first)
        assertEquals("测试",asked.single().second)
    }

    @Test fun searchNeverAdvertisesANextPageBecauseTheSiteRendersOnlyPageOne() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            searchPage(searchRow("456","测试短剧"))
        }
        assertFalse(repo.search("测试",1).hasMore)
        // 站方这一路只是补位，翻页是本机索引的事 —— 所以不该为翻页再问站方一次。
        assertTrue(repo.search("测试",2).items.isEmpty())
        assertEquals(1,paths.size)
    }

    @Test fun comicSearchUsesTheSameWorkingSourceAsShortDrama() {
        val repo=repository { searchPage(searchRow("999","测试漫剧")) }
        assertEquals(listOf("999"),repo.search("测试",1,ContentType.COMIC).items.map { it.id })
    }

    /**
     * A show the site lists in its search results but that no browsable catalogue carries can only
     * be reached through the search page: the walk covers the catalogues, and this show is in none
     * of them. Measured 2026-10-19 — 《回到心跳开始时》 is the site's first search row and appears in
     * none of the 2400 catalogue shows — so a filter that drops every row the index cannot
     * classify deletes exactly the shows that have no other way in.
     */
    @Test fun aServerRowTheIndexCannotClassifyIsKept() {
        val local=CatalogueIndex(listOf(Series("1","雾锁情迷")),listOf(Route.REAL_DRAMA))
        val repo=repository { searchPage(searchRow("7697172143917779992","回到心跳开始时")) }
        val result=repo.search("回到心跳开始时",1,ContentType.SHORT,local)
        assertEquals(listOf("7697172143917779992"),result.items.map { it.id })
        assertEquals(1,result.matchedCount)
    }

    /**
     * The other half of the same rule: a row the index can classify still goes when it belongs to
     * the catalogue the user did not pick. Splitting by catalogue is the point of the filter, and
     * keeping the rows the index cannot judge must not turn it into no filter at all.
     */
    @Test fun aServerRowTheIndexKnowsIsStillSplitByCatalogue() {
        val local=CatalogueIndex(listOf(Series("1","雾锁情迷"),Series("2","总裁归来")),listOf(Route.REAL_DRAMA,Route.COMIC_DRAMA))
        val repo=repository { searchPage(searchRow("2","总裁归来新番"),searchRow("999","总裁归来后传")) }
        val result=repo.search("总裁归来",1,ContentType.SHORT,local)
        // 「总裁归来」 is filed under 漫剧, so the row the index can place goes; the one it cannot
        // place stays, because dropping it would make a searchable show unsearchable.
        assertEquals(listOf("999"),result.items.map { it.id })
        assertEquals(1,result.matchedCount)
    }

    /**
     * The site never answers a search with an empty list, so 「站内没有这部剧」 can only be
     * decided client-side. These rows are what it really returns for 「庆余年」, which is a TV
     * drama this short-drama site does not carry: ten confident same-genre rows, a totalCount of
     * 182, and not one of them containing the term. The client used to show all ten as a hit.
     */
    @Test fun searchCountsOnlyRowsThatActuallyMentionTheKeyword() {
        val repo=repository {
            searchPage(
                searchRow("7695318268868840510","人在北凉，以势压人"),
                searchRow("7685713332619201561","剑神归来：我和驴子的十万年第一季"),
                searchRow("7689421093307681854","零下五十度我被困在公司")
            )
        }
        val result=repo.search("庆余年")
        assertEquals(3,result.items.size)
        assertEquals(0,result.matchedCount)
    }

    @Test fun fuzzySearchStillCountsEveryRowItDidReach() {
        // 搜「总裁」 really does reach seven shows containing it — fuzzy search works, and must not
        // be reported as 「站内没有」 just because the exact show is absent.
        val repo=repository {
            searchPage(
                searchRow("7688352674999176254","总裁是我前女友"),
                searchRow("7690406030894713881","总裁归来：从天桥开始"),
                searchRow("7694313235289214014","我的老婆居然是总裁"),
                searchRow("7695625941778697241","初尝玫瑰！")
            )
        }
        assertEquals(3,repo.search("总裁").matchedCount)
    }

    @Test fun mentionsIgnoresThePunctuationNobodyRetypes() {
        assertTrue(mentions("分手四年，傅机长失了控","分手四年，傅机长失了控"))
        // A phone keyboard produces a space where the site writes an exclamation mark.
        assertTrue(mentions("伪人降临！我是全场唯一预言家第三季","伪人降临 我是全场唯一预言家第三季"))
        assertTrue(mentions("谁让这个文盲修仙的第五季","谁让这个文盲修仙的"))
        assertFalse(mentions("剑神归来：我和驴子的十万年第一季","庆余年"))
        // A blank keyword must not match everything, or 「站内没有」 could never be shown.
        assertFalse(mentions("剑神归来","   "))
    }

    @Test fun browsePagesCarryNoMatchCountBecauseTheQuestionDoesNotApplyThere() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        assertNull(repo.home(1,ContentType.SHORT).matchedCount)
    }

    /**
     * The ten-row ceiling belongs to the server, not to the client: with an index the same search
     * pages through everything the index holds, which is the whole point of building it.
     */
    @Test fun theLocalIndexPagesResultsTheServerRefusesToGive() {
        val local=CatalogueIndex((1..70).map { Series((100+it).toString(),"总裁归来第${it}季") })
        val repo=repository { searchPage(searchRow("999","站内没有的热门剧")) }
        val first=repo.search("总裁归来",1,ContentType.SHORT,local)
        assertEquals(ContentRepository.SEARCH_PAGE_SIZE,first.items.size)
        assertTrue(first.hasMore)
        val third=repo.search("总裁归来",3,ContentType.SHORT,local)
        assertEquals(10,third.items.size)
        assertFalse(third.hasMore)
        // Every page is a local match, so the 「站内没有」 notice must stay off.
        assertTrue(third.items.all { mentions(it.title,"总裁归来") })
    }

    @Test fun rowsTheIndexHasNeverSeenOnlyFillAnEmptyPage() {
        val local=CatalogueIndex(listOf(Series("1","总裁归来"),Series("2","总裁归来第二季")))
        val repo=repository { searchPage(searchRow("999","总裁归来后传"),searchRow("998","另一个新剧")) }
        val first=repo.search("总裁归来",1,ContentType.SHORT,local)
        // Two local hits first. Of the server's rows only the one that mentions the term earns a
        // slot — the other is exactly the padding that made an exact hit look like a page of noise.
        assertEquals(listOf("1","2","999"),first.items.map { it.id })
        assertFalse(first.hasMore)
    }

    @Test fun withNoLocalHitEveryServerRowIsKeptBecauseItIsAllThereIs() {
        val local=CatalogueIndex(listOf(Series("1","雾锁情迷")))
        val repo=repository { searchPage(searchRow("999","剑神归来：我和驴子的十万年第一季"),searchRow("998","零下五十度我被困在公司")) }
        val result=repo.search("庆余年",1,ContentType.SHORT,local)
        // Nothing matched locally, so the two same-genre rows are shown in full behind the
        // 「站内没有《庆余年》，以下是同类推荐」 notice rather than filtered away to nothing.
        assertEquals(listOf("999","998"),result.items.map { it.id })
        assertEquals(0,result.matchedCount)
    }

    @Test fun withoutALocalHitSearchFallsBackToTheServersTenRows() {
        val local=CatalogueIndex(listOf(Series("1","雾锁情迷")))
        val repo=repository { searchPage(searchRow("999","剑神归来：我和驴子的十万年第一季")) }
        val result=repo.search("庆余年",1,ContentType.SHORT,local)
        assertEquals(listOf("999"),result.items.map { it.id })
        assertEquals(0,result.matchedCount)
        assertFalse(result.hasMore)
        // No index and no more pages: the second request must not be made.
        assertTrue(repo.search("庆余年",2,ContentType.SHORT,local).items.isEmpty())
    }

    @Test fun aTagOnlyHitCountsAsFoundSoTheTabDoesNotClaimTheShowIsMissing() {
        // 《雾锁情迷》 carries a 「总裁」 tag and no such word in its title; reporting zero matches
        // would put 「站内没有《总裁》」 directly above the very shows that matched.
        val local=CatalogueIndex(listOf(Series("1","雾锁情迷",tags="爱情 · 都市爱情 · 总裁")))
        val repo=repository { searchPage(searchRow("999","初尝玫瑰！")) }
        assertEquals(1,repo.search("总裁",1,ContentType.SHORT,local).matchedCount)
    }

    @Test fun `totalHits counts everything the paging will walk`() {
        val local=CatalogueIndex((1..70).map { Series((100+it).toString(),"总裁归来第${it}季") })
        val repo=repository { searchPage(searchRow("999","总裁归来新番")) }
        val first=repo.search("总裁归来",1,ContentType.SHORT,local)
        // 70 local hits plus the one server row that earned a slot because it mentions the term.
        assertEquals(71,first.totalHits)
        assertEquals(ContentRepository.SEARCH_PAGE_SIZE,first.items.size)
        assertTrue(first.hasMore)
        assertEquals(3,maxOf(1,((first.totalHits ?: 0)+ContentRepository.SEARCH_PAGE_SIZE-1)/ContentRepository.SEARCH_PAGE_SIZE))
    }

    @Test fun `a typo result announces itself so the tab does not claim an exact match`() {
        val local=CatalogueIndex(listOf(Series("1","分手四年，傅机长失了控")))
        val repo=repository { searchPage() }
        val result=repo.search("分手四年傅机长失控",1,ContentType.SHORT,local)
        assertEquals(listOf("1"),result.items.map { it.id })
        assertTrue(result.fuzzy)
        assertEquals(1,result.matchedCount)
        assertFalse("容错结果不该再说「站内没有」",result.matchedCount==0)
    }

    @Test fun searchNeverCallsTheRefusedAppEndpoint() {
        val hosts=mutableSetOf<String>()
        val repo=repository { request -> hosts+=request.url.host; searchPage() }
        repo.search("测试")
        assertEquals(setOf("hongguoduanju.com"),hosts)
    }

    @Test fun aServerSearchThatChangedShapeDoesNotSinkTheLocalSearch() {
        // 站方那一路取不到时，搜索**不能**跟着一起完蛋：它只是补位用的十行，主体是本机索引。
        // 2026-10-22 实测站方把搜索页改成了纯前端渲染，HTML 里不再有 _ROUTER_DATA，
        // 这一路从此取不到 —— 搜索照常用本机索引出结果，页面照常有东西看。
        val local=CatalogueIndex((1..70).map { Series((100+it).toString(),"总裁归来第${it}季") })
        val result=repository { "<html>没有数据</html>" }.search("总裁归来",1,ContentType.SHORT,local)
        assertEquals(ContentRepository.SEARCH_PAGE_SIZE,result.items.size)
        assertTrue(result.hasMore)
        assertTrue(result.serverFailed)
        // 命中数是本机索引数出来的，不是站方告诉我们的 —— 界面据此不能说「站内没有」。
        assertEquals(70,result.matchedCount)
        assertEquals(70,result.totalHits)
    }

    @Test fun aServerSearchThatIsGoneStillLeavesTheLocalIndexToAnswer() {
        // 站方搜索取不到、而且本机也没有索引时：页面要如实说是「站方搜索暂时不可用」，
        // 不能显示成「没有找到相关剧」—— 那部剧可能真的存在，只是我们没问到站方。
        val result=repository { "<html>没有数据</html>" }.search("庆余年")
        assertTrue(result.items.isEmpty())
        assertTrue(result.serverFailed)
        assertEquals(0,result.matchedCount)
    }

    @Test fun aWorkingServerSearchIsNotReportedAsBroken() {
        val repo=repository { searchPage(searchRow("456","测试短剧",12)) }
        val result=repo.search("测试")
        assertEquals("456",result.items.single().id)
        assertFalse(result.serverFailed)
    }

    @Test fun theServerIsAskedOnTheFirstPageOnly() {
        // 站方那一路每搜一次只问第一页：翻页是本机索引的事。
        val asked=mutableListOf<String>()
        val repo=repository { request -> asked+=request.url.encodedPath+"?"+request.url.query.orEmpty(); searchPage(searchRow("456","测试短剧")) }
        val local=CatalogueIndex(listOf(Series("1","测试短剧第二季")))
        repo.search("测试",1,ContentType.SHORT,local)
        repo.search("测试",2,ContentType.SHORT,local)
        assertEquals(1,asked.size)
    }

    @Test fun homeStopsOnTheLastPageOfANamedCatalogue() {
        val repo=repository { request ->
            assertEquals("/category/real-drama",request.url.encodedPath)
            assertNull(request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    /**
     * 站方有三条能播的货架，用户挑的是两档：`ai-drama` 是 800 部 AI 剧，并进「漫剧」而不是单开一档。
     *
     * `/category/comic`（漫画，74 部）故意不列进来：它确实有 `vid_list`，但那些 id 是漫画章节而不是
     * 视频 id，签名播放接口对试过的八部全部回 HTTP 500 —— 一整页死路。
     *
     * 落盘的 id 沿用旧的（short / comic / ai），所以电视上已有的索引和断点继续有效，只需补走
     * AI 剧那一档的 34 页，而不是把 102 页重走一遍。
     */
    @Test fun browseableCataloguesAreExactlyTheRoutesWeOffer() {
        assertEquals(listOf("real-drama","comic-drama","ai-drama"),Route.entries.map { it.categorySlug })
        assertEquals(Route.entries.size,Route.entries.map { it.categorySlug }.distinct().size)
        assertEquals(Route.entries.size,Route.entries.map { it.storedValue }.distinct().size)
        assertFalse("漫画 的分集不是视频 id，放进来是死路", Route.entries.any { it.categorySlug=="comic" })
        // 用户挑的是两档，不是三条货架。
        assertEquals(listOf("真人剧","漫剧"),ContentType.entries.map { it.label })
        assertEquals(listOf(ContentType.SHORT,ContentType.COMIC),Route.entries.map { it.type }.distinct())
        assertEquals(listOf(Route.REAL_DRAMA),ContentType.SHORT.routes)
        assertEquals(listOf(Route.COMIC_DRAMA,Route.AI_DRAMA),ContentType.COMIC.routes)
        // 旧索引的落盘 id 不能变，否则电视上已有的 1600 部索引要重走一遍。
        assertEquals(listOf("short","comic","ai"),Route.entries.map { it.storedValue })
    }

    @Test fun everyRouteBrowsesItsOwnNamedRoute() {
        val seen=mutableListOf<String>()
        val repo=repository { request ->
            seen += request.url.encodedPath
            assertNull(request.url.queryParameter("tab"))
            val route=Route.entries.single { request.url.encodedPath=="/category/${it.categorySlug}" }
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"测试${route.label}"}],"pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        for(route in Route.entries) {
            val page=repo.routePage(route=route)
            assertEquals("测试${route.label}",page.items.single().title)
            assertTrue(page.hasMore)
        }
        assertEquals(Route.entries.size,seen.distinct().size)
    }

    @Test fun browsingTheComicShelfShowsBothOfItsRoutes() {
        // 漫剧这一档由 comic-drama 和 ai-drama 两条货架拼成：两条各自的排序原样保留，拼接而不是
        // 交错，所以第一条货架自己的热门序仍然排在最前面。
        val repo=repository { request ->
            val route=Route.entries.single { request.url.encodedPath=="/category/${it.categorySlug}" }
            val id=Route.entries.indexOf(route)+1
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"$id","series_name":"测试${route.label}"}],"pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        val result=repo.home(1,ContentType.COMIC)
        assertEquals(listOf("测试漫剧","测试AI剧"),result.items.map { it.title })
        assertTrue(result.hasMore)
        // 真人剧这一档只有一条货架，不该被拼上什么。
        val single=repo.home(1,ContentType.SHORT)
        assertEquals(listOf("测试真人剧"),single.items.map { it.title })
    }

    @Test fun aBrowsedPageKnowsHowManyPagesThereAre() {
        // 站方给的是它自己的页数（实测 2026-10-20：三条货架都是 800 部、24 条一页、34 页）。
        // App 把这些条画在自己的网格里，从屏幕上的条数去数页数，数出来的是另一个数 ——
        // 所以这个数必须一路从 core 带上来，否则页码选择器在首页上永远只会说「只有一页」。
        val repo=repository { request ->
            val route=Route.entries.single { request.url.encodedPath=="/category/${it.categorySlug}" }
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"测试${route.label}"}],"pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        assertEquals(34,repo.routePage().totalPages)
        assertEquals(34,repo.home(1,ContentType.SHORT).totalPages)
        // 漫剧这一档是两条货架拼起来的：总页数取短的那条，因为两条是齐步走的。
        assertEquals(34,repo.home(1,ContentType.COMIC).totalPages)
    }

    @Test fun theLastPageIsTheLastPage() {
        // 「还有下一页」和「一共有几页」必须是同一个数：否则页码选择器能翻到的最后一页，
        // 和「下一页」按到底的那一页，会打起来。
        val repo=repository { request ->
            val page=request.url.queryParameter("page")?.toInt() ?: 1
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"测试"}],"pagination":{"total":800,"pageNum":$page,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        assertTrue(repo.home(33,ContentType.SHORT).hasMore)
        assertFalse(repo.home(34,ContentType.SHORT).hasMore)
    }

    @Test fun browsingTheComicCatalogueNoLongerReturnsHotBoardMetadata() {
        val repo=repository { request ->
            assertTrue(request.url.encodedPath in setOf("/category/comic-drama","/category/ai-drama"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试漫剧"}],"pagination":{"totalPages":2}}}};</script>"""
        }
        val result=repo.home(1,ContentType.COMIC)
        assertNull(result.ranking)
        assertTrue(result.hasMore)
    }

    @Test fun homeSendsThePageNumberOnlyAfterTheFirstOne() {
        val queries=mutableListOf<String?>()
        val repo=repository { request ->
            queries += request.url.queryParameter("page")
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试"}],"pagination":{"totalPages":34}}}};</script>"""
        }
        repo.home(1,ContentType.COMIC); repo.home(2,ContentType.COMIC); repo.home(12,ContentType.COMIC)
        // 漫剧这一档由两条货架拼成，所以每次翻页各请求一次；第一页不带 page 参数。
        assertEquals(listOf(null,null,"2","2","12","12"),queries)
    }

    @Test fun searchSkipsRowsWithoutAUsableSeriesId() {
        val repo=repository { searchPage("""{"video_data":{"series_title":"没有ID"}}""",searchRow("456","测试短剧"),"""{"name":"只有名字"}""") }
        assertEquals(listOf("456"),repo.search("测试").items.map { it.id })
    }

    @Test fun seasonNumberReadsArabicChineseAndBracketedMarkers() {
        assertEquals(7,seasonNumber("一梦青玄第七季"))
        assertEquals(2,seasonNumber("错嫁藏拙天作之合（第二季）"))
        assertEquals(3,seasonNumber("错嫁藏拙天作之合(第三季)"))
        assertEquals(4,seasonNumber("某剧【第四季】"))
        assertEquals(12,seasonNumber("某剧第十二季"))
        assertEquals(102,seasonNumber("某剧第一百零二季"))
        assertEquals(20,seasonNumber("某剧第二十季"))
        assertEquals(null,seasonNumber("错嫁藏拙天作之合"))
    }

    @Test fun seriesBaseTitleStripsOnlyTheSeasonMarker() {
        assertEquals("错嫁藏拙天作之合",seriesBaseTitle("错嫁藏拙天作之合（第二季）"))
        assertEquals("一梦青玄，团宠小师妹一剑毁天灭地",seriesBaseTitle("一梦青玄，团宠小师妹一剑毁天灭地第七季"))
        assertEquals("某剧",seriesBaseTitle("某剧"))
    }

    @Test fun seasonsFindsSiblingsAndSortsThemBySeasonNumber() {
        val repo=repository { searchPage(
            searchRow("1","错嫁藏拙天作之合（第三季）"),
            searchRow("2","错嫁藏拙天作之合",99),
            searchRow("3","错嫁藏拙天作之合（第二季）"),
        ) }
        val seasons=repo.seasons(Series("2","错嫁藏拙天作之合"))
        assertEquals(listOf(1,2,3),seasons.map { it.season })
        assertEquals(listOf("2","3","1"),seasons.map { it.series.id })
    }

    @Test fun seasonsTreatsAnUnmarkedTitleAsTheFirstSeason() {
        val repo=repository { searchPage(searchRow("1","某剧第二季"),searchRow("2","某剧")) }
        assertEquals(listOf(1,2),repo.seasons(Series("2","某剧")).map { it.season })
    }

    @Test fun seasonsKeepsSimilarlyNamedShowsApart() {
        // 「变废为宝」和「捡废为宝」都有第二季，但它们是两部不同的剧，不能合并。
        val repo=repository { searchPage(searchRow("1","变废为宝：大佬哥哥们抢着捡垃圾第二季"),searchRow("2","捡废为宝：四位大佬蹲我小店打杂第二季"),searchRow("3","变废为宝：大佬哥哥们抢着捡垃圾")) }
        val seasons=repo.seasons(Series("3","变废为宝：大佬哥哥们抢着捡垃圾"))
        assertEquals(listOf("3","1"),seasons.map { it.series.id })
    }

    @Test fun seasonsReturnsEmptyForASingleSeasonShow() {
        val repo=repository { searchPage(searchRow("1","独播新剧")) }
        assertTrue(repo.seasons(Series("1","独播新剧")).size==1)
    }

    @Test fun seasonsSwallowsSearchFailuresInsteadOfSurfacingThem() {
        val repo=repository { "<html>没有数据</html>" }
        assertTrue(repo.seasons(Series("1","某剧")).isEmpty())
    }
}
