package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.PrintWriter

/**
 * The largest response a probe reads.
 *
 * A probe is a diagnostic: it must not be the thing that dies. [okhttp3.ResponseBody.bytes]
 * reads the whole body into memory, so a body of any size takes the process with it.
 */
private const val MAX_PROBE_BODY=16L*1024*1024

/**
 * Reads a response body, refusing one that is too large.
 *
 * The length is checked while reading, so a body that does not announce its size is caught too.
 */
private fun readBody(response: okhttp3.Response): ByteArray {
    val body=response.body ?: return ByteArray(0)
    if(body.contentLength()>MAX_PROBE_BODY) throw IOException("响应体过大（${body.contentLength()} 字节）")
    val out=java.io.ByteArrayOutputStream(); val input=body.byteStream(); val buffer=ByteArray(64*1024)
    while(true) {
        val n=input.read(buffer); if(n<0) break
        if(out.size()+n>MAX_PROBE_BODY) throw IOException("响应体超过 $MAX_PROBE_BODY 字节")
        out.write(buffer,0,n)
    }
    return out.toByteArray()
}

/**
 * Where the probe's report goes.
 *
 * stdout alone is not enough on Windows: the JVM encodes it in the console's code page, so a
 * redirected run leaves a file of mojibake, and a report nobody can read is the one thing a
 * diagnostic must not produce. The console keeps what it can show, and the same lines are also
 * written to a UTF-8 file that can be read, diffed and pasted into an issue.
 */
class ProbeReport(val path: String = "probe-report.txt") {
    private val file = File(path)
    private val writer: PrintWriter = PrintWriter(file.bufferedWriter(Charsets.UTF_8))
    fun line(text: String) {
        println(text)
        writer.println(text)
        writer.flush()
    }
    fun close() = writer.close()
}

fun main(args: Array<String>) {
    val report = ProbeReport()
    try {
        run(args, report)
    } finally {
        report.close()
        println("报告已写入 ${report.path}")
    }
}

