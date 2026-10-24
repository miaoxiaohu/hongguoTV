// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException

/**
 * True when the request never reached a server.
 *
 * Two failures look alike on the screen and are not alike at all: the site answered with
 * something this client cannot use, or the network never got there. Only the first one gets
 * better by asking again - and the difference decides whether a second way of asking the same
 * site is worth the wait.
 *
 * A dropped handshake has to be told apart from a refusal, and both from a timeout: a refusal
 * is immediate, a dropped handshake sits there until the connect timeout is over, and that
 * difference is exactly what made the retry feel endless.
 */
fun isNetworkFailure(error: Throwable): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth++ < 8) {
        when (cause) {
            // SocketTimeoutException extends InterruptedIOException, and OkHttp's own call
            // timeout is an InterruptedIOException as well.
            is InterruptedIOException -> return true
            is UnknownHostException -> return true
            is ConnectException -> return true
            is NoRouteToHostException -> return true
            is PortUnreachableException -> return true
        }
        cause = cause.cause
    }
    return false
}

/**
 * True when the work was called off on purpose.
 *
 * A cancelled request is not a failure of the site and not a failure of the network: the person
 * who wanted it has already moved on. Retrying it anywhere only spends another second of
 * waiting for nobody.
 */
fun isCancellation(error: Throwable): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth++ < 8) {
        if (cause is CancelledException) return true
        // OkHttp's own cancellation is a plain IOException with this exact wording.
        if (cause is IOException && cause.message == "Canceled") return true
        cause = cause.cause
    }
    return false
}

/**
 * True when asking the same site a second way cannot help.
 *
 * This is the question the fallback has to answer before it is made. The public player page is
 * another door to the same room, so it only helps when the first door answered with something
 * unusable. A dead network and a cancelled request both say the same thing: do not knock again.
 *
 * Skipping the fallback in those cases halves the wait before the user learns that the episode
 * cannot be played - which is the difference between a few seconds of silence and a minute of
 * it, because the retry counter does not even start until the attempt comes back.
 */
fun fallbackCannotHelp(error: Throwable): Boolean = isNetworkFailure(error) || isCancellation(error)
