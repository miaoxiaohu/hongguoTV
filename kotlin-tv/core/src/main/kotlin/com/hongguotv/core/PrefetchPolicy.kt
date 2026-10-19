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
     * How far the current episode must be buffered ahead.
     *
     * Preparing the next episode costs a signed stream lookup and a megabyte of the CDN. If the
     * current episode is not buffered, that megabyte comes out of the picture.
     */
    const val BUFFER_AHEAD_MS = 3_000L

    /**
     * How long a prepared episode stays usable.
     *
     * It has to outlast the episode it was prepared for, and the episode is as long as it is:
     * the next episode is now prepared while the current one is still playing, so the resource
     * sits here for the whole of it. A short-drama episode runs a couple of minutes, and the
     * prepared stream URL is only worth holding while the CDN still serves it.
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
