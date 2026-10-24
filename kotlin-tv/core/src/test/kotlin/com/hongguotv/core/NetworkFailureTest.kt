// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The distinction the fallback and the retry both stand on: did the request reach a server, and
 * did the person still want it.
 */
class NetworkFailureTest {

    @Test fun `a refused connection is a network failure`() {
        assertTrue(isNetworkFailure(ConnectException("Connection refused")))
    }

    @Test fun `a dropped handshake is a network failure`() {
        assertTrue(isNetworkFailure(SocketTimeoutException("timeout")))
    }

    @Test fun `a name that does not resolve is a network failure`() {
        assertTrue(isNetworkFailure(UnknownHostException("api5-normal-sinfonlineb.fqnovel.com")))
    }

    @Test fun `a wrapped network failure is still one`() {
        val wrapped=IOException("播放失败",ConnectException("Connection refused"))
        assertTrue(isNetworkFailure(wrapped))
    }

    @Test fun `a site that answered with something unusable is not a network failure`() {
        // The signed endpoint answers HTTP 200 with code 110001, or with an empty body. Both are
        // answers, and the public page may still have the episode.
        assertFalse(isNetworkFailure(IOException("播放接口暂不可用")))
        assertFalse(isNetworkFailure(HttpStatusException(503)))
        assertFalse(isNetworkFailure(org.json.JSONException("Unexpected end of stream")))
    }

    @Test fun `a cancelled request is a cancellation and not a network failure`() {
        assertTrue(isCancellation(CancelledException()))
        assertTrue(isCancellation(IOException("Canceled")))
        assertFalse(isNetworkFailure(CancelledException()))
        assertFalse(isCancellation(ConnectException("Connection refused")))
    }

    @Test fun `the fallback is skipped only when asking again cannot help`() {
        assertTrue(fallbackCannotHelp(ConnectException("Connection refused")))
        assertTrue(fallbackCannotHelp(SocketTimeoutException("timeout")))
        assertTrue(fallbackCannotHelp(CancelledException()))
        // The signed endpoint answered; the public page is still worth a try.
        assertFalse(fallbackCannotHelp(IOException("播放接口暂不可用")))
    }
}
