// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 协议档案的验收。
 *
 * 三件事必须一直成立：
 *
 *  1. **行为不变**：档案驱动的签名与改造前逐字节一致（[medusaIsByteIdenticalToTheSignerBeforeTheProfileRefactor]）。
 *  2. **仓库里那份下发文件 == 内置默认**：站方改字段集时改的是它，不是代码。
 *  3. **坏档案宁可不采纳**：解析不过、校验不过、版本号不新，都继续用内置默认。
 */
class PlaybackProfileTest {

    /** 固定时钟：两边必须拿到同一串时间。 */
    private val fixedClock: () -> Long = { 1_760_000_000_000L }

    /** 进程 nonce：25 个字符、首位 A，正好走 sec_device_token 的原样返回那一支。 */
    private val nonce = "A" + "B".repeat(24)

    /** 确定性随机：LCG。两边各一份，序列完全相同。 */
    private class Lcg(seed: Long) : () -> Double {
        private var state = seed
        override fun invoke(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble())
        }
    }

    /** 身份：medusa 明文与 query 都要对得上同一份。 */
    private val identity = mapOf(
        "cdid" to "cdid-0001",
        "iid" to "iid-0002",
        "device_id" to "device-id-0003",
        "klink_egdi" to "klink-0004",
        "version_name" to "7.4.3.32",
        "device_type" to "PGT-AN10",
        "device_brand" to "HONOR",
        "os_version" to "12",
        "channel" to "vivo_8662_64",
    )

    /**
     * 逐字节基准：档案驱动之后，medusa 与 helios 必须和改造前完全一样。
     *
     * 参照件是改造前那版 Signer 的原样拷贝（LegacyV5Signer），两边喂同一个时钟、
     * 同一个进程 nonce、同一串随机数。任何一个字段的顺序或取值变了，这里就会红。
     */
    @Test fun medusaIsByteIdenticalToTheSignerBeforeTheProfileRefactor() {
        val legacy = LegacyV5Signer(clock = fixedClock, processNonce = nonce, mobileV5 = true, random = Lcg(7))
        val current = Signer(clock = fixedClock, processNonce = nonce, mobileV5 = true, random = Lcg(7))
        val body = "{\"biz_param\":{}}".toByteArray(Charsets.UTF_8)
        val ts = 1_760_000_000
        // v5 的 hash 分支只有 12 一支：先找一个落在那一支上的 query（跟 signPlayback 做的事一样）。
        val base = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"
        var query = ""
        for (ticket in 0..1023) {
            val candidate = "device_platform=android&device_id=device-id-0003&version_name=7.4.3.32&_rticket=$ticket"
            if (current.branch(candidate, body, ts) == 0) { query = candidate; break }
        }
        assertTrue("找不到落在 v5 分支 12 上的 query", query.isNotEmpty())
        val url = "$base?$query"
        assertEquals(legacy.medusa(url, body, ts, identity), current.medusa(url, body, ts, identity))
        assertEquals(legacy.helios(ts), current.helios(ts))
    }

    /** 内置默认自己往返一次还得是同一份（否则档案一落地就变了行为）。 */
    @Test fun theBuiltinProfileSurvivesTheJsonRoundTrip() {
        val builtin = PlaybackProfile.builtin()
        val json = builtin.toJson().put("revision", 1)
        val round = PlaybackProfile.parse(json.toString())
        assertJsonEquals(builtin.toJson().getJSONObject("v5"), round.toJson().getJSONObject("v5"))
    }

    /**
     * 仓库里下发的那份必须等于内置默认。
     *
     * 这条守的是「第一次上线拉到远端档案时行为零变化」：两边数据一样，只是版本号不同。
     */
    @Test fun theShippedProfileMatchesTheBuiltinDefault() {
        // 测试的工作目录是 kotlin-tv/core/，所以 ../.. 回到仓库根。
        val file = File("../../playback-profile.json")
        assumeTrue("仓库里没有 ${file.path}（cwd=${File(".").absolutePath}），跳过", file.isFile)
        val shipped = PlaybackProfile.parse(file.readText(Charsets.UTF_8))
        val builtin = PlaybackProfile.builtin()
        assertJsonEquals(builtin.toJson().getJSONObject("v5"), shipped.toJson().getJSONObject("v5"))
        assertTrue("下发档案的版本号必须大于 0", shipped.revision >= 1)
    }

    /** 坏档案一律拒绝：宁可不采纳，也不要拿半对的档案去签名。 */
    @Test fun brokenProfilesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackProfile.parse(PlaybackProfile.builtin().toJson().put("revision", 1).put("schema", 2).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val json = PlaybackProfile.builtin().toJson().put("revision", 1)
            json.getJSONObject("v5").put("sbox", "00")
            PlaybackProfile.parse(json.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            // 少一个 medusa 明文要用的身份字段（device_id）就是坏档案：删掉这一条，不是改它的值。
            val json = PlaybackProfile.builtin().toJson().put("revision", 1)
            val deviceProfile = json.getJSONObject("v5").getJSONObject("deviceProfile")
            val fields = deviceProfile.getJSONArray("fields")
            val kept = org.json.JSONArray()
            for (index in 0 until fields.length()) {
                val item = fields.getJSONObject(index)
                if (item.getString("key") != "device_id") kept.put(item)
            }
            deviceProfile.put("fields", kept)
            PlaybackProfile.parse(json.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val json = PlaybackProfile.builtin().toJson().put("revision", 1)
            val fields = json.getJSONObject("v5").getJSONObject("deviceProfile").getJSONArray("fields")
            for (index in 0 until fields.length()) {
                if (fields.getJSONObject(index).getString("key") == "cdid") {
                    fields.getJSONObject(index).put("value", "\${不存在的字段}")
                }
            }
            PlaybackProfile.parse(json.toString())
        }
    }

    /** 版本号只增不减：旧的档案不能把新的顶掉。 */
    @Test fun onlyANewerProfileIsAdopted() {
        PlaybackProfiles.reset()
        try {
            assertTrue(PlaybackProfiles.apply(profileWithRevision(1)))
            assertFalse("同版本号不采纳", PlaybackProfiles.apply(profileWithRevision(1)))
            assertTrue(PlaybackProfiles.apply(profileWithRevision(2)))
            assertFalse("旧版本号不采纳", PlaybackProfiles.apply(profileWithRevision(1)))
            assertEquals(2L, PlaybackProfiles.active.revision)
        } finally {
            PlaybackProfiles.reset()
        }
    }

    /**
     * 设备档案的字段与顺序是签名的一部分：顺序变了就是另一份签名。
     *
     * 这里把改造前那套固定顺序原样钉住（29 个固定字段 + rom_version + iid + device_id + klink_egdi）。
     */
    @Test fun theDeviceProfileKeepsItsHistoricalOrder() {
        val profile = PlaybackProfile.builtin()
        val query = profile.deviceQuery(
            mapOf("cdid" to "c", "iid" to "i", "device_id" to "d", "klink_egdi" to ""),
            withRomVersion = false,
        )
        assertEquals(
            listOf(
                "device_platform", "os", "ssmix", "cdid", "channel", "aid", "app_name",
                "version_code", "version_name", "manifest_version_code", "update_version_code",
                "resolution", "dpi", "device_type", "device_brand", "language", "os_api",
                "os_version", "ac", "host_abi", "dragon_device_type", "pv_player",
                "compliance_status", "need_personal_recommend", "player_so_load",
                "is_android_pad_screen", "iid", "device_id",
            ),
            query.keys.toList(),
        )
        assertFalse("单集门不带 rom_version", query.containsKey("rom_version"))
        assertFalse("klink_egdi 为空时不下发", query.containsKey("klink_egdi"))
    }

    /** 分集门：设备档案 + 业务参数，rom_version 要在。 */
    @Test fun theTabGateCarriesTheEpisodeAndTheDeviceLevel() {
        val profile = PlaybackProfile.builtin()
        val query = profile.tabQuery(
            mapOf("cdid" to "c", "iid" to "i", "device_id" to "d", "klink_egdi" to "k"),
            "7001",
        )
        assertEquals("7001", query["video_id"])
        assertEquals("1", query["device_level"])
        assertTrue(query.containsKey("rom_version"))
        assertEquals("k", query["klink_egdi"])
    }

    /** 单集门正文模板：剧集 id 与设备档位要填进去。 */
    @Test fun theModelBodyFillsInTheEpisodeAndTheDeviceLevel() {
        val body = PlaybackProfile.builtin().modelBody("7001")
        assertTrue(body.contains("\"1004\":[\"7001\"]"))
        assertTrue(body.contains("\"device_level\":1"))
    }

    /** 下发通道：只走 HTTPS、只认白名单里的域。 */
    @Test fun theProfileComesFromAnAllowedHttpsHost() {
        val url = PlaybackProfile.REMOTE_URL
        assertTrue(url.startsWith("https://"))
        val host = url.substringAfter("https://").substringBefore('/')
        assertTrue("下发域不在白名单里：$host", host in PlaybackProfileRepository.ALLOWED_HOSTS)
    }

    /** 造一份指定版本号的档案（基于内置默认）。 */
    private fun profileWithRevision(revision: Long): PlaybackProfile =
        PlaybackProfile.parse(PlaybackProfile.builtin().toJson().put("revision", revision).toString())

    /** 结构化比较 JSON：对象的键顺序不比，数组的顺序要比（protobuf 字段表是顺序敏感的）。 */
    private fun assertJsonEquals(expected: Any?, actual: Any?) {
        when {
            expected is JSONObject && actual is JSONObject -> {
                assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
                for (key in expected.keys()) assertJsonEquals(expected.get(key), actual.get(key))
            }
            expected is JSONArray && actual is JSONArray -> {
                assertEquals(expected.length(), actual.length())
                for (index in 0 until expected.length()) assertJsonEquals(expected.get(index), actual.get(index))
            }
            else -> assertEquals(expected?.toString(), actual?.toString())
        }
    }
}
