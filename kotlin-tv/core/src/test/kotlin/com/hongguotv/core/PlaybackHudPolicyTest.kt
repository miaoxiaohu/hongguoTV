package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHudPolicyTest {

    @Test
    fun theAutomaticEpisodeChangeDoesNotPutTheHudUp() {
        assertFalse(PlaybackHudPolicy.showStatusOnSwitch(chained = true))
    }

    @Test
    fun aSwitchTheUserAskedForStillGetsTheStatusLine() {
        assertTrue(PlaybackHudPolicy.showStatusOnSwitch(chained = false))
    }

    @Test
    fun aBufferingPlayerIsAllowedToDropTheHud() {
        // 这是那个隐患：以前缓冲中不排收起定时器，HUD 就一直挂在画面上收不掉。
        assertTrue(PlaybackHudPolicy.shouldAutoHide(isPlaying = false, buffering = true))
    }

    @Test
    fun aPlayingPlayerDropsTheHud() {
        assertTrue(PlaybackHudPolicy.shouldAutoHide(isPlaying = true, buffering = false))
    }

    @Test
    fun aPauseTheUserAskedForKeepsTheHudUp() {
        assertFalse(PlaybackHudPolicy.shouldAutoHide(isPlaying = false, buffering = false))
    }

    @Test
    fun bufferingWaitsLongerThanPlaying() {
        assertEquals(
            PlaybackHudPolicy.BUFFER_AUTO_HIDE_MS,
            PlaybackHudPolicy.autoHideDelayMs(buffering = true)
        )
        assertEquals(
            PlaybackHudPolicy.AUTO_HIDE_MS,
            PlaybackHudPolicy.autoHideDelayMs(buffering = false)
        )
        assertTrue(PlaybackHudPolicy.BUFFER_AUTO_HIDE_MS > PlaybackHudPolicy.AUTO_HIDE_MS)
    }

    @Test
    fun theProgressBarWaitsForAPositionToShow() {
        assertFalse(PlaybackHudPolicy.showProgress(playbackReady = false))
        assertTrue(PlaybackHudPolicy.showProgress(playbackReady = true))
    }
}
