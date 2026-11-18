# Kotlin 0.9.99：修好小米电视上的在线升级（App 自己把签名读空了）

<!-- notes -->
一、小米电视上点「检查更新」会提示「新版包名、版本或签名不匹配，已阻止安装」，于是永远升不了级。
根因不在包上：这台机器（MiTV-ASTP0 / Android 9）的系统接口读归档 APK 时读不出签名，
而 App 拿这个空结果跟已装包的签名硬比，就把自己拦下了 —— 其实包名、版本号、minSdk、证书全都对得上。
二、这一版改成「读不到就不算不匹配」：包名、版本号、版本名仍逐项严格比对；
签名只有两边都读到才比，读不到就放行给系统安装器（签名真不一致，系统自己会拒绝安装）。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9099。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 0.9.98 及更早版本并保留本机收藏、观看记录和设置。

## 现象

小米电视（MiTV-ASTP0，Android 9）上，App 内「版本与更新」点「检查更新」，
下到新版后被自己拦下，提示「新版包名、版本或签名不匹配，已阻止安装」—— 也就是「新版不一致，没法在线升级」。

## 根因：读归档 APK 读不出签名，被当成签名不一致

2026-11-18 在这台电视上实测（写了一个一次性探针 APK 打印 PackageManager 的返回值）：

- `getPackageArchiveInfo(apkPath, GET_SIGNING_CERTIFICATES)` 读下载下来的归档 APK：
  **包名、versionCode 9098、versionName 0.9.98、minSdk 26 都对，但 `signingInfo` 是 `null`**。
- 同一个 ROM 上 `getPackageInfo("com.hongguotv.nativeapp", ...)` 读已安装包：`signingInfo` 正常（Signature@652117d）。

旧版 `AppUpdater.validateApk` 里是这样的：

```kotlin
if (candidate.packageName != activity.packageName || version != update.versionCode || candidate.versionName != update.versionName || version <= BuildConfig.VERSION_CODE ||
    candidate.applicationInfo?.minSdkVersion != update.minSdk || signers(installed).isEmpty() || signers(installed) != signers(candidate))
    throw IOException("新版包名、版本或签名不匹配，已阻止安装")
```

`signers(candidate)` 拿的是空的签名集合，`signers(installed)` 是 `{652117d}`，
**两者永远不相等** —— 于是每一台这类电视上的每一次在线升级都被自己拦下。
（顺带一提：`candidate.applicationInfo?.minSdkVersion` 在读不到时是 null，与 update.minSdk 也不等，同样会触发拦截。）

**包本身完全没问题**：把同一个 APK 用 `pm install -r` 直接装到这台电视上，**Success**，
证书指纹（SHA-256 `4f4cf502…`）与已装版本完全一致。也就是说拦下它的只有 App 自己那一行判断。

## 这一版怎么改

- 新增 `core/ApkCompatibility.kt`：把「能不能装」的判定从 app 模块挪到 core（纯 JVM，可单测）。
  - `ApkFacts`：包名、versionCode、versionName、minSdk、签名集合，读不到的字段留空。
  - `ApkCompatibility.blocker()`：包名、版本号、版本名、minSdk 严格比对；
    **签名只有两边都真的读到才比较**，任一边读不到就放行，交给系统安装器判断。
- `AppUpdater.validateApk()` 改为调用它，并且把拦下时的原因写清楚（包名/版本号/版本名/minSdk/签名各说各的），
  不再是一句笼统的「不匹配」。
- 判定用的「已装版本号」改成读系统里的真实 `longVersionCode`，不再依赖编译期常量 `BuildConfig.VERSION_CODE`。

## 为什么放行签名比对是安全的

签名是否一致最终由系统安装器把关：签名不同，`PackageInstaller` 直接返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
装不上去。客户端这一层提前拦截的价值只是「别让用户点完安装才失败」，
它没有必要替系统做一个系统自己做不了的判断 —— 而在读不到签名时，它做的恰恰是误判。

## 验证

- `:core:test`（含新增 `ApkCompatibilityTest` 8 个用例：归档读不到签名/读不到 minSdk 时放行，
  签名不同、包名不同、版本号与更新说明不同、版本不比已装新、minSdk 与更新说明不同都要拦住）；
  `:app:testDebugUnitTest`、`:app:lintRelease`、`:app:assembleRelease` 全绿。
- 实机（小米电视 192.168.1.25）：`pm install -r` 装 0.9.98 成功，证明包与签名都没问题；
  根因由一次性探针 APK 直接读出（归档签名为 null）。

