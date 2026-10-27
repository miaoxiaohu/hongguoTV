// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo): Closeable {
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    data class Range(val bytes: ByteArray,val total: Long)
    /**
     * The size of the resource, in bytes.
     *
     * It is written by [prepare] on one thread and read by the player on another, so it has to
     * be visible to the reader: without that, the player can read a stale zero and treat the
     * whole episode as empty.
     */
    @Volatile var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix=byteArrayOf()
    /** True once the index has been read and the stream can be decrypted. */
    @Volatile private var ready=false

    /**
     * One request for the bytes of [start]..[end].
     *
     * RFC 7233 lets a server answer with less than was asked for, so a shorter range is not a
     * failure by itself. What has to hold is that the bytes start where the request did and
     * that they do not run past the resource - a range that starts somewhere else, or one that
     * claims more than the file holds, is a real mismatch and the episode cannot be assembled
     * from it.
     */
    private fun requestRange(start: Long,end: Long): Range {
        val request=Request.Builder().url(info.url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=http.newCall(request)
        synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
        try {
            return RequestScope.execute(call) { response ->
                if(response.code!=206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
                val (begin,finish,size)=match.destructured
                val full=size.toLong()
                if(full<=0) throw IOException("视频分段范围不匹配")
                val from=begin.toLong(); val to=finish.toLong()
                if(from!=start || to<from || to>full-1) throw IOException("视频分段范围不匹配")
                if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                // A server may stop short of the requested end, and may even answer with more
                // than was asked for; only the bytes inside the request are used.
                val usable=minOf(to,end)
                val n=(usable-from+1).toInt(); val body=response.body ?: throw IOException("视频分段为空")
                val bytes=body.source().readByteArray(n.toLong())
                Range(bytes,full)
            }
        } finally { synchronized(calls) { calls-=call } }
    }

    /**
     * The bytes of [start]..[end], asking for the rest when the server answers with less.
     *
     * The bytes that come back have to be the ones that were asked for, in order and without a
     * gap, so a short answer is continued with another request from where it stopped instead of
     * being treated as a broken range. The loop stops at the end of the request, at the end of
     * the resource, or when the server keeps answering with nothing - the last one is a broken
     * server, not a short answer.
     */
    private fun range(start: Long,end: Long): Range {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val out=java.io.ByteArrayOutputStream()
        var cursor=start; var full=0L; var continuations=0
        while(cursor<=end) {
            val part=requestRange(cursor,end)
            full=part.total
            if(part.bytes.isEmpty()) break
            out.write(part.bytes); cursor+=part.bytes.size
            if(cursor>end || cursor>full-1) break
            if(++continuations>MAX_RANGE_CONTINUATIONS) throw IOException("视频分段响应过短")
        }
        return Range(out.toByteArray(),full)
    }
    @Synchronized fun prepare(): RemoteVideo {
        if(::prepared.isInitialized) return this
        val first=range(0,256*1024-1); total=first.total
        prefix=first.bytes
        val end=MediaCrypto.headerEnd(first.bytes,total)
        val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes else first.bytes.copyOf(end)
        prepared=MediaCrypto.prepare(header,info.key)
        if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        ready=true
        return this
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(): RemoteVideo {
        prepare()
        val size=minOf(total,1024*1024L).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes
        return this
    }
    fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        // Reading before the index is known used to answer with an empty array, and the player
        // turned that into a generic "the episode ended early". Saying that the stream is not
        // ready yet names the thing that is actually wrong.
        if(!ready) throw IOException("视频尚未准备完成")
        val size=total
        if(start>=size) return byteArrayOf()
        val cached=prefix; val end=minOf(size,start+length)
        // 预热的那一段读完了就让它可回收：整集都挂在它上面，而播放器不会回头再读 —— 回头那
        // 一下是跳转，那时候重新取一次，比让这一兆一直占着内存划算。
        if(start>=cached.size && cached.isNotEmpty()) prefix=ByteArray(0)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        return MediaCrypto.decrypt(raw,start,info.key,prepared)
    }
    override fun close() { synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() } }
    private companion object {
        /** How many times one range may be continued before the server counts as broken. */
        const val MAX_RANGE_CONTINUATIONS=64
    }
}
