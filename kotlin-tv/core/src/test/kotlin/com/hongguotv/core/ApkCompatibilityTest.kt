package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 在线升级的「能不能装」判定。
 *
 * 关键回归：小米电视（Android 9）上归档 APK 读不到签名（signingInfo 为 null），
 * 旧逻辑把它当成签名不一致，于是每一次在线升级都被自己拦下。
 */
class ApkCompatibilityTest {
    private fun update() = AppUpdate.parse(
        """{"schema":1,"packageName":"com.hongguotv.nativeapp","versionCode":9099,"versionName":"0.9.99",
            "minSdk":26,"tag":"kotlin-v0.9.99","notes":"修好在线升级被自己拦下的问题。",
            "apk":{"name":"hongguotv-kotlin-0.9.99-android8.apk","size":2753962,
            "sha256":"11aebe87716afef268ab7c64700546e476f9daa8f5b9e0addaa987641bbb3763"}}"""
    )
    private val installed = ApkFacts(
        packageName = "com.hongguotv.nativeapp",
        versionCode = 9098,
        versionName = "0.9.98",
        minSdk = 26,
        signers = setOf("652117d"),
    )
    private val candidate = ApkFacts(
        packageName = "com.hongguotv.nativeapp",
        versionCode = 9099,
        versionName = "0.9.99",
        minSdk = 26,
        signers = setOf("652117d"),
    )

    @Test fun ordinaryUpgradeIsAllowed() {
        assertNull(ApkCompatibility.blocker(candidate, installed, update()))
    }

    /** 小米电视实况：归档 APK 的 signingInfo 是 null，签名集合为空。 */
    @Test fun archiveWithoutSignersIsNotTreatedAsMismatch() {
        val blind = candidate.copy(signers = emptySet())
        assertNull("读不到归档签名时必须放行", ApkCompatibility.blocker(blind, installed, update()))
    }

    @Test fun archiveWithoutMinSdkIsNotTreatedAsMismatch() {
        val blind = candidate.copy(minSdk = null)
        assertNull(ApkCompatibility.blocker(blind, installed, update()))
    }

    /** 两边都读到签名且不同，才是真不一致。 */
    @Test fun differentSignersAreRejected() {
        val other = candidate.copy(signers = setOf("deadbee"))
        assertNotNull(ApkCompatibility.blocker(other, installed, update()))
    }

    @Test fun differentPackageIsRejected() {
        val other = candidate.copy(packageName = "com.other.app")
        assertNotNull(ApkCompatibility.blocker(other, installed, update()))
    }

    @Test fun versionNotMatchingTheManifestIsRejected() {
        val other = candidate.copy(versionCode = 9000, versionName = "0.9.0")
        assertNotNull(ApkCompatibility.blocker(other, installed, update()))
    }

    @Test fun notNewerThanInstalledIsRejected() {
        val other = candidate.copy(versionCode = 9098, versionName = "0.9.98")
        assertNotNull(ApkCompatibility.blocker(other, installed, update()))
    }

    @Test fun minSdkNotMatchingTheManifestIsRejected() {
        val other = candidate.copy(minSdk = 28)
        val reason = ApkCompatibility.blocker(other, installed, update())
        assertNotNull(reason)
        assertTrue("报错文案应填入实际数值：$reason", reason!!.contains("28") && reason.contains("26"))
        assertFalse("报错文案不应出现未展开的模板字面量：$reason", reason.contains("\${"))
    }
}
