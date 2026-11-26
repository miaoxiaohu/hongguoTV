// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * v5（04.10.03）取址门。
 *
 * 站方在 2026-11-16 下午改掉了取址协议：旧的 GET /reading/bookapi/video_tab/... 与
 * POST /novel/player/multi_video_model/v1/ 一律回 HTTP 200 + 0 字节（软拦截）。
 * 现行的是 POST /novel/player/multi_video_model/player/v1：body 是 gzip 过的 JSON，
 * 头里只带 x-khronos / x-helios / x-medusa 三个 v5 签名头。
 *
 * 这一门里「门在哪、穿什么 UA、query 怎么写」全部由协议档案（[PlaybackProfile]）说了算 ——
 * 那是站方会改的数据；签名算法留在 [Signer] 里。
 */
internal object V5Playback {
    private val signer = Signer(mobileV5 = true)

    /**
     * 站方发的身份（medusa 明文与 query 都要用同一份）。
     *
     * medusa 的明文里逐个引用了 version_name / device_id / device_type / device_brand /
     * channel / os_version，站方拿它们跟 query 对账，少一个就是 HTTP 200 + 0 字节的静默封锁。
     */
    private fun identityValues(identity: PlaybackIdentity): Map<String, String> = linkedMapOf(
        "cdid" to identity.cdid,
        "iid" to identity.iid,
        "device_id" to identity.deviceId,
        "klink_egdi" to identity.klinkEgdi,
    )

    /**
     * 取一集的播放模型（video_model）。空答、业务码非 0、结构不对都返回 null。
     */
    fun model(http: OkHttpClient, identity: PlaybackIdentity, id: String, now: Long): JSONObject? {
        val profile = PlaybackProfiles.active
        val gate = profile.v5.modelGate
        // 站方现在要的是**完整设备档案**：query 里的字段同时被写进 medusa 明文，少一个就空答。
        val values = profile.deviceQuery(identityValues(identity), withRomVersion = false)
        val base = "https://${gate.host}${gate.path}"
        val url = base.toHttpUrl().newBuilder().apply {
            values.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val payload = JSONObject(profile.modelBody(id))
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        // 签名吃的是最终 query 原文与真正发出去的 body（gzip 之后的字节）。
        val signed = signer.signPlayback(url.newBuilder().query(null).build().toString(), values, bytes, now, profile)
        val request = Request.Builder().url(signed.url)
        // 签名头先落，再落这几个固定的：User-Agent 要与 query 里的设备字段对得上，
        // 被签名头里的那份覆盖掉就成了另一个机型。
        signed.headers.forEach { (key, value) -> request.header(key, value) }
        request.header("User-Agent", gate.userAgent)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val text = response.body?.string() ?: ""
            if (text.isBlank()) return@execute null
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return@execute null
            val code = if (root.has("code")) root.optInt("code",-1) else root.optInt("Code",-1)
            if (code != 0) return@execute null
            val data = root.optJSONObject("data") ?: return@execute null
            // 模型挂在 mixed_video_id_map 用的那个键（1004）下面这一集的位置上。
            val entries = data.optJSONObject("1004") ?: data
            val raw = entries.optJSONObject(id)?.opt("video_model")
                ?: data.optJSONObject(id)?.opt("video_model")
                ?: data.opt("video_model")
            when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
        }
    }

    /**
     * tab 取址门用 **v5 签名**的那一套 GET。
     *
     * 2026-11-23 实测（上游 0.16.12 同日发现）：站方现在要求这条 GET 也用 v5 的签名 ——
     * 头里只带 x-khronos / x-helios / x-medusa 三个，而且 medusa 吃的是
     * **MD5(空字节)**（不是 16 个零字节）；query 用的是 v5 那套设备字段
     * （version_code 74332 / vivo_8662_64 / PGT-AN10 / Android 12）。
     * 我们此前那两套形态（551 Argus 的 v3 形态、v4 signPlayback 形态）都被软拦截成
     * HTTP 200 + 0 字节 —— 站方要的是这一套。
     *
     * **2026-11-23 实测**：这一套答出 21684 字节的完整模型（code=0），播放 1080P 成功；
     * 同一时刻另外两套形态仍然回 0 字节。
     */
    fun tabModel(client: OkHttpClient, identity: PlaybackIdentity, id: String, now: Long, host: String): String {
        val profile = PlaybackProfiles.active
        val gate = profile.v5.tabGate
        val values = profile.tabQuery(identityValues(identity), id)
        val base = "https://$host" + gate.path
        // 空字节只是**签名输入**：这条 GET 没有正文，但 medusa 要的是 MD5(空字节)，
        // 不是 16 个零字节 —— 差这一位，站方就回 0 字节。
        val signed = signer.signPlayback(base, values, ByteArray(0), now, profile)
        val request = Request.Builder().url(signed.url).get()
        request.header("User-Agent", gate.userAgent)
        request.header("Accept", "application/json; charset=utf-8")
        request.header("Accept-Language", "zh-CN,zh;q=0.9")
        for ((k, v) in signed.headers) request.header(k, v)
        // 这三个头是 POST 才有的：空字节只是签名输入，不能留在请求上。
        request.removeHeader("x-ss-stub")
        request.removeHeader("Content-Type")
        request.removeHeader("Content-Encoding")
        val transport = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            response.body?.string() ?: ""
        }
    }
}
