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

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicRankingIsReachedDirectlyRatherThanThroughBrowsing() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.comicRanking(2)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.comicRanking(1).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.comicRanking(1) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.comicRanking(1) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.comicRanking(2)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    private fun searchPage(vararg rows: String) =
        """<script>_ROUTER_DATA={"loaderData":{"search_(keyword)/page":{"searchList":[${rows.joinToString(",")}]}}};</script>"""

    private fun searchRow(id: String, title: String, episodes: Int = 8) =
        """{"video_data":{"series_id":"$id","series_title":"$title","episode_cnt":$episodes}}"""

    @Test fun searchReadsSeriesIdFromNestedVideoData() {
        val repo=repository { searchPage(searchRow("456","测试短剧",12)) }
        val result=repo.search("测试",1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertEquals("测试短剧",result.items.single().title)
        assertEquals("全 12 集",result.items.single().badge)
    }

    @Test fun searchNeverAdvertisesANextPageBecauseTheSiteRendersOnlyPageOne() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            assertEquals("1",request.url.queryParameter("page"))
            searchPage(searchRow("456","测试短剧"))
        }
        assertFalse(repo.search("测试",1).hasMore)
        // The public page ignores ?page=, so a second request must never be made for one.
        assertTrue(repo.search("测试",2).items.isEmpty())
        assertEquals(1,paths.size)
    }

    @Test fun comicSearchUsesTheSameWorkingSourceAsShortDrama() {
        val repo=repository { searchPage(searchRow("999","测试漫剧")) }
        assertEquals(listOf("999"),repo.search("测试",1,ContentType.COMIC).items.map { it.id })
    }

    @Test fun searchNeverCallsTheRefusedAppEndpoint() {
        val hosts=mutableSetOf<String>()
        val repo=repository { request -> hosts+=request.url.host; searchPage() }
        repo.search("测试")
        assertEquals(setOf("hongguoduanju.com"),hosts)
    }

    @Test fun searchRejectsUnexpectedRouterShape() {
        assertThrows(IOException::class.java) { repository { "<html>没有数据</html>" }.search("测试") }
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
     * The four catalogues are disjoint on the site (measured pairwise overlap: zero), so each must
     * ask for its own route. A shared path here would silently collapse them back into one list —
     * which is exactly the bug that hid 1600 shows.
     */
    /**
     * `/category/comic` (漫画, 74 shows) is intentionally not browsable: it does list a `vid_list`,
     * but those ids are comic chapters rather than video — the signed player endpoint answers
     * HTTP 500 for every show tried. `ai-drama` is browsable and was offered in 0.9.14, but browsing
     * it did not pay for itself, so 0.9.15 offers two. Both decisions are pinned here on purpose so
     * nobody re-adds a tab without re-measuring first.
     */
    @Test fun browseableCataloguesAreExactlyTheTwoWeOffer() {
        assertEquals(listOf("real-drama","comic-drama"),ContentType.entries.map { it.categorySlug })
        assertEquals(ContentType.entries.size,ContentType.entries.map { it.categorySlug }.distinct().size)
        assertEquals(ContentType.entries.size,ContentType.entries.map { it.label }.distinct().size)
        assertEquals(ContentType.entries.size,ContentType.entries.map { it.storedValue }.distinct().size)
        assertFalse("漫画 的分集不是视频 id，放进来是死路", ContentType.entries.any { it.categorySlug=="comic" })
    }

    @Test fun everyCatalogueBrowsesItsOwnNamedRoute() {
        val seen=mutableListOf<String>()
        val repo=repository { request ->
            seen += request.url.encodedPath
            assertNull(request.url.queryParameter("tab"))
            val type=ContentType.entries.single { request.url.encodedPath=="/category/${it.categorySlug}" }
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"1","series_name":"测试${type.label}"}],"pagination":{"total":800,"pageNum":1,"pageSize":24,"totalPages":34}}}};</script>"""
        }
        for(type in ContentType.entries) {
            val page=repo.home(type=type)
            assertEquals("测试${type.label}",page.items.single().title)
            assertTrue(page.hasMore)
        }
        assertEquals(ContentType.entries.size,seen.distinct().size)
    }

    @Test fun browsingTheComicCatalogueNoLongerReturnsHotBoardMetadata() {
        val repo=repository { request ->
            assertEquals("/category/comic-drama",request.url.encodedPath)
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
        assertEquals(listOf(null,"2","12"),queries)
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
