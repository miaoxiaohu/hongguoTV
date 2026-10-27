// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * IPv4 first, IPv6 kept.
 *
 * The address of a CDN name comes back in whatever order the resolver felt like, and the first
 * one is the one the player tries. On a box whose IPv6 path is the one that stalls, that order
 * decides whether the episode plays or not. So the order is put under the app's control instead
 * of the resolver's: the IPv4 addresses come first, the IPv6 ones stay behind them.
 *
 * **Nothing is thrown away.** Some of the CDN names have no A record at all - they are AAAA
 * only - and dropping their IPv6 address would turn a working name into an unresolvable one.
 * A name that only has IPv6 therefore still resolves to IPv6; it is simply tried second.
 *
 * OkHttp walks the returned list and moves on to the next address when a connection to one
 * fails, so an IPv4 address that does not answer costs a timeout, not the episode.
 */
class PreferIpv4Dns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}
