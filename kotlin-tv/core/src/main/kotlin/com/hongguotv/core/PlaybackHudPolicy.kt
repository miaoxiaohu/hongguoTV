// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * When the playback HUD may come up, and when it is allowed to go away again.
 *
 * The HUD is the strip along the bottom of the player: the title, the state text and the
 * progress bar. It is useful when the user asks for it, and it is noise when nobody did.
 *
 * Two habits made it noisy:
 *
 * - Every episode change put it on screen, even the automatic one. The automatic change
 *   happens while the user is watching, so a strip that appears on its own is exactly the
 *   kind of thing that makes a TV app feel like it is showing off.
 * - It could never disappear on its own while the player was buffering, because the
 *   auto-hide timer was only armed while the picture was actually moving. A player stuck
 *   in "buffering" therefore left the strip on screen forever, covering the bottom of the
 *   picture, and there was nothing the user could do about it.
 *
 * The progress bar has its own rule as well: it is only worth showing when there is a
 * position to show. Before the episode is ready there is no position, and a bar sitting
 * at zero percent says nothing except that it is sitting at zero percent.
 */
object PlaybackHudPolicy {

    /**
     * How long the HUD stays up after it was asked for during normal playback.
     *
     * Long enough to read the time and the quality, short enough that it does not sit on
     * top of the picture while the user is watching.
     */
    const val AUTO_HIDE_MS = 4_500L

    /**
     * How long the HUD stays up while the player is buffering.
     *
     * Buffering is worth waiting out - the picture usually comes back - but not waiting
     * out forever. When the buffering turns into a real failure the failure path puts the
     * HUD back up with the reason on it, so letting this one go does not lose the
     * explanation, it only stops the strip from covering the picture in the meantime.
     */
    const val BUFFER_AUTO_HIDE_MS = 12_000L

    /**
     * Whether switching episodes may put the HUD on screen.
     *
     * A chained switch is the automatic episode change: nobody pressed anything, so
     * nothing may appear. A switch the user asked for still gets the status line, because
     * there the user is waiting for an answer.
     */
    fun showStatusOnSwitch(chained: Boolean): Boolean = !chained

    /**
     * Whether the HUD is allowed to disappear on its own.
     *
     * While the picture is moving, and while the player is waiting for it, yes. While the
     * user has paused on purpose, no - the pause is a state the user put the player into
     * and the strip is how they read it.
     */
    fun shouldAutoHide(isPlaying: Boolean, buffering: Boolean): Boolean = isPlaying || buffering

    /**
     * How long to wait before hiding the HUD.
     */
    fun autoHideDelayMs(buffering: Boolean): Long =
        if (buffering) BUFFER_AUTO_HIDE_MS else AUTO_HIDE_MS

    /**
     * Whether the progress bar belongs on screen.
     *
     * Only once there is a position to show. Before that the bar is a zero percent bar,
     * which is not information.
     */
    fun showProgress(playbackReady: Boolean): Boolean = playbackReady
}
