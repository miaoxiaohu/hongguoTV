package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdatesTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()
    private fun json() = JSONObject().put("schema", 1).put("packageName", "com.hongguotv.nativeapp")
        .put("versionCode", 10001).put("versionName", "0.9.0+1").put("minSdk", 26).put("tag", "kotlin-v0.9.0-build.1")
        .put("apk", JSONObject().put("name", "hongguotv-kotlin-0.9.0-build.1-android8.apk").put("size", bytes.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
    private fun update() = AppUpdate.parse(json().toString())
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("update-test").toFile()
        try { block(File(dir, "update.apk")) } finally { dir.deleteRecursively() }
    }
    @Test fun onlyHigherCompatibleVersionsAreUpdates() {
        val update = update()
        assertTrue(update.newerThan(9, 26)); assertFalse(update.newerThan(10001, 36)); assertFalse(update.newerThan(10002, 36)); assertFalse(update.newerThan(9, 25))
    }
    @Test fun downloadUrlUsesOnlyFixedRepositoryAndValidatedNames() {
        assertEquals("https://github.com/miaoxiaohu/hongguoTV/releases/download/kotlin-v0.9.0-build.1/hongguotv-kotlin-0.9.0-build.1-android8.apk", update().downloadUrl)
        for (value in listOf("../../bad.apk", "evil.apk", "https://example.com/evil.apk", "hongguotv-kotlin-1/2-android8.apk")) {
            val o = json(); o.getJSONObject("apk").put("name", value)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("tag", "../../other").toString()) }
    }
    /**
     * Every invariant a shipped manifest must satisfy, so a TV never shows 「更新信息格式不正确」.
     *
     * [sourceCommit] is present only in the manifests written by scripts/publish-tv-update.py, so it
     * is included on purpose: `AppUpdate.parse` reads fields by name and never enumerates keys, and
     * this is what pins that down. Without it, adding a field to the publisher could silently break
     * every update on every TV.
     */
    private fun assertValidManifest(text: String, label: String) {
        val update = AppUpdate.parse(text)
        assertTrue("$label 必须比任何旧版新", update.newerThan(1L, 26))
        // 不写死具体 tag：manifest 每次发版都会重新生成，写死会让上一版的值一直挂到下次发版。
        assertTrue("$label 的 tag 应指向 kotlin-v 前缀：${update.tag}", update.tag.matches(Regex("kotlin-v[0-9][0-9A-Za-z.+-]*")))
        assertTrue(update.downloadUrl.startsWith("${AppUpdate.RELEASES}/download/${update.tag}/"))
        assertTrue(update.sha256.matches(Regex("[0-9a-f]{64}")))
        // size 与 notes 必须有值：前者用于下载前校验长度，后者显示在确认弹窗里。
        assertTrue("$label 的 size 必须大于 0", update.size > 0)
        assertTrue("$label 的 notes 不能为空，否则更新弹窗没有说明", update.notes.isNotBlank())
    }

    /**
     * The exact shape both generators emit — server/make-update-json.mjs and
     * scripts/publish-tv-update.py — checked in CI.
     *
     * This used to read server/update.json, which .gitignore excludes on purpose because it is a
     * generated Docker artifact. That made the test pass on a developer machine and fail on every
     * clean checkout, including every pull request, for a file nobody commits. The fixture pins the
     * contract; the real artifact is still validated below whenever it happens to exist.
     */
    @Test fun shippedManifestShapePassesTheRealValidator() {
        val fixture = """{"schema":1,"packageName":"com.hongguotv.nativeapp","versionCode":9014,"versionName":"0.9.14",
            "minSdk":26,"tag":"kotlin-v0.9.14","notes":"首页片库扩为三个分类。","sourceCommit":"0000000000000000000000000000000000000000",
            "apk":{"name":"hongguotv-kotlin-0.9.14-android8.apk","size":2685970,
            "sha256":"d5e52fe87422afca0257ab313fe18f39b770d7de22d1d13ca268630ef35b56d0"}}"""
        assertValidManifest(fixture, "生成器契约样例")
    }

    @Test fun generatedUpdateJsonPassesTheRealValidatorWhenPresent() {
        // 发布时 server/make-update-json.mjs 生成的文件必须能通过 AppUpdate.parse。
        // 测试的工作目录是 kotlin-tv/core/，所以 ../.. 回到仓库根再取 server/。
        // 该文件被 .gitignore 排除（它是生成物），CI 上不存在，所以这里用 Assume 跳过而不是让整个构建红。
        // 路径可用 -Dhongguotv.updateJson=... 覆盖，这样「文件不存在」这条 CI 路径本身也能被验证，
        // 而不是只能假设 Assume 会跳过。
        val file = File(System.getProperty("hongguotv.updateJson") ?: "../../server/update.json")
        assumeTrue("本机没有 ${file.path}（cwd=${File(".").absolutePath}），跳过对真实生成物的校验", file.isFile)
        assertValidManifest(file.readText(), file.path)
    }
    @Test fun theRepositoryIsThisProjectNotTheUnusedUpstream() {
        // 上游 N3urda/hongguoTV-updates 从未发布过任何 release，指向它会让检查更新永远 404。
        assertEquals("miaoxiaohu/hongguoTV", AppUpdate.REPOSITORY)
        assertTrue(AppUpdate.RELEASES.startsWith("https://github.com/miaoxiaohu/hongguoTV/"))
        assertEquals("https://github.com/miaoxiaohu/hongguoTV/releases/latest/download/update.json",
            "${AppUpdate.RELEASES}/latest/download/update.json")
    }
    @Test fun rejectOtherPackageAndInvalidSchema() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("packageName", "other.app").toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("schema", 2).toString()) }
    }
    @Test fun enforceVersionSizeAndChecksumBounds() {
        for (code in listOf(0L, -1L, Int.MAX_VALUE.toLong()+1)) assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("versionCode", code).toString()) }
        for (size in listOf(0L, -1L, AppUpdate.MAX_APK_BYTES+1)) {
            val o = json(); o.getJSONObject("apk").put("size", size)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        val o = json(); o.getJSONObject("apk").put("sha256", "invalid")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
    }
    @Test fun verifiedDownloadAtomicallyReplacesOldFile() = withFile { file ->
        file.writeText("previous"); val progress = mutableListOf<Int>()
        UpdateFiles.receive(ByteArrayInputStream(bytes), file, update(), progress::add)
        assertTrue(UpdateFiles.verify(file, update())); assertEquals(100, progress.last()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun truncatedDownloadPreservesPreviousFile() = withFile { file ->
        file.writeText("previous")
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(4)), file, update()) }
        assertEquals("previous", file.readText()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun oversizedDownloadIsRejectedAndRemoved() = withFile { file ->
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes+byteArrayOf(1)), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun checksumMismatchCannotBecomeInstallableFile() = withFile { file ->
        val corrupt = bytes.copyOf(); corrupt[0] = 0
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(corrupt), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun interruptedDownloadCleansUpPartialFile() = withFile { file ->
        Thread.currentThread().interrupt()
        try { assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes), file, update()) } }
        finally { Thread.interrupted() }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun cacheMustStillMatchExpectedContent() = withFile { file ->
        file.writeBytes(bytes); assertTrue(UpdateFiles.verify(file, update()))
        file.writeBytes(ByteArray(bytes.size)); assertFalse(UpdateFiles.verify(file, update()))
    }
}
