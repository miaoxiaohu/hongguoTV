// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 远端协议档案的拉取。
 *
 * 网络层照 [AppUpdateRepository] 的规矩来：只走 HTTPS、只认白名单里的域、大小有上限。
 * 拉回来的档案要先过一遍严格校验才可能被采纳。
 */
class PlaybackProfileRepository(private val url: String = PlaybackProfile.REMOTE_URL) : java.io.Closeable {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).dns(PreferIpv4Dns())
        .addNetworkInterceptor { chain ->
            val target = chain.request().url
            if (target.scheme != "https" || target.host !in ALLOWED_HOSTS)
                throw IOException("协议档案下载地址不受支持")
            chain.proceed(chain.request())
        }.build()

    /** 拉一份档案并校验。拉不到或校验不过就抛异常，调用方继续用内置默认。 */
    fun fetch(): PlaybackProfile {
        val request = Request.Builder().url(url)
            .header("User-Agent", "HongguoTV-Profile")
            .header("Cache-Control", "no-cache").build()
        val call = http.newCall(request)
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful)
                throw IOException(if (response.code == 404) "尚无可用的协议档案" else "无法获取协议档案，请稍后重试")
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > PlaybackProfile.MAX_BYTES) throw IOException("协议档案过大")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: throw IOException("协议档案为空")
            try {
                PlaybackProfile.parse(bytes.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                throw IOException("协议档案格式不正确")
            }
        }
    }

    fun cancel() = http.dispatcher.cancelAll()

    override fun close() {
        cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
    }

    companion object {
        /** 只允许从这几个域取档案。 */
        val ALLOWED_HOSTS = setOf("raw.githubusercontent.com", "github.com", "objects.githubusercontent.com")
    }
}
