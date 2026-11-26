// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 站方会改的那部分数据。
 *
 * 分工划得很清楚：
 *
 *  - **这份档案（数据）**：medusa 明文的字段表、magic / license / signKey / sbox、
 *    取址门的 host / path / UA、query 里的设备档案。站方改的是这些 —— 2026-11-16 换了一代
 *    取址协议，2026-11-23 又换了一次 medusa 的字段集，两次都是「数据」变了，算法一次没动。
 *  - **代码（算法）**：xmxor / hashF13 / sm3 / AesV3 的表与位序、随机量的取法、
 *    编码规则。站方历史上一次都没动过这些，也不该由远端下发。
 *
 * 端上从远端拉这份 JSON 覆盖内置默认；拉不到、解析不过、校验不过、版本号不新，
 * 一律继续用内置默认 —— 行为与改造前完全一致。
 */
class PlaybackProfile(
    val schema: Int,
    val revision: Long,
    val updatedAt: String,
    val note: String,
    val v5: V5Profile,
) {
    /**
     * 取址这一门（v5）的全部档案。
     *
     * @param magic medusa 明文里的 magic 与密文前缀，共用一份。
     * @param license 许可证号：medusa 明文与 helios 里各用一次。
     * @param signKey 签名密钥：明文哈希与密文密钥都从它派生。
     * @param sbox v5 专用的字节替换表（1024 字节，4 组 × 256）。
     * @param headers 这一次签名带哪几个头（顺序即写入顺序）。
     */
    class V5Profile(
        val magic: ByteArray,
        val license: String,
        val signKey: ByteArray,
        val sbox: ByteArray,
        val headers: List<String>,
        val deviceLevel: Int,
        val medusa: MedusaProfile,
        val modelGate: GateProfile,
        val tabGate: TabGateProfile,
        val deviceProfile: DeviceProfileSpec,
        val tabQuery: DeviceProfileSpec,
        val modelBody: String,
    )

    /** 一个 protobuf 字段：tag + 类型 + 取值。 */
    class ProtoField(val tag: Int, val type: String, val literal: Any?, val ref: String?) {
        /** 取值的来源：字面量，或者一个运行时引用（`${名字}`）。 */
        fun resolve(values: ProtoValues): Any =
            if (ref != null) values.get(ref) else literal ?: throw IllegalStateException("协议字段 ${tag} 没有取值")
    }

    /** medusa 明文里的五张字段表。顺序敏感：protobuf 的字段按写入顺序编码。 */
    class MedusaProfile(
        val flags: ByteArray,
        val device: List<ProtoField>,
        val env: List<ProtoField>,
        val envSub13: List<ProtoField>,
        val nested: List<ProtoField>,
        val message: List<ProtoField>,
    )

    /** 单集取址门（POST）：一个 host、一条路径、一个 UA。 */
    class GateProfile(val host: String, val path: String, val userAgent: String)

    /** 分集取址门（GET）：若干 host、一条路径、一个 UA。 */
    class TabGateProfile(val hosts: List<String>, val path: String, val userAgent: String)

    /** query 里的一个字段：名字 + 取值 + 什么时候才带上。 */
    class DeviceField(val key: String, val literal: String?, val ref: String?, val whenMode: String) {
        fun resolve(base: Map<String, String>): String =
            if (ref != null) base[ref] ?: throw IllegalStateException("设备档案引用了未知的身份字段：$ref")
            else literal.orEmpty()
    }

    /** 一张有序的 query 字段表。 */
    class DeviceProfileSpec(val fields: List<DeviceField>)

    /**
     * 单集取址门（model 门）那套设备档案。
     *
     * 顺序不能改：签名把 query 的字节原样算进去，顺序变了就是另一份签名。
     *
     * @param withRomVersion 分集门要带 rom_version，单集门不带。
     * @param identity 站方发的身份（cdid / iid / device_id / klink_egdi）。
     */
    fun deviceQuery(identity: Map<String, String>, withRomVersion: Boolean): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in v5.deviceProfile.fields) {
            if (field.whenMode == WHEN_ROM && !withRomVersion) continue
            val value = field.resolve(identity)
            if (field.whenMode == WHEN_NON_EMPTY && value.isBlank()) continue
            out[field.key] = value
        }
        return out
    }

    /**
     * 分集取址门（tab 门）的完整 query：设备档案 + 业务参数。
     *
     * 业务参数也是站方的数据：少一个服务端回 100103 PARAM_INVALID，所以一并外置。
     */
    fun tabQuery(identity: Map<String, String>, id: String): LinkedHashMap<String, String> {
        val base = HashMap(identity)
        base["id"] = id
        base["device_level"] = v5.deviceLevel.toString()
        val out = deviceQuery(identity, withRomVersion = true)
        for (field in v5.tabQuery.fields) {
            val value = field.resolve(base)
            if (field.whenMode == WHEN_NON_EMPTY && value.isBlank()) continue
            out[field.key] = value
        }
        return out
    }

    /** 单集取址门的请求正文模板：把剧集 id 与设备档位填进去。 */
    fun modelBody(id: String): String =
        v5.modelBody.replace("\${id}", id).replace("\${device_level}", v5.deviceLevel.toString())

    /** 摊成 JSON（写缓存、生成仓库里那份下发文件都走这里）。 */
    fun toJson(): JSONObject {
        val v5Json = JSONObject()
        v5Json.put("magic", v5.magic.toHex())
        v5Json.put("license", v5.license)
        v5Json.put("signKey", v5.signKey.toHex())
        v5Json.put("sbox", v5.sbox.toHex())
        v5Json.put("headers", JSONArray(v5.headers))
        v5Json.put("deviceLevel", v5.deviceLevel)
        val medusa = JSONObject()
        medusa.put("flags", v5.medusa.flags.toHex())
        medusa.put("device", v5.medusa.device.toJson())
        medusa.put("env", v5.medusa.env.toJson())
        medusa.put("envSub13", v5.medusa.envSub13.toJson())
        medusa.put("nested", v5.medusa.nested.toJson())
        medusa.put("message", v5.medusa.message.toJson())
        v5Json.put("medusa", medusa)
        val gates = JSONObject()
        gates.put("model", v5.modelGate.toJson())
        val tab = JSONObject()
        tab.put("hosts", JSONArray(v5.tabGate.hosts))
        tab.put("path", v5.tabGate.path)
        tab.put("userAgent", v5.tabGate.userAgent)
        gates.put("tab", tab)
        v5Json.put("gates", gates)
        v5Json.put("deviceProfile", v5.deviceProfile.toJson())
        v5Json.put("tabQuery", v5.tabQuery.toJson())
        v5Json.put("modelBody", v5.modelBody)
        val root = JSONObject()
        root.put("schema", schema)
        root.put("revision", revision)
        root.put("updatedAt", updatedAt)
        root.put("note", note)
        root.put("v5", v5Json)
        return root
    }

    private fun GateProfile.toJson(): JSONObject =
        JSONObject().put("host", host).put("path", path).put("userAgent", userAgent)

    private fun DeviceProfileSpec.toJson(): JSONObject {
        val array = JSONArray()
        for (field in fields) {
            val item = JSONObject().put("key", field.key)
            item.put("value", if (field.ref != null) "\${${field.ref}}" else field.literal.orEmpty())
            item.put("when", field.whenMode)
            array.put(item)
        }
        return JSONObject().put("fields", array)
    }

    private fun List<ProtoField>.toJson(): JSONArray {
        val array = JSONArray()
        for (field in this) {
            val item = JSONObject().put("tag", field.tag).put("type", field.type)
            when (field.type) {
                "bytes" -> item.put("value", if (field.ref != null) "\${${field.ref}}" else (field.literal as ByteArray).toHex())
                "float" -> item.put("value", if (field.ref != null) "\${${field.ref}}" else field.literal)
                else -> item.put("value", if (field.ref != null) "\${${field.ref}}" else field.literal)
            }
            array.put(item)
        }
        return array
    }

    companion object {
        /** 档案格式版本。改结构就要改这里，旧档案会被拒绝而不是被误读。 */
        const val SCHEMA = 1
        /** 档案的最大字节数。远端下发的档案应该是几 KB。 */
        const val MAX_BYTES = 256 * 1024
        /** 下发地址：本仓库 main 分支上的那份 JSON。 */
        const val REMOTE_URL = "https://raw.githubusercontent.com/miaoxiaohu/hongguoTV/main/playback-profile.json"

        const val WHEN_ALWAYS = "always"
        const val WHEN_ROM = "rom"
        const val WHEN_NON_EMPTY = "nonEmpty"

        /** 运行时取值里，身份相关的名字（必须由设备档案提供）。 */
        private val IDENTITY_REFS = setOf("device_id", "version_name", "device_type", "device_brand", "os_version", "channel")

        /** 运行时取值里，协议自己算出来的名字。 */
        private val RUNTIME_REFS = setOf(
            "ts", "nowMs", "processStarted", "rand", "envLaunch", "envPid",
            "f13", "query6", "secToken", "magic", "license", "flags",
            "device", "env", "envSub13", "nested",
        )

        /** host 的合法形状。 */
        val HOST_PATTERN = Regex("[a-z0-9.-]{1,120}")

        /** 设备档案（model 门 query）里允许引用的身份字段。 */
        private val IDENTITY_QUERY_REFS = setOf("cdid", "iid", "device_id", "klink_egdi")

        /** 分集参数（tab 门 query）里允许引用的字段。 */
        private val TAB_QUERY_REFS = IDENTITY_QUERY_REFS + setOf("id", "device_level")

        /** medusa 各阶段可用引用的类型表，用于严格校验类型兼容与求值顺序。 */
        private val MEDUSA_BASE_REF_TYPES = mapOf(
            "ts" to "sint", "nowMs" to "sint", "processStarted" to "sint",
            "envLaunch" to "sint", "envPid" to "sint",
            "device_id" to "string", "version_name" to "string", "device_type" to "string",
            "device_brand" to "string", "os_version" to "string", "channel" to "string",
            "secToken" to "string", "license" to "string",
            "f13" to "bytes", "query6" to "bytes", "magic" to "bytes", "flags" to "bytes",
        )

        /** medusa 必须能从设备档案里拿到的字段（少一个就是 HTTP 200 + 0 字节）。 */
        private val REQUIRED_DEVICE_KEYS = setOf("device_id", "version_name", "device_type", "device_brand", "os_version", "channel")

        fun parse(text: String): PlaybackProfile {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "协议档案过大" }
            return fromJson(JSONObject(text))
        }

        fun fromJson(root: JSONObject): PlaybackProfile {
            require(root.optInt("schema", -1) == SCHEMA) { "协议档案版本不认识" }
            val revision = root.optLong("revision", -1)
            require(revision >= 1) { "协议档案版本号非法" }
            val v5 = root.getJSONObject("v5")
            val medusaJson = v5.getJSONObject("medusa")
            val gates = v5.getJSONObject("gates")
            val profile = PlaybackProfile(
                SCHEMA, revision,
                root.optString("updatedAt"), root.optString("note"),
                V5Profile(
                    magic = hexBytes(v5.getString("magic"), "magic"),
                    license = v5.getString("license"),
                    signKey = hexBytes(v5.getString("signKey"), "signKey"),
                    sbox = hexBytes(v5.getString("sbox"), "sbox"),
                    headers = v5.getJSONArray("headers").let { array ->
                        (0 until array.length()).map { array.getString(it) }
                    },
                    deviceLevel = v5.getInt("deviceLevel"),
                    medusa = MedusaProfile(
                        flags = hexBytes(medusaJson.getString("flags"), "medusa.flags"),
                        device = protoFields(medusaJson.getJSONArray("device")),
                        env = protoFields(medusaJson.getJSONArray("env")),
                        envSub13 = protoFields(medusaJson.getJSONArray("envSub13")),
                        nested = protoFields(medusaJson.getJSONArray("nested")),
                        message = protoFields(medusaJson.getJSONArray("message")),
                    ),
                    modelGate = gate(gates.getJSONObject("model")),
                    tabGate = TabGateProfile(
                        hosts = gates.getJSONObject("tab").getJSONArray("hosts").let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        },
                        path = gates.getJSONObject("tab").getString("path"),
                        userAgent = gates.getJSONObject("tab").getString("userAgent"),
                    ),
                    deviceProfile = deviceSpec(v5.getJSONObject("deviceProfile")),
                    tabQuery = deviceSpec(v5.getJSONObject("tabQuery")),
                    modelBody = v5.getString("modelBody"),
                ),
            )
            profile.validate()
            return profile
        }

        private fun gate(o: JSONObject) = GateProfile(o.getString("host"), o.getString("path"), o.getString("userAgent"))

        private fun protoFields(array: JSONArray): List<ProtoField> =
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val tag = item.getInt("tag")
                val type = item.getString("type")
                require(type in setOf("sint", "float", "string", "bytes")) { "协议字段类型不认识：$type" }
                require(tag in 1..2047) { "协议字段 tag 非法：$tag" }
                val raw = item.get("value")
                when (type) {
                    "sint" -> if (raw is String) ProtoField(tag, type, null, refName(raw))
                    else ProtoField(tag, type, (raw as Number).toLong(), null)
                    "float" -> if (raw is String) ProtoField(tag, type, null, refName(raw))
                    else ProtoField(tag, type, (raw as Number).toDouble(), null)
                    "string" -> if (raw is String && raw.startsWith("\${")) ProtoField(tag, type, null, refName(raw))
                    else ProtoField(tag, type, raw as String, null)
                    else -> if (raw is String && raw.startsWith("\${")) ProtoField(tag, type, null, refName(raw))
                    else ProtoField(tag, type, hexBytes(raw as String, "字段 $tag"), null)
                }
            }

        private fun deviceSpec(o: JSONObject): DeviceProfileSpec {
            val array = o.getJSONArray("fields")
            val fields = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val key = item.getString("key")
                require(key.matches(Regex("[a-z0-9_]{1,40}"))) { "设备档案字段名非法：$key" }
                val whenMode = item.optString("when", WHEN_ALWAYS)
                require(whenMode in setOf(WHEN_ALWAYS, WHEN_ROM, WHEN_NON_EMPTY)) { "设备档案条件不认识：$whenMode" }
                val raw = item.getString("value")
                if (raw.startsWith("\${")) DeviceField(key, null, refName(raw), whenMode)
                else DeviceField(key, raw, null, whenMode)
            }
            require(fields.isNotEmpty()) { "设备档案是空的" }
            require(fields.map { it.key }.distinct().size == fields.size) { "设备档案里有重名字段" }
            return DeviceProfileSpec(fields)
        }

        /** `${名字}` → 名字。 */
        private fun refName(raw: String): String {
            require(raw.startsWith("\${") && raw.endsWith("}")) { "引用写法不对：$raw" }
            return raw.substring(2, raw.length - 1)
        }

        private fun hexBytes(text: String, what: String): ByteArray {
            require(text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" }) { "$what 不是合法的十六进制" }
            val out = ByteArray(text.length / 2)
            for (i in out.indices) out[i] = ((Character.digit(text[i * 2], 16) shl 4) or Character.digit(text[i * 2 + 1], 16)).toByte()
            return out
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /**
         * 内置默认：与改造前的代码逐字节一致。
         *
         * 仓库里下发的那份 JSON 就是这一份的序列化（单测守着这条），所以第一次上线
         * 拉到远端档案时行为不会有任何变化。
         */
        fun builtin(): PlaybackProfile {
            val profile = PlaybackProfile(
                // 版本号 0 = 内置默认（没被下发覆盖过）。下发的那份从 1 开始。
                SCHEMA, 0L, "2026-11-23", "内置默认：站方 2026-11-23 那套 v5 取址协议",
                V5Profile(
                    magic = V5Constants.magic,
                    license = V5Constants.license,
                    signKey = V5Constants.signKey,
                    sbox = V5Constants.sbox,
                    headers = listOf("x-khronos", "x-helios", "x-medusa"),
                    deviceLevel = 1,
                    medusa = MedusaProfile(
                        flags = hexBytes("4480000000000000", "medusa.flags"),
                        device = listOf(
                            sint(1, 1), sint(2, 2), str(3, "8662"), strRef(4, "device_id"),
                            strRef(5, "secToken"), str(6, "!noperm!"),
                            sint(7, -888888), sint(8, -888888), sint(9, 3), sint(10, -888888),
                            str(11, "!notset!"), str(12, "Asia/Shanghai,8"), str(13, "zh_CN"),
                            sint(14, 4),
                            flt(16, 0.0), flt(17, 0.0), flt(18, 0.0),
                            flt(19, 0.0), flt(20, 0.0), flt(21, 0.0),
                            strRef(22, "os_version"), sint(23, 41), sint(24, 36),
                            sintRef(25, "processStarted"), sintRef(26, "processStarted"),
                            sintRef(27, "processStarted"), sintRef(28, "nowMs"),
                            sint(29, -1),
                            strRef(30, "device_type"), strRef(31, "device_brand"),
                            strRef(32, "device_type"), strRef(33, "device_type"),
                            strRef(34, "device_brand"), strRef(35, "device_brand"), strRef(36, "device_brand"),
                            sint(38, 31),
                        ),
                        env = listOf(
                            sintRef(1, "envLaunch"), sint(2, 247235599), sint(3, 247235596), sint(5, 7),
                            str(6, "v04.10.03.04-bugfix"), sintRef(7, "envPid"),
                            strRef(8, "channel"),
                            bytesRef(12, "device"), bytesRef(13, "envSub13"),
                            strRef(14, "version_name"),
                        ),
                        envSub13 = listOf(sintRef(1, "ts"), sint(2, -2), sint(4, 200)),
                        nested = listOf(sint(1, 305), sint(2, 4), sint(3, 694367), sint(5, 1621072674)),
                        message = listOf(
                            bytesRef(1, "magic"), sint(2, 5), sintRef(3, "rand"),
                            str(4, "8662"), strRef(5, "device_id"), strRef(6, "license"),
                            strRef(7, "version_name"), str(8, "v04.10.03-ml-android"), sint(9, 0x040a0300L),
                            bytesRef(10, "flags"),
                            sintRef(12, "ts"), bytesRef(13, "f13"), bytesRef(14, "query6"),
                            bytesRef(15, "nested"),
                            strRef(16, "secToken"),
                            sintRef(17, "ts"), str(20, "none"), sint(21, 312),
                            bytesRef(23, "env"),
                            str(24, """{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}"""),
                        ),
                    ),
                    modelGate = GateProfile(
                        "api5-normal-sinfonlinea.fqnovel.com",
                        "/novel/player/multi_video_model/player/v1",
                        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
                    ),
                    tabGate = TabGateProfile(
                        listOf("api5-normal-sinfonlineb.fqnovel.com", "api5-normal-hl.fqnovel.com"),
                        "/reading/bookapi/video_tab/multi_video_model/v1/",
                        "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)",
                    ),
                    deviceProfile = DeviceProfileSpec(listOf(
                        device("device_platform", "android"), device("os", "android"), device("ssmix", "a"),
                        deviceRef("cdid", "cdid"),
                        device("channel", "vivo_8662_64"), device("aid", "8662"), device("app_name", "novelread"),
                        device("version_code", "74332"), device("version_name", "7.4.3.32"),
                        device("manifest_version_code", "74332"), device("update_version_code", "74332"),
                        device("resolution", "1920*1080"), device("dpi", "280"),
                        device("device_type", "PGT-AN10"), device("device_brand", "HONOR"),
                        device("language", "zh"), device("os_api", "31"), device("os_version", "12"),
                        device("ac", "wifi"), device("host_abi", "arm64-v8a"),
                        device("dragon_device_type", "phone"), device("pv_player", "74332"),
                        device("compliance_status", "0"), device("need_personal_recommend", "1"),
                        device("player_so_load", "1"), device("is_android_pad_screen", "0"),
                        device("rom_version", "V417IR+release-keys", WHEN_ROM),
                        deviceRef("iid", "iid"), deviceRef("device_id", "device_id"),
                        deviceRef("klink_egdi", "klink_egdi", WHEN_NON_EMPTY),
                    )),
                    tabQuery = DeviceProfileSpec(listOf(
                        deviceRef("video_id", "id"), device("video_id_type", "0"),
                        deviceRef("device_level", "device_level"),
                        device("need_all_video_definition", "1"), device("need_mp4_align", "0"),
                        device("use_os_player", "0"), device("video_platform", "1024"),
                        device("source", "0"), device("from_video_id", ""),
                    )),
                    modelBody = """{"biz_param":{"detail_page_version":0,"device_level":${'$'}{device_level},"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["${'$'}{id}"]},"dr_scene":"player"}""",
                ),
            )
            return profile
        }

        private fun sint(tag: Int, value: Long) = ProtoField(tag, "sint", value, null)
        private fun sintRef(tag: Int, ref: String) = ProtoField(tag, "sint", null, ref)
        private fun flt(tag: Int, value: Double) = ProtoField(tag, "float", value, null)
        private fun str(tag: Int, value: String) = ProtoField(tag, "string", value, null)
        private fun strRef(tag: Int, ref: String) = ProtoField(tag, "string", null, ref)
        private fun bytesRef(tag: Int, ref: String) = ProtoField(tag, "bytes", null, ref)
        private fun device(key: String, value: String, whenMode: String = WHEN_ALWAYS) = DeviceField(key, value, null, whenMode)
        private fun deviceRef(key: String, ref: String, whenMode: String = WHEN_ALWAYS) = DeviceField(key, null, ref, whenMode)
    }

    /**
     * 严格校验：宁可拒绝一份档案，也不要用一份半对的档案去签名。
     *
     * 站方的封锁是静默的（HTTP 200 + 0 字节），所以这里挡的是「档案本身有问题」这种
     * 我们自己能发现的事：字段名写错、引用了不存在的取值、sbox 少了几组字节。
     */
    private fun validate() {
        require(v5.magic.size == 16) { "magic 必须是 16 字节" }
        require(v5.signKey.size == 32) { "signKey 必须是 32 字节" }
        require(v5.sbox.size == 1024) { "sbox 必须是 1024 字节（4 组 × 256）" }
        require(v5.license.isNotBlank()) { "license 不能为空" }
        require(v5.headers.isNotEmpty() && v5.headers.size <= 8) { "签名头列表不合法" }
        require(v5.headers.distinct().size == v5.headers.size) { "签名头列表里有重名" }
        require(v5.deviceLevel in 0..5) { "deviceLevel 不合法" }
        require(v5.medusa.flags.size == 8) { "medusa.flags 必须是 8 字节" }
        require(v5.modelGate.host.matches(HOST_PATTERN)) { "单集门 host 不合法" }
        require(v5.modelGate.path.startsWith("/")) { "单集门 path 必须以 / 开头" }
        require(v5.modelGate.userAgent.isNotBlank()) { "单集门 UA 不能为空" }
        require(v5.tabGate.hosts.isNotEmpty()) { "分集门没有 host" }
        for (host in v5.tabGate.hosts) require(host.matches(HOST_PATTERN)) { "分集门 host 不合法：$host" }
        require(v5.tabGate.path.startsWith("/")) { "分集门 path 必须以 / 开头" }
        require(v5.tabGate.userAgent.isNotBlank()) { "分集门 UA 不能为空" }
        require(v5.modelBody.contains("\${id}") && v5.modelBody.contains("\${device_level}")) { "单集门正文模板缺占位符" }
        try {
            JSONObject(modelBody("0"))
        } catch (_: Exception) {
            throw IllegalArgumentException("单集门正文模板不是合法 JSON")
        }

        val deviceKeys = v5.deviceProfile.fields.map { it.key }.toSet()
        for (key in REQUIRED_DEVICE_KEYS) require(deviceKeys.contains(key)) { "设备档案缺字段：$key" }
        for (field in v5.deviceProfile.fields) {
            if (field.ref != null) require(IDENTITY_QUERY_REFS.contains(field.ref)) { "设备档案引用了未知的身份字段：${field.ref}" }
        }
        for (field in v5.tabQuery.fields) {
            if (field.ref != null) require(TAB_QUERY_REFS.contains(field.ref)) { "分集参数引用了未知的取值：${field.ref}" }
        }

        val stages = listOf(
            v5.medusa.device to MEDUSA_BASE_REF_TYPES,
            v5.medusa.envSub13 to (MEDUSA_BASE_REF_TYPES + ("device" to "bytes")),
            v5.medusa.env to (MEDUSA_BASE_REF_TYPES + mapOf("device" to "bytes", "envSub13" to "bytes")),
            v5.medusa.nested to (MEDUSA_BASE_REF_TYPES + mapOf("device" to "bytes", "envSub13" to "bytes", "env" to "bytes")),
            v5.medusa.message to (MEDUSA_BASE_REF_TYPES + mapOf(
                "device" to "bytes", "envSub13" to "bytes", "env" to "bytes", "nested" to "bytes", "rand" to "sint",
            )),
        )
        for ((list, allowed) in stages) {
            require(list.isNotEmpty()) { "medusa 里有空字段表" }
            for (field in list) {
                val ref = field.ref ?: continue
                val expectedType = allowed[ref]
                require(expectedType != null) { "medusa 引用了未知或尚未生成的取值：$ref" }
                require(field.type == expectedType) { "medusa 字段 $ref 类型不匹配：期望 $expectedType，实际 ${field.type}" }
            }
        }
        // medusa 明文里引用到的身份字段，必须真的在设备档案里。
        val allMedusa = v5.medusa.device + v5.medusa.env + v5.medusa.envSub13 + v5.medusa.nested + v5.medusa.message
        val used = allMedusa.filter { it.ref in IDENTITY_REFS }.map { it.ref!! }.toSet()
        for (ref in used) require(deviceKeys.contains(ref)) { "设备档案缺 medusa 需要的字段：$ref" }
    }

}

/**
 * 端上当前生效的档案。
 *
 * 只有一个可变状态：生效的那份档案。内置默认永远是兜底 —— 远端拉不到、解析不过、
 * 校验不过、版本号不新，都不动它。
 */
object PlaybackProfiles {
    @Volatile
    private var current: PlaybackProfile = PlaybackProfile.builtin()

    /** 生效中的档案。 */
    val active: PlaybackProfile get() = current

    /**
     * 换一份档案。
     *
     * 版本号只增不减：远端档案比当前旧就丢掉（防止缓存回退，也防止有人把旧档案重新推上去）。
     */
    @Synchronized
    fun apply(profile: PlaybackProfile): Boolean {
        if (profile.revision <= current.revision) return false
        current = profile
        return true
    }

    /** 回到内置默认（只给测试用）。 */
    @Synchronized
    fun reset() {
        current = PlaybackProfile.builtin()
    }
}