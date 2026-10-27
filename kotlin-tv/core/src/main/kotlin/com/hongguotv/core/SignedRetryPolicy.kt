// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * How one episode's playback address is looked up.
 *
 * The signed endpoint refuses an attempt with an empty body about seven times in eight. That
 * rate was measured on 2026-10-27 over a hundred attempts against the live endpoint, and it
 * does not move with any of the things that usually explain such a thing: not with the request
 * rate (twelve attempts fired back to back and twelve fired four seconds apart were refused
 * alike), not with the device identity, not with the connection the request rides on, and not
 * with the server address it lands on - two IPv4 addresses and three IPv6 ones were refused
 * just as often as each other. It is a coin toss, not a rate limit and not a block on this
 * client.
 *
 * So the only thing that helps is asking again, and the only question left is how many asks
 * fit into the time a person is willing to wait for 「正在获取播放地址…」.
 *
 * Asking in series does not fit. One attempt costs about a second on the box this runs on, so
 * six in series are six seconds of silence - and the four-second budget cut the list short
 * before it was even finished, which is how a box slower than the laptop ended up asking
 * fewer times than the code said it would. Asking a dozen at once is one round trip: measured
 * the same afternoon, a wave of twelve parallel attempts found a usable answer in every one of
 * twelve waves, in 188 ms to 487 ms each.
 *
 * Two waves of twelve is twenty-four attempts. At the measured refusal rate that leaves about
 * two percent of episodes unresolved, against fifty-seven percent for the six serial attempts
 * it replaces - and the wait is two round trips instead of six.
 */
object SignedRetryPolicy {
    /** How many signed attempts one wave fires at the same time. */
    const val WAVE_SIZE = 12

    /** How many waves one episode may spend on the signed endpoint. */
    const val WAVES = 2

    /** How long the whole lookup may take, in milliseconds. */
    const val BUDGET_MS = 9_000L

    /**
     * How much of the budget a later wave needs before it may start.
     *
     * The first wave always runs - there is nothing else to try. A later wave only starts when
     * there is room left for it, so the budget is a deadline for the whole lookup rather than a
     * promise to overrun it: the person is looking at a spinner, and a second wave that cannot
     * finish is better not to start.
     */
    const val WAVE_RESERVE_MS = 3_500L

    /**
     * Whether the wave with this index may still start.
     *
     * @param index the wave's position, counting from zero.
     * @param elapsedMs how long the lookup has been going.
     */
    fun waveAllowed(index: Int, elapsedMs: Long): Boolean =
        index == 0 || elapsedMs + WAVE_RESERVE_MS <= BUDGET_MS
}