private fun run(args: Array<String>, report: ProbeReport) {
    val repository=ContentRepository()
    val say = report::line

    // Re-measures the signed App search endpoint, the one path that could have lifted the ten-row
    // ceiling on the public page.
    //
    // Measured 2026-10-18: it answers HTTP 200 with a zero-byte body for every parameter shape
    // tried - 8 GET and 5 POST variants across 3 hosts and 6 candidate paths, all of the others
    // 404 outright - while video_model/v1/ returns ~24 KB of code=0 in the same process. An empty
    // search body is therefore the endpoint refusing us, not a wrong parameter, a stale device
    // identity or a broken signature. That is why [CatalogueIndex] exists.
    //
    // The control is not optional. Both endpoints answer 200, and a banned fingerprint is also a
    // 200 with an empty body, so without a known-good request in the same run there is nothing to
    // tell "refused" apart from "we got blocked".
    if(args.contains("--appsearch")) {
        val signer=Signer()
        val keyword=args.firstOrNull { !it.startsWith("--") } ?: "总裁"
        val http=repository.http

        fun call(label: String,url: String,headers: Map<String,String>,body: ByteArray?) {
            try {
                val request=okhttp3.Request.Builder().url(url)
                headers.forEach { (k,v) -> request.header(k,v) }
                body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
                val bytes=http.newCall(request.build()).execute().use { response -> readBody(response) }
                say("[$label] bytes=${bytes.size} :: ${String(bytes,0,minOf(160,bytes.size)).replace(Regex("\\s+")," ")}")
            } catch(e: Exception) {
                say("[$label] FAILED ${e.javaClass.simpleName}: ${e.message?.take(110)}")
            }
        }

        fun control(): String {
            val sample=repository.home(1,ContentType.SHORT).items.firstOrNull() ?: return "no catalogue row"
            val episode=repository.detail(sample.id).episodes.firstOrNull() ?: return "no episode id"
            val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"video_id":"$episode"}""".toByteArray()
            val signed=signer.sign(ContentRepository.VIDEO_MODEL_API,VendorConstants.newDevice(),payload)
            call("control video_model",signed.url,signed.headers,payload)
            return repository.stream(sample.id,episode).let { "stream OK ${it.quality}" }
        }
        say("control: ${control()}")

        val base="https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/tab/v/"
        // The url and the headers must come from one signing pass: x-medusa embeds the device_id
        // that is also in the query, and a mismatch makes the server drop the response silently.
        val getSigned=signer.sign(base,VendorConstants.newDevice().toMutableMap().apply { put("query",keyword) })
        call("GET query",getSigned.url,getSigned.headers,null)
        val body="""{"query":"$keyword","offset":0,"count":20}""".toByteArray()
        val postSigned=signer.sign(base,VendorConstants.newDevice(),body)
        call("POST body",postSigned.url,postSigned.headers,body)

        say("control again: ${control()}")
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        return
    }

    // Reproduces one keyword through the exact code path the TV app uses, with and without the
    // local index. A user report of 「搜不到某剧」 is only decidable by running the real search():
    // the site answers every keyword with ten rows, so "no results" and "the wrong ten rows" look
    // identical on screen.
    //
    // --search=<keyword> prints, for the server alone and for server+index:
    //   - what the local index holds for the word (this is the part that can silently miss a show
    //     that the site itself lists in its search results but not in any browsable catalogue)
    //   - whether the server's rows mention the word, and where the exact match lands in the page
    if(args.any { it.startsWith("--search=") }) {
        val keyword=args.first { it.startsWith("--search=") }.removePrefix("--search=")
        try {
            val server=repository.search(keyword)
            say("关键词：$keyword")
            say("仅服务端：${server.items.size} 条，matched=${server.matchedCount}，total=${server.totalHits}")
            server.items.forEachIndexed { i,row -> say("  #${i+1} ${row.id} ${row.title}") }

            val walk=CatalogueBuilder(repository).build()
            val index=CatalogueIndex(walk.shows)
            val (local,fuzzy)=index.searchAllowingTypo(keyword)
            say("全库索引：${index.size} 部，本地命中=${local.size}（fuzzy=$fuzzy），目录页里有吗=${local.any { it.title.contains(keyword) }}")
            val merged=repository.search(keyword,1,ContentType.SHORT,index)
            say("合并后第1页：${merged.items.size} 条，hasMore=${merged.hasMore}，matched=${merged.matchedCount}，total=${merged.totalHits}")
            merged.items.forEachIndexed { i,row -> say("  #${i+1} ${row.id} ${row.title}") }
            val exact=merged.items.indexOfFirst { it.title.contains(keyword) }
            say(if(exact>=0) "精确命中在第 ${exact+1} 条" else "精确命中不在第 1 页里")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    // End-to-end proof for the local catalogue index: walk everything, then search it. Prints the
    // size of the index on disk too, because the ceiling that matters on a low-end box is whether
    // the JSON still fits the preference file and the 7-day cache.
    if(args.contains("--indexresume")) {
        // Stops the walk partway, stores the bookmark, and walks again from it. This is the
        // failure the whole resumable branch exists for: on a projector the 102 serial requests
        // outlast one session, so an index that cannot resume never finishes and is never worth
        // storing. --indexresume <pages> says how many pages the first pass is allowed.
        val stopAfter=args.getOrNull(args.indexOf("--indexresume")+1)?.toIntOrNull() ?: 6
        try {
            val started=System.currentTimeMillis()
            var pages=0
            val first=CatalogueBuilder(repository).build(onPage={ pages++ },isCancelled={ pages>=stopAfter })
            val firstElapsed=(System.currentTimeMillis()-started)/1000
            say("第一趟：${first.shows.size} 部，用时 ${firstElapsed}s，断点 ${first.cursor}，已完成 ${first.done.map { it.storedValue }}${if(first.gaps.isEmpty()) "" else "，未取到 ${first.gaps.joinToString("；")}"}")

            val json=CatalogueIndexDisk.encode(first.shows,first.routes,first.cursor,first.done,System.currentTimeMillis())
            val saved=checkNotNull(CatalogueIndexDisk.decode(json,System.currentTimeMillis())) { "断点没能落盘" }

            val resumedAt=System.currentTimeMillis()
            val again=CatalogueBuilder(repository).build(resume=saved.cursor,done=saved.done)
            say("第二趟：只新增 ${again.shows.size} 部，用时 ${(System.currentTimeMillis()-resumedAt)/1000}s -> 合计 ${(first.shows+again.shows).size} 部，断点 ${again.cursor}，complete=${again.complete}")

            val together=CatalogueIndex(first.shows+again.shows)
            val q="总裁"
            val full=repository.search(q,1,ContentType.SHORT,together)
            say("续建后搜「$q」：本地命中 ${together.search(q,400).size}，第1页 ${full.items.size} 条，hasMore=${full.hasMore}，total=${full.totalHits}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    if(args.contains("--index")) {
        try {
            val started=System.currentTimeMillis()
            var last=0
            val walk=CatalogueBuilder(repository).build(onPage={ step ->
                if(step.total-last>=400) { last=step.total; say("  ... 已收录 ${step.total} 部，用时 ${(System.currentTimeMillis()-started)/1000}s") }
            })
            val entries=walk.shows
            say("索引建完：本轮 ${entries.size} 部，用时 ${(System.currentTimeMillis()-started)/1000}s，断点 ${walk.cursor}，已完成 ${walk.done.map { it.storedValue }}${if(walk.gaps.isEmpty()) "" else "，未取到 ${walk.gaps.joinToString("；")}"}")
            // 带标签的索引，才是应用真正用的那一个：没标签的索引里每一行都算「哪边都算」，
            // 分档的数字会全等于全库。
            val index=CatalogueIndex(entries,walk.routes)
            for(q in listOf("大明","总裁","雾锁情迷","修仙","庆余年第二季")) {
                val serverOnly=repository.search(q)
                val merged=repository.search(q,1,ContentType.SHORT,index)
                say("  「$q」 本地命中=${index.search(q,5000).size} | 服务端=${serverOnly.items.size} 条 matched=${serverOnly.matchedCount} | 合并后第1页=${merged.items.size} hasMore=${merged.hasMore} matched=${merged.matchedCount} total=${merged.totalHits}")
            }
            // 错字：站内剧名打错一个字仍要能找到，且要标记成容错。
            for(q in listOf("雾锁请迷","首夫娇娘","谁让这个文盲修仙的")) {
                val (hits,fuzzy)=index.searchAllowingTypo(q)
                say("  错字「$q」 fuzzy=$fuzzy 命中=${hits.size} top=${hits.take(3).map { it.title }}")
            }
            val json=CatalogueIndexDisk.encode(entries,walk.routes,walk.cursor,walk.done,System.currentTimeMillis())
            val decoded=CatalogueIndexDisk.decode(json,System.currentTimeMillis())
            say("磁盘：${json.length} 字符（上限 1500000），解码后 ${decoded?.size} 部，断点 ${decoded?.cursor}，回搜雾锁情迷 -> ${decoded?.index?.search("雾锁情迷")?.firstOrNull()?.title}")
            // 「随便看看」按分类翻页，两个档各自从自己的首页开始 —— 分类按钮全靠这个。
            for(type in ContentType.entries) {
                val total=decoded?.index?.sizeOf(type) ?: 0
                val first=decoded?.index?.browse(0,6,type).orEmpty().map { it.title }
                val pageSize=ContentRepository.SEARCH_PAGE_SIZE
                say("  ${type.label}：$total 部 -> ${(total+pageSize-1)/pageSize} 页，首屏 ${first.joinToString("、")}")
            }
            // 分档之后，每条货架各有多少部才是真正要看的数字：漫剧这一档是 comic-drama + ai-drama。
            for(route in Route.entries) {
                val count=entries.indices.count { walk.routes.getOrNull(it)==route }
                say("  ${route.categorySlug}（${route.label} -> ${route.type.label}）：$count 部")
            }
            // 题材词在两档之间的分布 —— 「分开搜」存在的理由，数字得跟着索引走，不能写死在注释里。
            for(q in listOf("都市","穿越","重生","总裁","修仙")) {
                val all=index.search(q,5000).size
                val short=index.search(q,5000,ContentType.SHORT).size
                val comic=index.search(q,5000,ContentType.COMIC).size
                say("  题材「$q」：全库 $all 部（真人剧 $short，漫剧 $comic）")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    // How long a signed stream URL stays usable.
    //
    // The next episode is now prepared while the current one is still playing, so the URL it holds
    // was signed a whole episode ago - and longer still if the user paused. If the CDN stops serving
    // that URL before the next episode starts, the prepared episode fails, the user sees a loading
    // step anyway, and this time with an error on top. The prepared resource's own lifetime (see
    // PrefetchPolicy.TTL_MS) has to stay under the URL's real one, so measure it instead of guessing.
    //
    // --urllife [minutes] resolves one URL and then asks for its first kilobyte every minute, the way
    // the player does, until the answer stops being 206. The first non-206 is the cliff.
    if(args.contains("--urllife")) {
        val minutes=args.getOrNull(args.indexOf("--urllife")+1)?.toIntOrNull() ?: 30
        try {
            val sample=repository.home(1,ContentType.SHORT).items.firstOrNull() ?: error("首页没有剧集")
            val episode=repository.detail(sample.id).episodes.firstOrNull() ?: error("没有分集")
            val stream=repository.stream(sample.id,episode)
            say("取到地址：${sample.title} 画质=${stream.quality}")
            val started=System.currentTimeMillis()
            var alive=true
            for(minute in 0..minutes) {
                val age=(System.currentTimeMillis()-started)/1000
                val answer=runCatching {
                    val request=okhttp3.Request.Builder().url(stream.url)
                        .header("User-Agent",VendorConstants.VIDEO_UA)
                        .header("Referer","https://novel.snssdk.com/")
                        .header("Range","bytes=0-1023")
                        .header("Accept-Encoding","identity").build()
                    repository.http.newCall(request).execute().use { response -> response.code to (response.body?.bytes()?.size ?: 0) }
                }.getOrElse { (0 to 0) }
                say("t=${age}s http=${answer.first} bytes=${answer.second}")
                if(answer.first!=206) { alive=false; break }
                Thread.sleep(60_000)
            }
            say(if(alive) "URL 在 $minutes 分钟内一直是 206" else "URL 在 ${(System.currentTimeMillis()-started)/1000}s 之后不再返回 206")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    if(args.contains("--ranking")) {
        try {
            // 两档都量：真人剧档一个榜，漫剧档两个榜合成一张。名次必须连续且递增 ——
            // 合成后的名次是这一档自己的热度序，不是两个榜各自的第几名。
            for(type in ContentType.entries) {
                val list=repository.rankingList(type)
                val metadata=checkNotNull(HotRank.page(list,1,18).ranking)
                check(list.items.isNotEmpty())
                val seen=mutableSetOf<String>()
                for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                    val result=HotRank.page(list,pageNumber,18)
                    val ranking=checkNotNull(result.ranking)
                    check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                    seen.addAll(result.items.map { it.id })
                    val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                    check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                    check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                    check(result.hasMore==(pageNumber<ranking.totalPages))
                    val heats=result.items.map { HotRank.heatValue(ranking.positions[it.id]!!.heat) }
                    say("${type.label}榜 page=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; heat=${heats.filterNotNull().firstOrNull()}..${heats.filterNotNull().lastOrNull()}; first=${result.items.first().title}; updated=${ranking.updatedText}")
                }
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    // Browsing alone does not prove a catalogue is watchable, so probe playback on its own and
    // keep going after a failure: a catalogue that lists episodes but cannot stream any of them
    // must not ship as a browse tab.
    if(args.any { it.startsWith("--play=") }) {
        val slug=args.first { it.startsWith("--play=") }.removePrefix("--play=")
        val route=Route.entries.singleOrNull { it.categorySlug==slug }
        require(route!=null) { "未知分类 $slug，可用：${Route.entries.joinToString("、") { it.categorySlug }}" }
        try {
            var ok=0; var bad=0
            val ids=repository.routePage(1,route).items.map { it.id }
            for(id in ids.take(8)) {
                try {
                    val detail=repository.detail(id)
                    val stream=repository.stream(detail.series.id,detail.episodes.first())
                    say("streamUrl=${stream.url}")
                    RemoteVideo(repository.http,stream).use { remote ->
                        remote.prepare(); val head=remote.read(0,1024*1024)
                        ok++; say("play OK  $id ${detail.series.title} 集数=${detail.episodes.size} 画质=${stream.quality} 字节=${head.size}")
                    }
                } catch(e: Exception) {
                    bad++; say("play FAIL $id ${e.javaClass.simpleName}: ${e.message?.take(90)}")
                }
            }
            say("playSummary catalog=$slug 成功=$ok 失败=$bad")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    // Asks whether one specific episode of one specific show can actually be played.
    //
    // The site's search names two different shows under one title (measured 2026-10-23:
    // 《亲姐是末世女主,我才是苟王》 exists as a 126-episode entry and as a 341-episode one,
    // with different stories and different episode lists). The catalogue pages carry
    // neither, so only the search reaches them. Whether an episode streams is a different
    // question from whether the site lists it, and the answer decides whether the search
    // should surface the second one at all.
    //
    // --stream=<series_id>,<video_id> asks that question for one episode.
    if(args.any { it.startsWith("--stream=") }) {
        val spec=args.first { it.startsWith("--stream=") }.removePrefix("--stream=").split(",")
        require(spec.size==2) { "用法：--stream=<series_id>,<video_id>" }
        val seriesId=spec[0].trim(); val videoId=spec[1].trim()
        try {
            val detail=repository.detail(seriesId)
            say("title=${detail.series.title} 集数=${detail.episodes.size} 首集=${detail.episodes.firstOrNull()} 末集=${detail.episodes.lastOrNull()}")
            say("要问的分集在不在这一部的分集表里：${videoId in detail.episodes}")
            val stream=repository.stream(seriesId,videoId)
            say("streamUrl=${stream.url}")
            RemoteVideo(repository.http,stream).use { remote ->
                remote.prepare(); val head=remote.read(0,1024*1024)
                say("play OK  $seriesId/$videoId 画质=${stream.quality} 字节=${head.size}")
            }
        } catch(e: Exception) {
            say("play FAIL $seriesId/$videoId ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    // Walks every route end to end: two pages, a detail fetch and a real CDN byte read each.
    // This is the only way to prove the named routes are genuinely distinct and playable.
    if(args.contains("--catalogs")) {
        try {
            val seen=mutableMapOf<String,MutableSet<String>>()
            for(route in Route.entries) {
                val first=repository.routePage(1,route)
                check(first.items.isNotEmpty()) { "${route.label} 首页没有返回剧集" }
                val second=repository.routePage(2,route)
                val overlap=first.items.map { it.id }.intersect(second.items.map { it.id }.toSet())
                check(overlap.isEmpty()) { "${route.label} 第1页和第2页重复: $overlap" }
                check(first.hasMore) { "${route.label} 首页没有给出下一页" }
                val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: first.items.first().id)
                check(detail.episodes.isNotEmpty()) { "${route.label} ${detail.series.title} 没有分集" }
                val stream=repository.stream(detail.series.id,detail.episodes.first())
                RemoteVideo(repository.http,stream).use { remote ->
                    remote.prepare(); val head=remote.read(0,1024*1024)
                    check(head.size>0) { "${route.label} ${detail.series.title} 拉不到视频字节" }
                    seen.getOrPut(route.categorySlug){mutableSetOf()}.addAll(first.items.map { it.id })
                    say("catalog=${route.categorySlug}(${route.label} -> ${route.type.label}); p1=${first.items.size}; p2=${second.items.size}; episodes=${detail.episodes.size}; quality=${stream.quality}; bytes=${head.size}; first=${detail.series.title}")
                }
            }
            // Equal page sizes prove nothing about identity; only shared ids do.
            val keys=seen.keys.toList()
            for(i in keys.indices) for(j in i+1 until keys.size) {
                val shared=seen.getValue(keys[i]).intersect(seen.getValue(keys[j]))
                check(shared.isEmpty()) { "${keys[i]} 与 ${keys[j]} 共享剧集: $shared" }
            }
            say("catalogsOk=${seen.entries.joinToString { "${it.key}:${it.value.size}" }}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    // Whether the platform still serves danmaku for a short drama, and under which signature.
    //
    // The comment endpoint is not the player endpoint: it wants `comment-source`/`server-channel`
    // and an empty `x-ss-stub`, and the reference danmu client signs it with metasec-style
    // X-Argus/X-Gorgon while this project signs with the drpys-style one. Every shape is tried
    // in one run instead of guessing, and a known-good player request rides along as the control:
    // a banned fingerprint also answers HTTP 200 with an empty body, so "refused" and "we got
    // blocked" look identical without it.
    if(args.contains("--danmu")) {
        val signer=Signer()
        fun call(label: String,url: String,headers: Map<String,String>,body: ByteArray?): ByteArray {
            return try {
                val request=okhttp3.Request.Builder().url(url)
                headers.forEach { (k,v) -> request.header(k,v) }
                body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
                val bytes=repository.http.newCall(request.build()).execute().use { response -> readBody(response) }
                say("[$label] bytes=${bytes.size} :: ${String(bytes,0,minOf(200,bytes.size)).replace(Regex("\\s+")," ")}")
                bytes
            } catch(e: Exception) {
                say("[$label] FAILED ${e.javaClass.simpleName}: ${e.message?.take(110)}")
                ByteArray(0)
            }
        }
        try {
            val sample=repository.home(1,ContentType.SHORT).items.firstOrNull() ?: error("首页没有剧集")
            val detail=repository.detail(sample.id)
            val vid=detail.episodes.firstOrNull() ?: error("没有分集")
            val seriesId=detail.series.id
            say("样本：${detail.series.title} series=$seriesId vid=$vid 集数=${detail.episodes.size}")

            // The control: the very same signer that resolves a playable URL for this episode.
            val modelPayload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"video_id":"$vid"}""".toByteArray()
            val modelSigned=signer.sign(ContentRepository.VIDEO_MODEL_API,VendorConstants.newDevice(),modelPayload)
            call("control video_model",modelSigned.url,modelSigned.headers,modelPayload)

            val body=JSONObject()
                .put("comment_source",601).put("server_channel",1000)
                .put("group_id",vid).put("group_type",30).put("comment_type",20).put("sort",1)
                .put("business_param",JSONObject()
                    .put("book_id",seriesId).put("start_offset_time",0)
                    .put("playlet_item_duration",600000)
                    .put("need_danmaku_guide_type",JSONArray(listOf(1,3,4,2))))
                .put("count",90).put("cursor","").put("aid",8662).put("compliance_status",0)
                .toString().toByteArray()
            val commentApi="https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/$vid/v1/"

            val base=mapOf("comment-source" to "601","server-channel" to "1000")
            // A: signed, empty x-ss-stub (what the reference client sends)
            val signedEmpty=signer.sign(commentApi,VendorConstants.newDevice(),body)
            val answer=call("A signed stub-empty",signedEmpty.url,signedEmpty.headers+base+mapOf("x-ss-stub" to ""),body)
            // What a danmaku row actually looks like, so the renderer is written against the real
            // shape rather than against the reference client's idea of it.
            if(answer.isNotEmpty()) {
                runCatching {
                    val json=JSONObject(String(answer))
                    val data=json.optJSONObject("data") ?: error("no data")
                    val list=data.optJSONArray("data_list")
                    val info=data.optJSONObject("common_list_info")
                    val extra=data.optJSONObject("extra")
                    say("danmaku: 本页 ${list?.length() ?: 0} 条, has_more=${info?.optBoolean("has_more")}, total=${info?.optInt("total")}, next=${extra?.optInt("next_query_danmaku_list_time")}")
                    for(i in 0 until minOf(6,list?.length() ?: 0)) {
                        val item=list!!.optJSONObject(i) ?: continue
                        val comment=item.optJSONObject("comment")
                        val text=comment?.optJSONObject("common")?.optJSONObject("content")?.optString("text").orEmpty()
                        val offset=comment?.optJSONObject("expand")?.optLong("offset_time") ?: -1L
                        val digg=comment?.optJSONObject("stat")?.optInt("digg_count") ?: 0
                        say("  [$i] ${offset}ms digg=$digg $text")
                    }
                }.onFailure { say("解析弹幕失败: ${it.message}") }
            }
            // B: signed, the signer's own MD5 x-ss-stub
            val signedStub=signer.sign(commentApi,VendorConstants.newDevice(),body)
            call("B signed stub-md5",signedStub.url,signedStub.headers+base,body)
            // C: unsigned - if this is the one that works, the endpoint does not check signatures
            val plain=linkedMapOf(
                "iid" to "4439167111854618","device_id" to "4439167111850522","ac" to "wifi",
                "channel" to "update_64","aid" to "8662","app_name" to "novelread",
                "version_code" to "71332","version_name" to "7.1.3.32","device_platform" to "android",
                "os" to "android","ssmix" to "a","device_type" to "25053RT47C","device_brand" to "Redmi",
                "language" to "zh","os_api" to "36","os_version" to "16",
                "manifest_version_code" to "71332","resolution" to "1280*2772","dpi" to "520",
                "update_version_code" to "71332","host_abi" to "arm64-v8a","dragon_device_type" to "phone",
                "pv_player" to "71332","compliance_status" to "0","need_personal_recommend" to "1",
                "player_so_load" to "1","is_android_pad_screen" to "0",
                "ts" to (System.currentTimeMillis()/1000).toString(),
                "_rticket" to System.currentTimeMillis().toString()
            )
            val plainUrl="$commentApi?"+Signer.encode(plain)
            call("C unsigned",plainUrl,base+mapOf("User-Agent" to VendorConstants.VIDEO_UA,"x-ss-stub" to ""),body)
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    // The danmaku path as the app actually uses it: the real repository, the real signer, one
    // window at a time. --danmu measured the endpoint by hand; this one runs the shipped code.
    if(args.contains("--danmu2")) {
        val danmaku=DanmakuRepository()
        try {
            val sample=repository.home(1,ContentType.SHORT).items.firstOrNull() ?: error("首页没有剧集")
            val detail=repository.detail(sample.id)
            val vid=detail.episodes.firstOrNull() ?: error("没有分集")
            val seriesId=detail.series.id
            say("样本：${detail.series.title} series=$seriesId vid=$vid")
            val window=danmaku.window(seriesId,vid,0L,0L)
            say("第一窗：${window.items.size} 条, has_more=${window.hasMore}, 下一窗从 ${window.nextStartMs}ms 开始")
            for(item in window.items.take(8)) say("  ${item.offsetMs}ms digg=${item.digg} ${item.text}")
            // The second window has to come from where the first one said it would.
            val next=danmaku.window(seriesId,vid,window.nextStartMs,0L)
            say("第二窗：${next.items.size} 条, 起点 ${next.items.firstOrNull()?.offsetMs}ms")
            // And the track layout has to survive the real rows.
            val timeline=DanmakuTimeline(window.items,trackCount=6,lifetimeMs=8_000L)
            say("排布：${timeline.placed.size} 条占了 ${timeline.placed.map { it.track }.toSet().size} 条轨道")
            say("在 3 秒处看得见的：${timeline.visibleAt(3_000L).size} 条")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    // Are the danmaku really repeated, or does it look that way?
    //
    // Three shapes of "the same thing" have to be told apart, and they mean different things:
    // - the same row twice in one answer (the platform really sent it twice)
    // - the same text at different moments (different people wrote the same thing - that is real)
    // - the same row in two windows (our own paging asked for it twice)
    if(args.contains("--danmu3")) {
        val danmaku=DanmakuRepository()
        try {
            val sample=repository.home(1,ContentType.SHORT).items.firstOrNull() ?: error("首页没有剧集")
            val detail=repository.detail(sample.id)
            val vid=detail.episodes.firstOrNull() ?: error("没有分集")
            val seriesId=detail.series.id
            say("样本：${detail.series.title} vid=$vid")

            val windows=mutableListOf<com.hongguotv.core.DanmakuWindow>()
            var start=0L
            repeat(4) {
                val window=danmaku.window(seriesId,vid,start,0L)
                windows+=window
                say("窗口 ${it+1}：起点 ${start}ms，${window.items.size} 条，下一窗 ${window.nextStartMs}ms")
                if(window.items.isEmpty()) return@repeat
                start=window.nextStartMs
            }

            val raw=windows.flatMap { it.items }
            say("四窗一共 ${raw.size} 条（没去重）")
            val exactDuplicates=raw.groupBy { it.offsetMs to it.text }.filter { it.value.size>1 }
            say("完全一样的行（同一个时间点 + 同一句话）出现了 ${exactDuplicates.size} 组")
            for(entry in exactDuplicates.entries.take(3)) {
                say("  重复 ${entry.value.size} 次 @${entry.key.first}ms 「${entry.key.second.take(20)}」")
            }
            val byText=raw.groupBy { it.text }
            val repeatedText=byText.filter { it.value.size>1 }
            say("出现过一次以上的文本有 ${repeatedText.size} 种，涉及 ${repeatedText.values.sumOf { it.size }} 条")
            for(entry in repeatedText.entries.sortedByDescending { it.value.size }.take(5)) {
                val times=entry.value.map { it.offsetMs }.sorted()
                say("  「${entry.key.take(18)}」×${entry.value.size}，出现在 ${times.take(6).joinToString(",")}ms")
            }
            val tagged=raw.count { it.text.count { ch->ch=='[' }>=2 }
            say("正文里带两个以上方括号标记的：$tagged 条")
            for(item in raw.filter { it.text.count { ch->ch=='[' }>=2 }.take(3)) {
                say("  @${item.offsetMs}ms 「${item.text.take(40)}」")
            }
            // The rule the app merges with: one row per (moment, text).
            val merged=raw.distinctBy { it.offsetMs to it.text }
            say("按 App 的合并规则（同一时间点 + 同一句话算一条）之后：${merged.size} 条，去掉 ${raw.size-merged.size} 条")
            val sameMoment=merged.groupBy { it.offsetMs }.filter { it.value.size>1 }
            say("同一个时间点上仍有多条（不同的话）：${sameMoment.size} 组")
            for(entry in sameMoment.entries.sortedByDescending { it.value.size }.take(3)) {
                say("  @${entry.key}ms 有 ${entry.value.size} 条：${entry.value.map { it.text.take(10) }.distinct().joinToString(" / ")}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    // The client is shut down here as it is in every other branch. OkHttp's dispatcher runs
    // non-daemon threads, so a probe that throws before this point leaves the JVM running with
    // nothing to do: the report is half written and the process never ends.
    try {
        val search=repository.search(keyword,type=type); say("search=${search.items.size}; hasMore=${search.hasMore}")
        val home=repository.home(type=type); say("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
        val next=repository.home(2,type)
        say("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
        val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
        say("detail=${detail.series.title}; episodes=${detail.episodes.size}")
        val stream=repository.stream(detail.series.id,detail.episodes.first())
        RemoteVideo(repository.http,stream).use { remote ->
            remote.prepare(); val first=remote.read(0,1024*1024)
            val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
            say("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
            if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
        }
    } finally {
        repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
    }
}
