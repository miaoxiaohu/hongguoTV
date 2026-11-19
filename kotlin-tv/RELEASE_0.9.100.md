# Kotlin 0.9.100：v5 取址门补上「空答换号重试」，device_level 改回 1

<!-- notes -->
一、站方偶尔回一次空答（HTTP 200 + 0 字节）时，这一集就直接被判死，
降级链一路退到公开页，用户只看到 10 秒试看片段就报「播放失败」。
这一版让 v5 取址门跟老门一样：同一个身份空答就再问一次，整轮都空才换一个身份。
二、同时把 v5 请求里的 device_level 从 3 改回 1 —— 站方按这个值决定发什么编码，
3 只给电视解不了的 ByteVC2，0 和 1 才给真 HEVC。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9100。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 0.9.99 及更早版本并保留本机收藏、观看记录和设置。

## 现象

2026-11-20 03:00 的每日检测报异常：real-drama 有 2 集、comic-drama 有 1 集失败，
失败形态都是 `SampleClipException: 只取到 10 秒试看片段，不是完整剧集`。

排查分两层：**第一层是检测本身跑的不是当前代码**，**第二层才是 v5 取址门自己的缺陷**。

## 第一层：检测跑的是过期探针，报的是假故障

`daily-source-check.ps1` 直接跑 `kotlin-tv\core\build\install\core\bin\core.bat`，
**自己不重新编译**。而那天磁盘上的探针二进制停在 **2026-11-16 20:56** ——
比 v5 取址协议接入（`V5Playback.kt` 20:59、`Signer.kt` 21:01）还早，
里面甚至还留着早先撤掉的 `[DIAG-TEMP]` 埋点。

也就是说，那一趟检测跑的是**站方早已关停的旧取址接口**，
报出来的「取不到地址」是过时结论，跟当前代码无关 —— 证据就是它输出了 `[DIAG-TEMP]` 行，
而当前源码里早就没有这段埋点了。

**修法**（在检测脚本里，不在 APK 里）：每趟检测前先增量编译一次探针，
编译失败只警告、仍用现有探针跑（不因为编译问题直接判失败）。
重编译之后，real-drama 立刻 8/8 全通过 —— 0.9.99 的代码本来就是好的。

## 第二层：v5 门一次空答就判失败（真缺陷）

重编译后仍剩 comic-drama 2 集失败。临时埋点（量完已撤）把 v5 门的原始回答打出来：

- 8 集里 7 集正常：HTTP 200、响应体约 23 KB、`code=0`、`video_model` 里带可播档位；
- 失败的集：HTTP 200、**响应体 0 字节**。

而下一趟检测里，失败的那一集**又正常了**（同样的集 v5 照样答出 23 KB）——
说明这不是「这一集没有片源」，是**偶发的一次空答**。

根因：v5 门（`ContentRepository.v5Stream()`）照搬 tab 门的时候只搬了一半 ——
tab 门有「空答就换号」的整套机制（`PLAYBACK_TAB_ATTEMPTS_PER_IDENTITY`、
`PLAYBACK_TAB_MAX_IDENTITIES`、`dropPlaybackIdentity()`），
而 v5 门是**一次空答就抛 `EmptySignedAnswerException`**。
于是站方偶尔的一次空答直接把这集判死，降级链又一路退到公开页（只有 10 秒试看）。

**修法**：v5 门补上与 tab 门相同的机制 —— 同一个身份最多问两次（间隔 500 ms），
整轮都空答就丢掉这个身份、重新注册一个，最多换 8 个身份；
注册被拒仍然直接上抛走长退避，不被后面的空答盖成「空内容」。

## 第三层：v5 请求里的 device_level 写死成了 3

`V5Playback` 的请求体里 `biz_param.device_level` 照抄上游写死成 `3`，
而项目硬约定是 **`PLAYBACK_DEVICE_LEVEL = 1`**（3 会拿到 ByteVC2，电视解不了；
0 和 1 给的是真 HEVC）。这与老 POST 门、tab 门都不一致。

这一版改回 `ContentRepository.PLAYBACK_DEVICE_LEVEL`，并补了单测锁住它
（读 gzip 后的请求体，断言里面是 `"device_level":1`）。

## 验证

- **探针**：real-drama 8 集、comic-drama 8 集，全部成功、失败 0，每集实读 1048576 字节媒体字节。
- **单测**：`:core:test` 全绿（新增 3 个用例：
  `anEmptyV5AnswerIsFollowedByAnotherAskWithTheSameIdentity`、
  `anIdentityThatKeepsAnsweringEmptyOnTheV5DoorIsReplaced`、
  `theV5RequestAsksForTheLevelTheTelevisionCanDecode`）；
  `:app:testDebugUnitTest`、`:app:lintRelease` 全绿。
- **诊断代码已撤干净**：`git diff` 里没有任何 `[DIAG-TEMP]` 残留，探针二进制里也扫不到该关键字。

## 没动的东西

弹幕几何/密度/透明度、播放页手势与常量、焦点落点、主题文案、取址签名本身
（真签名、query 编码、`cdid`）、身份跨集复用、地址缓存 TTL 2 小时 / 上限 120 条，
以及 `server/` 那套纯 JS 签名链路 —— 全部原样保留。
