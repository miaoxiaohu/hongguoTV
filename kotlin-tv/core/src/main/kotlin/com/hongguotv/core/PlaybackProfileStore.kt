// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File

/**
 * 协议档案的本地缓存。
 *
 * 缓存的是远端下发的原文：解析不过就当没有缓存（继续用内置默认），
 * 不去猜、不去修补 —— 一份坏档案比没有档案更危险。
 */
class PlaybackProfileStore(private val file: File) {
    fun load(): PlaybackProfile? {
        if (!file.isFile) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        return try {
            PlaybackProfile.parse(text)
        } catch (_: Exception) {
            null
        }
    }

    /** 写缓存。先写临时文件再替换，免得中途断电留下半份档案。 */
    fun save(profile: PlaybackProfile) {
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        partial.writeText(profile.toJson().toString(), Charsets.UTF_8)
        try {
            java.nio.file.Files.move(
                partial.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            // 有些文件系统不支持原子替换，退回普通替换。
            java.nio.file.Files.move(
                partial.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
