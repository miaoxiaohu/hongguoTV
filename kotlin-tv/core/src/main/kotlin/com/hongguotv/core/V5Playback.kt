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
 */
internal object V5Playback {
    const val HOST = "api5-normal-sinfonlinea.fqnovel.com"
    const val PATH = "/novel/player/multi_video_model/player/v1"
    private const val UA = "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
    private val signer = Signer(mobileV5 = true)

    /**
     * 取一集的播放模型（video_model）。空答、业务码非 0、结构不对都返回 null。
     */
    fun model(http: OkHttpClient, identity: PlaybackIdentity, id: String, now: Long): JSONObject? {
        val values = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to identity.iid, "cdid" to identity.cdid, "device_id" to identity.deviceId,
            "_rticket" to now.toString(),
        )
        val base = "https://$HOST$PATH"
        val url = base.toHttpUrl().newBuilder().apply {
            values.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":${ContentRepository.PLAYBACK_DEVICE_LEVEL},"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}"""
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        // 签名吃的是最终 query 原文与真正发出去的 body（gzip 之后的字节）。
        val signed = signer.signPlayback(url.newBuilder().query(null).build().toString(), values, bytes, now)
        val request = Request.Builder().url(signed.url)
        // 签名头先落，再落这几个固定的：User-Agent 要与 query 里的设备字段对得上，
        // 被签名头里的那份覆盖掉就成了另一个机型。
        signed.headers.forEach { (key, value) -> request.header(key, value) }
        request.header("User-Agent", UA)
            .header("Accept", "application/json")
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
}
