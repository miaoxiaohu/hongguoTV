# Kotlin 0.9.62：手机输入不再被「来源不匹配」拦下

<!-- notes -->
手机扫码输入剧名，点「发送到电视」以前只换来一句「来源不匹配」，剧名根本没送到电视。原因是页面自己回了 Referrer-Policy: no-referrer，Chrome 于是把 Origin 一起抹成 null，服务器拿这个 null 跟自己的地址比对，对不上就拒收。改成 same-origin，服务器也不再把 null 当成跨站来源。
<!-- /notes -->
最低 Android 8.0 / API 26，versionCode 9062。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.61 并保留本机收藏、观看记录和设置。

## 这一版改了什么

- **手机输入页面的 Referrer-Policy 从 `no-referrer` 改成 `same-origin`**。`no-referrer` 会让 Chromium 在表单 POST 上把 **Origin 一起抹成 `null`**（Referer 本来就没有了，Origin 也跟着没了）；`same-origin` 只对跨站请求隐藏 Referer，同站请求仍然带着正常来源，Origin 也就还是那个能对上的地址。页面只有一次同站提交、没有第三方资源，所以这一档既不泄露地址里的令牌，也不影响来源判定。
- **服务器不再把 `null` 当成跨站来源**。一个说不清自己来源的浏览器（`Origin: null`）会被放行：真正拦跨站的是 URL 里那个一次性令牌，没有它谁也 POST 不进来；**说得清、而且说的不是自己**的来源仍然照旧拒绝，另外浏览器明说「这是跨站请求」（`Sec-Fetch-Site: cross-site`）也拒绝。
- **备份与恢复那个二维码没动**。它走的是 `fetch()` 而不是表单提交，实测 `no-referrer` 对 `fetch()` 的 Origin 没有影响，原来就是好的，不乱改。

## 为什么手机上看到的是「来源不匹配」

手机把词打对了，点「发送到电视」，电视那边回了 403「来源不匹配」。服务器当时的判定只有一行：

```kotlin
if (headers["origin"] != null && headers["origin"] != "http://$host") return respond(socket, 403, "来源不匹配")
```

而手机发过来的 Origin 是 `null` —— 于是对不上，拒收。**剧名其实已经到了电视门口，是被这个判定挡在门外的。**

## 怎么量的

在手机上跑了一台**和电视那边一模一样的探针服务器**（同样的页面、同样的响应头、同样的判定），一次只改一个响应头，用手机自己的 Chrome 提交同一个表单，记下每次发过来的 Origin：

| 页面 | 响应头 | 手机发来的 Origin | 判定 |
| --- | --- | --- | --- |
| 基准 | 无额外响应头 | `http://192.168.1.11:8890` | 收 |
| 基准 + `Referrer-Policy: no-referrer` | 只多这一个头 | **`null`** | **拒** |
| 电视原样 | `no-referrer` + CSP + `nosniff` | **`null`** | **拒** |
| 改后 | `Referrer-Policy: same-origin` | `http://192.168.1.11:8890` | 收 |

**只差一个头，结论就反了** —— 是 `no-referrer` 把 Origin 抹掉的，不是 CSP、不是 `nosniff`。再把 Referrer-Policy 逐档试了一遍（`same-origin` / `origin` / `strict-origin-when-cross-origin` / `origin-when-cross-origin`），**只有 `no-referrer` 这一档会产生 `null`**，其余四档都发正常的 Origin。

## 验证记录

- `:core:test` **277 项全绿**（新增 3 项：null 来源照收、cross-site 即使没有 Origin 也拒、页面不再要求浏览器藏起来源），`:app:testDebugUnitTest` **51 项全绿**。
- `:app:lintRelease` **0 error**（32 条警告是既有的），`:app:assembleRelease` 成功。
- APK 内版本号核对：**versionCode=9062 / versionName=0.9.62**，`minSdkVersion=26`；签名证书 SHA-256 `4f4cf502b9ea2b2cccada54ab62031de1ef4dc9aecdf3addbcf4099dd853cf08`，与历次正式版**同一张证书**。
- **端到端**：把编出来的**真** `PhoneSearchServer` 起在本机（`kotlin-tv/core/build/libs/core.jar`），用**手机自己的 Chrome** 打开它给的地址、输入词、点「发送到电视」—— 服务器收到 `QUERY=EndToEndTest`，手机回执页显示「已发送到电视 / 请回到电视查看搜索结果」。
- **四种来源语义在真服务器上逐个验过**：跨站来源 `http://evil.example` → **403**；`Origin: null` + `Sec-Fetch-Site: cross-site` → **403**；`Origin: null`（Chromium 实际会发的那一档）→ **200 且剧名送达**；与页面同源 → **200 且剧名送达**。
- **应用级闭环**：替身手机（Palm PVG100）上装 0.9.62，让 **App 自己**起手机输入服务，从外面以 `Origin: null` 提交 → **200「已发送到电视」**，对话框自动关闭，搜索框里出现「末日漂流」并开始加载。

## 顺带补的一个坑

本地出包时，dex 里会同时留着**新旧两个版本号** —— Kotlin 把 `BuildConfig.VERSION_NAME` 当编译期常量内联了，增量构建只重生成了 `BuildConfig.java` 而没重编 Kotlin，manifest 是新的、dex 里的常量还是旧的。原来的 `scripts/check-apk-version.py` 要调用方自己说出「上一个版本号」才能查，这次说出的是 0.9.61，而 dex 里实际留着的是 **0.9.60**，于是**检查放行了一个坏包**（装到手机上后 App 自己报的还是 0.9.60）。

现在这个检查会**把 dex 里同 major 的所有版本号都扫出来**，多于一个就报错，不必再靠人记。实测：那个坏包现在会被拦下（`FAIL dex also carries 0.9.60 next to 0.9.62`），干净构建出来的包通过（`OK dex carries no other 0.x.y version`）。

## 已知边界

- 这一版只动了手机输入这一个二维码页面。备份与恢复那个页面照旧发 `no-referrer`，因为它走 `fetch()`，实测不受影响。
- 放行 `Origin: null` 等于把「这是不是跨站」这件事交给令牌：令牌在 URL 里、一次性、128 位随机，**不知道令牌就 POST 不进来**。要说清来源又说得不对的，照旧拒绝。
- 投影仪上没有 adb over network，读不到 logcat —— 这一版的验证是探针对照 + 真服务器端到端 + 单元测试 + 版本回读。
- 本地出包必须 `clean` 后重建（见上一节）；CI 是全新检出，不受这个坑影响。

## 发布状态

- 代码已经改好并通过测试，**发布签名**的本机包已出（`out/hongguotv-kotlin-0.9.62-android8.apk`，SHA-256 `e48319bf67cb14ec1517e0e436b4915c75e74381657f0a3b4dd939888337eb36`）。
- 这一版已经用发布签名**覆盖安装到投影仪**（`192.168.1.26`），服务回读版本 **9062 / 0.9.62**。
- **发布由 CI 完成**：tag 一推，CI 就会按本文件出包发布（版本号、签名证书、附件完整性都由 CI 校验）。本文件就是发布页正文，电视更新弹窗的短说明出自 `<!-- notes -->` 块。
