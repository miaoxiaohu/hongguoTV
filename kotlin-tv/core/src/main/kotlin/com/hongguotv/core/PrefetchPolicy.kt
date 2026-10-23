// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object PrefetchPolicy {
    /**
     * How long the current episode must be playing before the next one is prepared.
     *
     * Long enough that the picture has settled and the user is not about to skip away.
     */
    const val SETTLE_MS = 8_000L

    /**
     * How far the current episode must be buffered ahead before the next one may be prepared.
     *
     * This is not a small "do not starve the picture" cushion. The player's own load control
     * already keeps fifteen to thirty seconds of media buffered - see the DefaultLoadControl the
     * player is built with - so a threshold of a few seconds never binds and never protected
     * anything: it was not a guard, it was a delay of three seconds.
     *
     * What the number is really about is the moment the preparation starts. It starts a few
     * seconds after the picture is up, and at that point the player is still filling its own
     * buffer towards that target. The preparation takes a megabyte off the same uplink, so on a
     * link that is only a little faster than the bitrate it lands exactly while the player is
     * short of cushion, and the two compete. Waiting until the player's buffer has reached its
     * own floor means the megabyte is only ever spent out of spare capacity.
     *
     * A link that never gets there cannot afford the megabyte either, so nothing is prepared -
     * which is the honest answer for that link rather than a reason to interrupt the episode.
     */
    const val BUFFER_AHEAD_MS = 15_000L

    /**
     * How long one preparation may take before it is given up on.
     *
     * The deadline belongs to the preparation, not to the transport it borrows. The client that
     * prepares the next episode is the client that plays it afterwards, so a timeout short
     * enough for a preparation is also short enough to fail a slow chunk of the episode itself:
     * the player pulls the video in 512 KiB ranges and each of those is one call that has to
     * finish inside the call timeout.
     *
     * A minute is long enough for a megabyte on a link that is still worth watching - a
     * megabyte takes about forty seconds at the slowest rate the video itself plays at - and
     * short enough that a link which cannot manage it does not get a second and third try at
     * the current episode's bandwidth.
     */
    const val BUDGET_MS = 60_000L

    /**
     * How long a prepared episode stays usable.
     *
     * It has to outlast the episode it was prepared for, and the episode is as long as it is:
     * the next episode is now prepared while the current one is still playing, so the resource
     * sits here for the whole of it. A short-drama episode runs a couple of minutes, and the
     * prepared stream URL is only worth holding while the CDN still serves it.
     *
     * It does: measured 2026-10-20 by resolving one signed URL and asking for its first kilobyte
     * every minute for half an hour - thirty-one requests, all 206, so the URL was still serving
     * bytes long after the ten minutes this resource is held for. The ten minutes are therefore
     * the life of the resource, not the life of the address.
     */
    const val TTL_MS = 10 * 60_000L

    /**
     * Whether the next episode may be prepared now.
     *
     * The old rule was "the current episode ends within thirty seconds". That left the whole
     * preparation - resolving the signed stream, warming its first megabyte - to race the end of
     * the episode, and on a slow box it did not win: the next episode started with nothing
     * prepared, so every automatic episode change showed a loading step. Preparing early gives
     * the work a whole episode to finish in.
     *
     * @param playingFor how long the current episode has been playing.
     * @param bufferedAhead how much of the current episode is already buffered.
     */
    fun shouldPrepare(playingFor: Long, bufferedAhead: Long): Boolean =
        playingFor >= SETTLE_MS && bufferedAhead >= BUFFER_AHEAD_MS
}
