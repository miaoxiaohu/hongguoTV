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
    /** tab 门（GET video_tab）用这一档 UA：版本 73932，但说的是 Android 16 / 25053RT47C。 */
    private const val TAB_UA = "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    private val signer = Signer(mobileV5 = true)

    /**
     * 完整设备档案（上游 openhongguo 的 29 字段固定顺序）。
     *
     * 这些字段不只是「参数」：medusa 的明文里逐个引用了 version_name / device_id /
     * device_type / device_brand / channel / os_version，站方拿它们跟 query 对账，
     * 少一个就是 HTTP 200 + 0 字节的静默封锁。
     */
    private fun deviceProfile(identity: PlaybackIdentity, withRomVersion: Boolean): LinkedHashMap<String, String> {
        val values = linkedMapOf(
            "device_platform" to "android", "os" to "android", "ssmix" to "a",
            "cdid" to identity.cdid, "channel" to "vivo_8662_64",
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "manifest_version_code" to "74332", "update_version_code" to "74332",
            "resolution" to "1920*1080", "dpi" to "280",
            "device_type" to "PGT-AN10", "device_brand" to "HONOR",
            "language" to "zh", "os_api" to "31", "os_version" to "12",
            "ac" to "wifi", "host_abi" to "arm64-v8a",
            "dragon_device_type" to "phone", "pv_player" to "74332",
            "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0",
        )
        if (withRomVersion) values["rom_version"] = "V417IR+release-keys"
        values["iid"] = identity.iid
        values["device_id"] = identity.deviceId
        if (identity.klinkEgdi.isNotEmpty()) values["klink_egdi"] = identity.klinkEgdi
        return values
    }

    /**
     * 取一集的播放模型（video_model）。空答、业务码非 0、结构不对都返回 null。
     */
    fun model(http: OkHttpClient, identity: PlaybackIdentity, id: String, now: Long): JSONObject? {
        // 站方现在要的是**完整设备档案**：query 里的字段同时被写进 medusa 明文
        // （version_name / device_id / device_type / device_brand / channel / os_version），
        // 少一个就回 HTTP 200 + 0 字节。
        val values = deviceProfile(identity, withRomVersion = false)
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
        val values = deviceProfile(identity, withRomVersion = true)
        values.putAll(
            linkedMapOf(
                "video_id" to id, "video_id_type" to "0",
                "device_level" to ContentRepository.PLAYBACK_DEVICE_LEVEL.toString(),
                "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
                "video_platform" to "1024", "source" to "0", "from_video_id" to "",
            )
        )
        val base="https://$host"+ContentRepository.PLAYBACK_TAB_PATH
        // 空字节只是**签名输入**：这条 GET 没有正文，但 medusa 要的是 MD5(空字节)，
        // 不是 16 个零字节 —— 差这一位，站方就回 0 字节。
        val signed=signer.signPlayback(base,values,ByteArray(0),now)
        val request=Request.Builder().url(signed.url).get()
        request.header("User-Agent",TAB_UA)
        request.header("Accept","application/json; charset=utf-8")
        request.header("Accept-Language","zh-CN,zh;q=0.9")
        for((k,v) in signed.headers) request.header(k,v)
        // 这三个头是 POST 才有的：空字节只是签名输入，不能留在请求上。
        request.removeHeader("x-ss-stub")
        request.removeHeader("Content-Type")
        request.removeHeader("Content-Encoding")
        val transport=client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            if(!response.isSuccessful) throw HttpStatusException(response.code)
            response.body?.string() ?: ""
        }
    }

}
