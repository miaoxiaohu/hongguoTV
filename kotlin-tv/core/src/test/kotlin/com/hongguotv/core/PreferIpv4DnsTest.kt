// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The address order the playback transport asks the resolver for.
 *
 * The point of the change is that the box tries IPv4 first, and the point of keeping every
 * address is that a name which only has IPv6 - some of the CDN names have no A record at all -
 * still resolves.
 */
class PreferIpv4DnsTest {
    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    private class FakeDns(private val answers: List<InetAddress>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = answers
    }

    @Test
    fun ipv4AddressesComeBeforeIpv6Ones() {
        val dns = PreferIpv4Dns(FakeDns(listOf(
            addr("2408:873c:5010:d:8000:0:b00:103"),
            addr("47.116.166.79"),
            addr("240e:960:3600:6::100"),
            addr("122.97.199.190"),
        )))
        val answers = dns.lookup("video.example.cn")
        assertEquals("IPv4 addresses first, in the order they came back",
            listOf(addr("47.116.166.79"), addr("122.97.199.190")), answers.take(2))
        assertEquals("the IPv6 addresses stay behind them",
            listOf(addr("2408:873c:5010:d:8000:0:b00:103"), addr("240e:960:3600:6::100")),
            answers.drop(2))
    }

    @Test
    fun anIpv6OnlyNameStillResolvesToItsIpv6Address() {
        // The CDN names that have no A record at all: dropping their IPv6 address would turn a
        // working name into an unresolvable one.
        val only = addr("240e:960:3600:6::100")
        val dns = PreferIpv4Dns(FakeDns(listOf(only)))
        val answers = dns.lookup("vuqsneiqnjiqqninnbqq.jspcdn.cn")
        assertEquals(1, answers.size)
        assertTrue("the IPv6 address is kept", answers[0] is Inet6Address)
        assertEquals(only, answers[0])
    }

    @Test
    fun anIpv4OnlyNameIsUnchanged() {
        val only = addr("47.116.166.79")
        val dns = PreferIpv4Dns(FakeDns(listOf(only)))
        val answers = dns.lookup("api5-normal-sinfonlineb.fqnovel.com")
        assertEquals(1, answers.size)
        assertTrue(answers[0] is Inet4Address)
    }

    @Test
    fun theOrderOfTheAddressesTheResolverGaveIsKept() {
        val dns = PreferIpv4Dns(FakeDns(listOf(
            addr("47.116.166.79"), addr("122.97.199.190"),
        )))
        assertEquals(listOf("47.116.166.79", "122.97.199.190"),
            dns.lookup("host").map { it.hostAddress })
    }

    @Test
    fun thePlaybackTransportAsksForIpv4First() {
        assertTrue("the playback transport resolves the way the box needs it",
            ContentRepository.playbackHttpClient().dns is PreferIpv4Dns)
    }

    @Test
    fun theAddressLookupKeepsTheSameResolver() {
        // The lookup client is derived from the playback transport, so it must not fall back to
        // the system resolver and undo the ordering.
        assertTrue(ContentRepository.addressHttpClient().dns is PreferIpv4Dns)
    }
}
