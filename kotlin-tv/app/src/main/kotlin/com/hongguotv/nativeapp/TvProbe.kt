// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.net.HttpURLConnection
import java.net.URL

/**
 * TEMPORARY diagnostic reporter. It lives only in the throwaway probe build and must be
 * removed before a release build (the delivery rule: no diagnostics in shipped APKs).
 *
 * It posts one JSON object per event to a listener on the development machine so the
 * failure reason can be read without adb on the TV.
 */
object TvProbe {
    private const val ENDPOINT = "http://192.168.1.11:8899/report"

    fun report(event: String, fields: Map<String, Any?>) {
        Thread({
            try {
                val body = StringBuilder()
                body.append('{')
                body.append("\"event\":\"").append(escape(event)).append('"')
                for ((k, v) in fields) {
                    body.append(",\"").append(escape(k)).append("\":")
                    when (v) {
                        null -> body.append("null")
                        is Boolean, is Number -> body.append(v.toString())
                        else -> body.append('"').append(escape(v.toString())).append('"')
                    }
                }
                body.append('}')
                val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (_: Throwable) {
                // A probe that cannot report must never break playback.
            }
        }, "hongguotv-tvprobe").start()
    }

    /** The exception and everything that caused it, on one line. */
    fun chain(problem: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = problem
        var depth = 0
        while (current != null && depth < 8) {
            if (depth > 0) out.append(" <- ")
            out.append(current.javaClass.simpleName).append(": ").append(current.message)
            // The doors that were tried and refused are attached as suppressed exceptions; they
            // say which door answered and which ones did not, which is the whole question.
            current.suppressed.forEach { out.append(" [suppressed ").append(it.javaClass.simpleName)
                .append(": ").append(it.message).append("]") }
            current = current.cause
            depth++
        }
        return out.toString()
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
}
