package com.hongguotv.core

fun main(args: Array<String>) {
    val repository=ContentRepository()
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
