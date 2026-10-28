// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The identity the playback endpoint accepts.
 *
 * Only the two numbers the endpoint checks are kept: they are what the playback request carries
 * in its query, and nothing else about the registered device is used or stored.
 */
internal data class PlaybackIdentity(val deviceId: String, val iid: String)

/**
 * The anonymous playback device.
 *
 * The playback endpoint used to answer a request that carried a freshly synthesised device
 * identity, and it stopped doing so on 2026-10-27: a request whose `device_id` and `iid` were
 * made up on the spot comes back as HTTP 200 with a zero-byte body, which is indistinguishable
 * from an endpoint that no longer exists. The endpoint did not go away - it started checking
 * that the identity was one it had seen, and the identity it accepts is one the platform
 * registered.
 *
 * So the app registers an anonymous device once and replays its `device_id` and `iid` on every
 * playback request. The registration is the `device_register` call the app itself makes at first
 * start; the body it expects is a gzip-compressed JSON envelope encrypted with ttEncrypt, and
 * the answer carries the two numbers back.
 *
 * This object registers one device and hands the numbers back; it keeps nothing. The caller
 * holds the identity, so the lifetime of a registration is the lifetime of the caller - which
 * also means a test can register as many devices as it likes without one answer leaking into
 * the next.
 */
internal object PlaybackDevice {
    private const val REGISTER_URL =
        "https://log5-applog-lf.fqnovel.com/service/2/device_register/" +
            "?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1"

    /** The app's own user agent - the registration is not the playback request. */
    private const val REGISTER_UA =
        "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"

    /** The fixed salt the ttEncrypt key derivation uses, the same one the app ships with. */
    private const val KDF_SALT =
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"

    /**
     * Registers one anonymous device and returns the two numbers the playback request carries.
     *
     * @throws IOException when the registration cannot be made or does not answer with the two
     *   numbers the playback request needs.
     */
    fun register(http: OkHttpClient, now: Long): PlaybackIdentity {
        val body = registerBody(now, UUID.randomUUID().toString())
        val request = Request.Builder().url(REGISTER_URL)
            .header("User-Agent", REGISTER_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/octet-stream;tt-data=a")
            .header("log-encode-type", "gzip")
            .header("x-ss-dp", "8662")
            .header("x-ss-req-ticket", now.toString())
            .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
            .header("lc", "101")
            .header("sdk-version", "2")
            .header("passport-sdk-version", "5051452")
            .header("x-neptune", "-8|50:51:59:30:40:47:49:39")
            .header("x-ss-stub", digest("MD5", body).hex().uppercase())
            .post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
            .build()
        val text = RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            response.body?.string() ?: throw IOException("播放设备注册响应为空")
        }
        val json = try { JSONObject(text) } catch (e: Exception) { throw IOException("播放设备注册响应无法解析", e) }
        // The registration answer carries no status field at all - it answers with the identity
        // itself (`server_time`, `device_id`, `install_id`, `device_token`) and no `code`. A
        // missing field is therefore not a refusal; only a field that is there and says otherwise
        // is. Reading the absent field as a failure threw away a registration that had succeeded.
        val code = when {
            json.has("code") -> json.optInt("code", -1)
            json.has("Code") -> json.optInt("Code", -1)
            else -> 0
        }
        val deviceId = responseId(json, "device_id")
        val iid = responseId(json, "install_id")
        if (code != 0 || deviceId.isEmpty() || iid.isEmpty())
            throw IOException("播放设备注册未返回可用身份 code=$code device=$deviceId iid=$iid")
        return PlaybackIdentity(deviceId, iid)
    }

    /**
     * The number the registration answer carries.
     *
     * The answer spells it two ways - a number and its string form - and the string form is the
     * one the playback request wants, because a number that arrived as a double would lose its
     * last digits on the way to the query.
     */
    private fun responseId(response: JSONObject, name: String): String {
        val preferred = response.optString("${name}_str")
        if (preferred.isNotEmpty() && preferred.matches(Regex("^[0-9]{1,30}$")) && preferred.any { it != '0' }) return preferred
        val raw = response.opt(name)
        return if (raw is Number && raw.toLong() > 0) raw.toString() else ""
    }

    /**
     * The ttEncrypt body the registration expects.
     *
     * The layout is the app's own: a six byte marker, thirty two random bytes, and the
     * envelope - the digest of the compressed JSON followed by the compressed JSON itself -
     * encrypted with AES-128-CBC under a key derived from the random bytes.
     */
    private fun registerBody(now: Long, cdid: String): ByteArray {
        val header = JSONObject().apply {
            put("display_name", "红果免费短剧")
            put("update_version_code", 73932)
            put("manifest_version_code", 73932)
            put("app_version_minor", "7.3.9.32")
            put("aid", 8662)
            put("channel", "update_64")
            put("package", "com.phoenix.read")
            put("app_version", "7.3.9.32")
            put("version_code", 73932)
            put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
            put("sdk_target_version", 29)
            put("git_hash", "3a88f4e")
            put("os", "Android")
            put("os_version", "16")
            put("os_api", 36)
            put("device_model", "25053RT47C")
            put("device_brand", "Redmi")
            put("device_manufacturer", "Redmi")
            put("cpu_abi", "arm64-v8a")
            put("release_build", "b5e187f")
            put("density_dpi", 520)
            put("display_density", "mdpi")
            put("resolution", "1280x2772")
            put("language", "zh")
            put("timezone", 8)
            put("access", "wifi")
            put("not_request_sender", 0)
            put("carrier", "CHINA MOBILE")
            put("mcc_mnc", "46000")
            put("rom", "eng.root.20231116.160340")
            put("rom_version", "W528JS release-keys")
            put("cdid", cdid)
            put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
            put("openudid", ByteArray(8).also { SecureRandom().nextBytes(it) }.hex())
            put("clientudid", UUID.randomUUID().toString())
            put("ipv6_list", JSONArray())
            put("region", "CN")
            put("tz_name", "Asia/Shanghai")
            put("tz_offset", 28800)
            put("sim_region", "cn")
            put("sim_serial_number", JSONArray())
            put("oaid_may_support", false)
            put("req_id", UUID.randomUUID().toString())
            put("device_platform", "android")
            put("custom", JSONObject().apply {
                put("account_region_src", "fallback")
                put("account_region", "cn")
                put("host_bit", 64)
                put("dragon_device_type", "phone")
            })
            put("pre_installed_channel", "")
            put("apk_first_install_time", now)
            put("is_system_app", 0)
            put("sdk_flavor", "china")
            put("guest_mode", 0)
        }
        val envelope = JSONObject().apply {
            put("magic_tag", "ss_app_log")
            put("header", header)
            put("_gen_time", now)
        }.toString().toByteArray(Charsets.UTF_8)
        val compressed = gzip(envelope)
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val derived = digest("SHA-512", digest("SHA-512", random) + hex(KDF_SALT))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.copyOfRange(0, 16), "AES"), IvParameterSpec(derived.copyOfRange(16, 32)))
        val plain = digest("SHA-512", compressed) + compressed
        return hex("746305100000") + random + cipher.doFinal(plain)
    }

    private fun gzip(value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(value) }
        return out.toByteArray()
    }
}
