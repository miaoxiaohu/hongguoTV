package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Where a cover may be fetched from.
 *
 * A cover URL is fetched by the app, and a backup reaches the TV over a QR code - so the URL in
 * it is untrusted input that ends up as an outgoing request. The old rule refused an address
 * written in dotted decimal and nothing else, which left every other notation of an address,
 * the names that only mean something inside a network, and every name that resolves to a
 * private address wide open.
 */
class CoverUrlPolicyTest {
    @Test fun `a cover that points inside the network is refused`() {
        val refused=listOf(
            "127.0.0.1","0.0.0.0","10.0.0.5","192.168.1.10","169.254.169.254",
            "127.1","0177.0.0.1","0x7f.0.0.1","0x7f000001","2130706433","999.999.999.999",
            "localhost","metadata.google.internal","metadata","box.home.arpa",
            "printer.local","nas.lan","host.internal","host.intranet","host.private",
            "[::1]")
        for(host in refused) assertFalse(host,LibraryBackup.isPublicCoverUrl("https://$host/cover.jpg"))
    }

    @Test fun `a public cover is kept`() {
        assertTrue(LibraryBackup.isPublicCoverUrl("https://example.com/cover.jpg"))
        assertTrue(LibraryBackup.isPublicCoverUrl("https://p3-read.byteimg.com/img/cover.jpg"))
        assertTrue(LibraryBackup.isPublicCoverUrl("https://v3-dy.bytecdn.cn/cover.jpg"))
    }

    @Test fun `anything that is not a plain https image address is refused`() {
        assertFalse(LibraryBackup.isPublicCoverUrl("http://example.com/cover.jpg"))
        assertFalse(LibraryBackup.isPublicCoverUrl("https://user:pass@example.com/cover.jpg"))
        assertFalse(LibraryBackup.isPublicCoverUrl("https://example.com/cover.jpg\n"))
        assertFalse(LibraryBackup.isPublicCoverUrl("https://exa mple.com/cover.jpg"))
        assertFalse(LibraryBackup.isPublicCoverUrl("https:///cover.jpg"))
        assertFalse(LibraryBackup.isPublicCoverUrl("not a url"))
    }

    @Test fun `a backup keeps a public cover and drops the rest`() {
        val keep=Series("1","剧 1","https://example.com/cover.jpg")
        val drop=Series("2","剧 2","https://169.254.169.254/latest/meta-data/")
        val encoded=LibraryBackup.encode(BackupData(listOf(keep,drop),emptyList(),emptySet(),emptyList(),BackupSettings()))
        val decoded=LibraryBackup.decode(encoded)
        assertEquals("https://example.com/cover.jpg",decoded.favorites[0].cover)
        assertEquals("",decoded.favorites[1].cover)
    }
}
