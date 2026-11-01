// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

/**
 * The sample that is not an episode, and the fallback that used to lead to it.
 *
 * Measured 2026-11-01 on the public page: `video/public/<id>/360p.mp4` is exactly 10.000 s
 * long and 1,057,426 bytes - a preview. It used to be handed to the player as if it were the
 * episode, so the box played ten seconds of 360p, reported a playback failure, and the retry
 * did the same thing again. That is what "播放总是重试" looked like from the sofa.
 */
class PlaybackFallbackTest {

    /** An MP4 header that says how long the media is. */
    private fun be32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 255).toByte(), ((value ushr 16) and 255).toByte(),
        ((value ushr 8) and 255).toByte(), (value and 255).toByte())

    private fun box(type: String, body: ByteArray): ByteArray = be32(body.size + 8) + type.toByteArray() + body

    /** The `mvhd` box of a media of [seconds], with a timescale of 1000. */
    private fun mvhd(seconds: Int): ByteArray {
        val body = ByteArray(4) +      // version 0 and the flags
            be32(0) +                   // creation time
            be32(0) +                   // modification time
            be32(1000) +                // timescale
            be32(seconds * 1000) +      // duration
            ByteArray(80)
        return box("mvhd", body)
    }

    /** A file whose container says it is [seconds] long. */
    private fun video(seconds: Int, size: Int = 512 * 1024): ByteArray {
        val head = box("ftyp", "isom0000".toByteArray()) + box("moov", mvhd(seconds))
        return head + ByteArray(size - head.size) { (it % 239).toByte() }
    }

    /** A server that answers range requests the way the CDN does. */
    private class VideoServer(val data: ByteArray) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        init {
            server.createContext("/video") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                val (start, requested) = range.removePrefix("bytes=").split('-').map(String::toInt)
                val end = minOf(requested, data.lastIndex)
                val bytes = data.copyOfRange(start, end + 1)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                exchange.sendResponseHeaders(206, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/video"
        fun stop() = server.stop(0)
    }

    private fun withServer(data: ByteArray, body: (VideoServer, OkHttpClient) -> Unit) {
        val server = VideoServer(data); val client = OkHttpClient()
        try { body(server, client) } finally { server.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `the length of the media is read out of the container`() {
        assertEquals(10_000L, MediaCrypto.mediaDurationMs(video(10)))
        assertEquals(180_000L, MediaCrypto.mediaDurationMs(video(180)))
    }

    @Test fun `a container that does not say how long it is says nothing`() {
        // The old moov has no mvhd: an unknown length is not a short one, and a guess would
        // refuse a real episode.
        val head = box("ftyp", "isom0000".toByteArray()) + box("moov", ByteArray(16))
        assertNull(MediaCrypto.mediaDurationMs(head))
    }

    @Test fun `a ten second clip is refused instead of played as the episode`() {
        withServer(video(10)) { server, client ->
            val remote = RemoteVideo(client, StreamInfo(server.url, null, "360P"))
            val failure = assertThrows(SampleClipException::class.java) { remote.prepare() }
            assertEquals(10_000L, failure.sampleMs)
            remote.close()
        }
    }

    @Test fun `a real episode is played and its length is known`() {
        withServer(video(180)) { server, client ->
            val remote = RemoteVideo(client, StreamInfo(server.url, null, "1080P"))
            remote.prepare()
            assertEquals(180_000L, remote.durationMs)
            remote.close()
        }
    }

    @Test fun `without an identity the fallback stops instead of walking to the sample`() {
        // The public page is another door to the same room, and what it has there is a ten
        // second preview. So an attempt that failed for want of an identity must not fall
        // through to it: that is how a ten second outage became a minute of "play ten seconds,
        // fail, retry".
        assertTrue(fallbackCannotHelp(RegistrationUnavailableException()))
        assertTrue(isRegistrationUnavailable(RegistrationUnavailableException()))
        assertTrue(isRegistrationUnavailable(IOException("外层", RegistrationUnavailableException())))
        // A refusal is still worth falling back from - the page may have the episode.
        assertFalse(fallbackCannotHelp(IOException("播放接口暂不可用")))
    }

    @Test fun `a registration is not given up on for a minute`() {
        // A minute was long enough for every attempt inside it to be answered with the sample.
        assertTrue(ContentRepository.PLAYBACK_REGISTRATION_RETRY_MS <= 10_000L)
    }

    @Test fun `a registration may wait longer than an address lookup`() {
        // The lookup's ten seconds were measured against a stall of 10.04 s whose answer
        // arrived 155 ms late - the request was killed just before it would have succeeded.
        assertTrue(ContentRepository.REGISTRATION_CALL_TIMEOUT_S >= 15L)
    }
}
