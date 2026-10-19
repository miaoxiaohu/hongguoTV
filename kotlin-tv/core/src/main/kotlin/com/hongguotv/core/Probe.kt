package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter

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
                val bytes=http.newCall(request.build()).execute().use { response -> response.body?.bytes() ?: ByteArray(0) }
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
                say("  ${type.label}：$total 部 -> ${(total+29)/30} 页，首屏 ${first.joinToString("、")}")
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
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
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
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
