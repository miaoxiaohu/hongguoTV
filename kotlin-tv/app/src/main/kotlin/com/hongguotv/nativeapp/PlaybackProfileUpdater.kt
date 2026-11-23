// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.PlaybackProfileRepository
import com.hongguotv.core.PlaybackProfileStore
import com.hongguotv.core.PlaybackProfiles
import java.io.File
import java.util.concurrent.Executors

/**
 * 协议档案的更新。
 *
 * 站方会改的那部分数据（medusa 字段表、magic/sbox、门地址、设备档案）从远端拉下来，
 * 覆盖内置默认；这样站方下次改字段集不用发版，改一下仓库里那份 JSON 就行。
 *
 * 规矩跟更新包一样：**前台拉、后台拉、拉不到就当没有**。
 * 拉不到、解析不过、版本号不新 —— 一律继续用内置默认，行为与改造前完全一致。
 */
class PlaybackProfileUpdater(context: Context) {
    private val store = PlaybackProfileStore(File(context.applicationContext.filesDir, "playback-profile.json"))
    private val repository = PlaybackProfileRepository()
    private val worker = Executors.newSingleThreadExecutor()

    /** 读本地缓存（很快，同步做）。读不到就继续用内置默认。 */
    fun loadCached() {
        store.load()?.let { PlaybackProfiles.apply(it) }
    }

    /** 后台拉一次远端档案。新版本才采纳。 */
    fun refresh() {
        worker.execute {
            try {
                val profile = repository.fetch()
                if (PlaybackProfiles.apply(profile)) store.save(profile)
            } catch (_: Exception) {
                // 拉不到不是故障：继续用当前生效的那份（内置默认或上次缓存的）。
            }
        }
    }

    fun close() {
        worker.shutdownNow()
        repository.cancel()
        // 关 TLS 连接会发 close_notify，不能在主线程上做：丢到子线程里去。
        Thread({ repository.close() }, "hongguotv-profile-cleanup").start()
    }
}
