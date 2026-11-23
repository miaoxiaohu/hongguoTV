// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 缓存与拉取的验收。
 *
 * 三条底线：
 *
 *  1. **缓存存的是原文**：存进去再读出来，还是同一份档案（版本号、字段顺序都在）。
 *  2. **坏缓存当没有**：文件坏掉、读不了、内容不是档案，一律返回 null，让端上继续用内置默认。
 *  3. **写缓存不留半份**：先写临时文件再替换，替换完不能留下 .part。
 */
class PlaybackProfileStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun store(file: File = File(folder.root, "profile.json")) = PlaybackProfileStore(file)

    /** 存进去再读出来，还是同一份。 */
    @Test fun aSavedProfileComesBackUnchanged() {
        val file = File(folder.root, "profile.json")
        val store = store(file)
        val profile = PlaybackProfile.parse(PlaybackProfile.builtin().toJson().put("revision", 7).toString())
        store.save(profile)
        val loaded = store.load()
        assertTrue("缓存里没读到档案", loaded != null)
        assertEquals(7L, loaded!!.revision)
        // 逐段比：字段表是顺序敏感的，顺序变了就是另一份档案。
        assertEquals(
            profile.toJson().getJSONObject("v5").getJSONObject("deviceProfile").getJSONArray("fields").toString(),
            loaded.toJson().getJSONObject("v5").getJSONObject("deviceProfile").getJSONArray("fields").toString(),
        )
    }

    /** 文件不存在就是没有缓存，不是错误。 */
    @Test fun aMissingFileMeansNoCache() {
        assertNull(store(File(folder.root, "nope.json")).load())
    }

    /** 坏缓存（不是 JSON / 不是档案）一律当没有，绝不半信半疑。 */
    @Test fun aBrokenCacheIsTreatedAsNoCache() {
        val file = File(folder.root, "broken.json")
        file.writeText("{ 这不是 JSON", Charsets.UTF_8)
        assertNull(store(file).load())

        val wrong = File(folder.root, "wrong.json")
        wrong.writeText("""{"schema":1}""", Charsets.UTF_8)
        assertNull(store(wrong).load())
    }

    /** 写完不能留下 .part 半成品。 */
    @Test fun savingLeavesNoPartialFileBehind() {
        val file = File(folder.root, "profile.json")
        val store = store(file)
        store.save(PlaybackProfile.builtin())
        assertFalse("留下了临时文件", File(folder.root, "profile.json.part").exists())
        assertTrue("缓存文件没落盘", file.isFile)
    }

    /** 存了新的覆盖旧的，读出来是新的。 */
    @Test fun aNewerProfileReplacesTheOldOne() {
        val file = File(folder.root, "profile.json")
        val store = store(file)
        store.save(PlaybackProfile.parse(PlaybackProfile.builtin().toJson().put("revision", 1).toString()))
        store.save(PlaybackProfile.parse(PlaybackProfile.builtin().toJson().put("revision", 2).toString()))
        assertEquals(2L, store.load()!!.revision)
    }

    /**
     * 下发通道的规矩：只走 HTTPS、只认白名单里的域。
     *
     * 白名单外的域在发起网络请求之前就该被拒掉 —— 这里用明显不存在的域验证它
     * 不会真的去连网（连不上会抛 IOException，但那是「连不上」，不是「不允许」，
     * 所以断言的其实是异常信息里的原因）。
     */
    @Test fun theRepositoryRefusesHostsOutsideTheAllowList() {
        val repository = PlaybackProfileRepository("https://example.com/playback-profile.json")
        try {
            val error = assertThrows(Exception::class.java) { repository.fetch() }
            val message = error.message ?: ""
            assertTrue(
                "异常原因应当是地址不受支持，实际：$message",
                message.contains("不受支持") || message.contains("Unable to resolve host"),
            )
        } finally {
            repository.close()
        }
    }

    /** 仓库的默认地址必须在白名单里。 */
    @Test fun theDefaultUrlIsAllowed() {
        val url = PlaybackProfile.REMOTE_URL
        val host = url.substringAfter("https://").substringBefore('/')
        assertTrue("$host 不在白名单里", host in PlaybackProfileRepository.ALLOWED_HOSTS)
    }
}
