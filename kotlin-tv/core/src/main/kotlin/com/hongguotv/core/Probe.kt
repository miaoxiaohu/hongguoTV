package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

fun main(args: Array<String>) {
    val repository=ContentRepository()

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
                println("[$label] bytes=${bytes.size} :: ${String(bytes,0,minOf(160,bytes.size)).replace(Regex("\\s+")," ")}")
            } catch(e: Exception) {
                println("[$label] FAILED ${e.javaClass.simpleName}: ${e.message?.take(110)}")
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
        println("control: ${control()}")

        val base="https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/tab/v/"
        // The url and the headers must come from one signing pass: x-medusa embeds the device_id
        // that is also in the query, and a mismatch makes the server drop the response silently.
        val getSigned=signer.sign(base,VendorConstants.newDevice().toMutableMap().apply { put("query",keyword) })
        call("GET query",getSigned.url,getSigned.headers,null)
        val body="""{"query":"$keyword","offset":0,"count":20}""".toByteArray()
        val postSigned=signer.sign(base,VendorConstants.newDevice(),body)
        call("POST body",postSigned.url,postSigned.headers,body)

        println("control again: ${control()}")
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        return
    }

    // End-to-end proof for the local catalogue index: walk everything, then search it. Prints the
    // size of the index on disk too, because the ceiling that matters on a low-end box is whether
    // the JSON still fits the preference file and the 7-day cache.
    if(args.contains("--index")) {
        try {
            val started=System.currentTimeMillis()
            var last=0
            val entries=CatalogueBuilder(repository).build(onPage={ _,total ->
                if(total-last>=400) { last=total; println("  ... 已收录 $total 部，用时 ${(System.currentTimeMillis()-started)/1000}s") }
            })
            println("索引建完：${entries.size} 部，用时 ${(System.currentTimeMillis()-started)/1000}s")
            val index=CatalogueIndex(entries)
            for(q in listOf("庆余年","总裁","雾锁情迷","修仙","庆余年第二季")) {
                val serverOnly=repository.search(q)
                val merged=repository.search(q,1,ContentType.SHORT,index)
                println("  「$q」 本地命中=${index.search(q,400).size} | 服务端=${serverOnly.items.size} 条 matched=${serverOnly.matchedCount} | 合并后第1页=${merged.items.size} hasMore=${merged.hasMore} matched=${merged.matchedCount}")
            }
            val json=CatalogueIndexDisk.encode(entries,System.currentTimeMillis())
            val decoded=CatalogueIndexDisk.decode(json,System.currentTimeMillis())
            println("磁盘：${json.length} 字符（上限 1500000），解码后 ${decoded?.size} 部，回搜雾锁情迷 -> ${decoded?.search("雾锁情迷")?.firstOrNull()?.title}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
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
        val type=ContentType.entries.singleOrNull { it.categorySlug==slug }
        require(type!=null) { "未知分类 $slug" }
        try {
            var ok=0; var bad=0
            val ids=repository.home(type=type).items.map { it.id }
            for(id in ids.take(8)) {
                try {
                    val detail=repository.detail(id)
                    val stream=repository.stream(detail.series.id,detail.episodes.first())
                    RemoteVideo(repository.http,stream).use { remote ->
                        remote.prepare(); val head=remote.read(0,1024*1024)
                        ok++; println("play OK  $id ${detail.series.title} 集数=${detail.episodes.size} 画质=${stream.quality} 字节=${head.size}")
                    }
                } catch(e: Exception) {
                    bad++; println("play FAIL $id ${e.javaClass.simpleName}: ${e.message?.take(90)}")
                }
            }
            println("playSummary catalog=$slug 成功=$ok 失败=$bad")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }

    // Walks every catalogue end to end: two pages, a detail fetch and a real CDN byte read each.
    // This is the only way to prove the four named routes are genuinely distinct and playable.
    if(args.contains("--catalogs")) {
        try {
            val seen=mutableMapOf<String,MutableSet<String>>()
            for(type in ContentType.entries) {
                val first=repository.home(type=type)
                check(first.items.isNotEmpty()) { "${type.label} 首页没有返回剧集" }
                val second=repository.home(2,type)
                val overlap=first.items.map { it.id }.intersect(second.items.map { it.id }.toSet())
                check(overlap.isEmpty()) { "${type.label} 第1页和第2页重复: $overlap" }
                check(first.hasMore) { "${type.label} 首页没有给出下一页" }
                val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: first.items.first().id)
                check(detail.episodes.isNotEmpty()) { "${type.label} ${detail.series.title} 没有分集" }
                val stream=repository.stream(detail.series.id,detail.episodes.first())
                RemoteVideo(repository.http,stream).use { remote ->
                    remote.prepare(); val head=remote.read(0,1024*1024)
                    check(head.size>0) { "${type.label} ${detail.series.title} 拉不到视频字节" }
                    seen.getOrPut(type.categorySlug){mutableSetOf()}.addAll(first.items.map { it.id })
                    println("catalog=${type.categorySlug}(${type.label}); p1=${first.items.size}; p2=${second.items.size}; episodes=${detail.episodes.size}; quality=${stream.quality}; bytes=${head.size}; first=${detail.series.title}")
                }
            }
            // Equal page sizes prove nothing about identity; only shared ids do.
            val keys=seen.keys.toList()
            for(i in keys.indices) for(j in i+1 until keys.size) {
                val shared=seen.getValue(keys[i]).intersect(seen.getValue(keys[j]))
                check(shared.isEmpty()) { "${keys[i]} 与 ${keys[j]} 共享剧集: $shared" }
            }
            println("catalogsOk=${seen.entries.joinToString { "${it.key}:${it.value.size}" }}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    val search=repository.search(keyword,type=type); println("search=${search.items.size}; hasMore=${search.hasMore}")
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    val next=repository.home(2,type)
    println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.series.id,detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
