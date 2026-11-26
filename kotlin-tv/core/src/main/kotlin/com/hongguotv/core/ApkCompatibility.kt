package com.hongguotv.core

/**
 * 一个 APK（归档文件或已安装包）里跟「能不能装」有关的那些事实。
 *
 * 之所以要单独抽出来，是因为 core 是纯 JVM 模块、碰不到 android.content.pm，
 * 而取值方式在不同电视 ROM 上差别很大（见 [ApkCompatibility]）。
 */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    /** 读不到时为 null（例如某些 ROM 的归档解析不填 minSdkVersion）。 */
    val minSdk: Int?,
    /** 读不到签名时是空集合（例如小米电视上归档 APK 的 signingInfo 为 null）。 */
    val signers: Set<String>,
)

/**
 * 判断「下载下来的新版」能不能装到「已装版本」上。
 *
 * 2026-11-18 在小米电视（MiTV-ASTP0 / Android 9）上实测到的坑：
 * `PackageManager.getPackageArchiveInfo()` 读归档 APK 时 **signingInfo 返回 null**，
 * 而同一个 ROM 上 `getPackageInfo()` 读已安装包是正常的。于是旧版 AppUpdater 拿
 * 「空签名集合」跟「已装签名集合」硬比，判成不一致，把每一次在线升级都拦下来，
 * 电视上只能看到「新版包名、版本或签名不匹配，已阻止安装」——
 * 其实包名、版本号、minSdk、证书全都对得上（`pm install -r` 能直接装成功）。
 *
 * 所以这里的规则是：**读不到就不当它不匹配**。
 * 签名是否一致最终由系统安装器把关（签名不同系统自己会拒绝安装），
 * 客户端这一层只需要拦住「确实不对」的情况，不必替系统做它做不了的判断。
 */
object ApkCompatibility {
    /**
     * 返回 null 表示可以装；否则返回拦下它的一条中文原因。
     */
    fun blocker(candidate: ApkFacts, installed: ApkFacts, update: AppUpdate): String? {
        if (candidate.packageName != installed.packageName)
            return "包名不一致（${candidate.packageName} ≠ ${installed.packageName}）"
        if (candidate.versionCode != update.versionCode)
            return "版本号与更新说明不一致（${candidate.versionCode} ≠ ${update.versionCode}）"
        if (candidate.versionName != update.versionName)
            return "版本名与更新说明不一致（${candidate.versionName} ≠ ${update.versionName}）"
        if (candidate.versionCode <= installed.versionCode)
            return "不是更新的版本（${candidate.versionCode} ≤ 已装的 ${installed.versionCode}）"
        val candidateMinSdk = candidate.minSdk
        if (candidateMinSdk != null && candidateMinSdk > 0 && candidateMinSdk != update.minSdk)
            return "最低系统版本与更新说明不一致（$candidateMinSdk ≠ ${update.minSdk}）"
        // 只有两边都真的读到签名才比较；任一边读不到就放行，交给系统安装器判断。
        if (candidate.signers.isNotEmpty() && installed.signers.isNotEmpty() && candidate.signers != installed.signers)
            return "签名与已安装版本不一致"
        return null
    }
}
