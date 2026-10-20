package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

/**
 * How much of a range the server is allowed to answer with, and what a read before the stream
 * is ready says.
 *
 * RFC 7233 lets a server answer a range request with a shorter range than the one that was
 * asked for. The old code compared the answer with the request byte for byte, so such a
 * server failed the whole episode. The bytes still have to be the ones that were asked for, in
 * order and without a gap - so a short answer is followed, and a range that starts somewhere
 * else is still refused.
 */
class RemoteVideoRangeTest {
    private fun box(type: String, body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
    private fun video(size: Int): ByteArray {
        val head=box("ftyp","isom0000".toByteArray())+box("moov",byteArrayOf())
        return head+ByteArray(size-head.size) { (it%239).toByte() }
    }
    /** A server that answers with at most [cap] bytes, and that may lie about the range. */
    private class RangeServer(val data: ByteArray,val cap: Int,val lying: Boolean=false) {
        /** The range the server actually answered with, not the one that was asked for. */
        val served=java.util.Collections.synchronizedList(mutableListOf<IntArray>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        init {
            server.createContext("/video") { exchange ->
                val range=exchange.requestHeaders.getFirst("Range")
                val (start,requested)=range.removePrefix("bytes=").split('-').map(String::toInt)
                val end=minOf(minOf(requested,start+cap-1),data.lastIndex)
                val bytes=data.copyOfRange(start,end+1)
                val reported=if(lying) start+1 else start
                served+=intArrayOf(start,end)
                exchange.responseHeaders.add("Content-Range","bytes $reported-$end/${data.size}")
                exchange.sendResponseHeaders(206,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        val url get()="http://127.0.0.1:${server.address.port}/video"
        fun stop()=server.stop(0)
    }
    private fun withServer(data: ByteArray,cap: Int,lying: Boolean=false,body: (RangeServer,OkHttpClient)->Unit) {
        val server=RangeServer(data,cap,lying); val client=OkHttpClient()
        try { body(server,client) } finally { server.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `a server that answers with less than asked for is followed to the end of the range`() {
        val data=video(2*1024*1024)
        withServer(data,16*1024) { server,client ->
            val remote=RemoteVideo(client,StreamInfo(server.url,null,"720P")).warm()
            // Past the warmed prefix, so the bytes have to come off the wire.
            val bytes=remote.read(1_000_000,100_000)
            assertEquals(100_000,bytes.size)
            assertArrayEquals(data.copyOfRange(1_000_000,1_100_000),bytes)
            // The ranges that came back follow one another without a gap and without a jump
            // backwards, and they cover exactly the bytes that were read.
            val asked=server.served.toList()
            asked.forEachIndexed { index,pair ->
                assertTrue(pair[0]<=pair[1])
                if(index>0) assertEquals("the range continued where the last one stopped",asked[index-1][1]+1,pair[0])
            }
            assertEquals(1_099_999,asked.last()[1])
            assertTrue("the short answer was continued rather than taken as the whole range",asked.size>1)
            remote.close()
        }
    }

    @Test fun `a range that does not start where it was asked to is still refused`() {
        val data=video(1024*1024)
        withServer(data,64*1024,lying=true) { server,client ->
            val remote=RemoteVideo(client,StreamInfo(server.url,null,"720P"))
            val failure=assertThrows(IOException::class.java) { remote.prepare() }
            assertTrue(failure.message!!.contains("范围不匹配"))
            remote.close()
        }
    }

    @Test fun `reading before the stream is prepared says so instead of returning nothing`() {
        val data=video(1024*1024)
        withServer(data,64*1024) { server,client ->
            val remote=RemoteVideo(client,StreamInfo(server.url,null,"720P"))
            assertEquals(0L,remote.total)
            // The old answer was an empty array, which the player turned into a generic
            // "the episode ended early" and the user saw as a playback failure.
            val failure=assertThrows(IOException::class.java) { remote.read(0,1024) }
            assertTrue(failure.message!!.contains("尚未准备"))
            remote.prepare()
            assertTrue(remote.total>0)
            assertEquals(1024,remote.read(0,1024).size)
            remote.close()
        }
    }
}
