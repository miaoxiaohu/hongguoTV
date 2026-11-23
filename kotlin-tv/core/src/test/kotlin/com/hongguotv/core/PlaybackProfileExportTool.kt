// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Test
import java.io.File

/**
 * 把内置默认导出成仓库里那份可下发的协议档案。
 *
 * 这是**正式工具**，不是临时诊断：站方下次改字段集时的流程是
 *
 *  1. 改 [PlaybackProfile.builtin] 里的数据（那才是事实来源）；
 *  2. 跑这个导出器，把新的档案写到仓库根的 playback-profile.json；
 *  3. 跑 `kotlin-tv/tools/order_profile.py` 排键顺序（好 diff）；
 *  4. 把版本号（revision）加一提交 —— 端上才肯采纳。
 *
 * 跑法（工作目录是 kotlin-tv/）：
 *
 *     .\gradlew.bat :core:test --tests 'com.hongguotv.core.PlaybackProfileExportTool' --console=plain --no-daemon
 *
 * 版本号、日期、说明可以覆盖：
 *
 *     .\gradlew.bat :core:test --tests 'com.hongguotv.core.PlaybackProfileExportTool' -Dprofile.revision=2 -Dprofile.updatedAt=2026-12-01
 */
class PlaybackProfileExportTool {
    @Test fun export() {
        val revision = System.getProperty("profile.revision")?.toLongOrNull() ?: 1L
        val updatedAt = System.getProperty("profile.updatedAt") ?: "2026-11-23"
        val note = System.getProperty("profile.note")
            ?: "站方 2026-11-23 那套 v5 取址协议：medusa 明文字段表、magic/license/signKey/sbox、单集门与分集门、query 设备档案。"
        val json = PlaybackProfile.builtin().toJson()
        json.put("revision", revision)
        json.put("updatedAt", updatedAt)
        json.put("note", note)
        // 测试的工作目录是 kotlin-tv/core/，../.. 回到仓库根。
        val file = File("../../playback-profile.json")
        file.writeText(json.toString(2), Charsets.UTF_8)
        println("已导出 " + file.absolutePath + " revision=" + revision + " bytes=" + file.length())
    }
}
