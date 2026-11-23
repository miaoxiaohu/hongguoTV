# Kotlin 0.9.102：tab 取址门改用 v5 签名（站方 11-23 又改了签名要求）

<!-- notes -->
站方今天又改了取址门的签名要求：video_tab 这条 GET 现在只认 v5 签名
（只带 x-khronos / x-helios / x-medusa 三个头），旧的 551 Argus 与 v4 签名一律回 0 字节。
这一版把 tab 门换成 v5 签名，当天 16 集全部恢复（每集实读 1MB 媒体、1080P）。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9102。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 0.9.101 及更早版本并保留本机收藏、观看记录和设置。

## 现象

2026-11-23 14:00 的每日检测报异常：real-drama 8 集、comic-drama 8 集**全部失败**，
失败形态都是 `SampleClipException: 只取到 10 秒试看片段，不是完整剧集`
（取址门空答，降级链一路退到公开页）。

同一份代码当天 **03:01 那趟还全绿**（16/16 通过，每集实读 1 MB 媒体），
14:02 就全红 —— 又是一次「白天收紧」。

## 根因：站方改了 video_tab 这条 GET 的签名要求

这次不是「整族门被关」，而是**签名要求变了**。临时诊断（量完已撤）把三道门的原始回答打出来：

| 门 | 回答 |
|---|---|
| v5 POST `/novel/player/multi_video_model/player/v1` | HTTP 200 + **0 字节** |
| tab GET `/reading/bookapi/video_tab/multi_video_model/v1/`（v4 signPlayback 形态） | HTTP 200 + **0 字节** |
| tab GET（同一条路，我们自己那套 551 Argus 形态） | HTTP 200 + **0 字节** |
| tab GET（**v5 签名**形态，本版新增） | HTTP 200 + **21684 字节**，`code=0`，模型完整 |

也就是说：**站方现在要求这条 GET 也用 v5 的签名**。具体要求（出处：上游
N3urda/hongguoTV 的 0.16.12，commit `1782dda4`，11-22 发布；他们连着发了 0.16.11/0.16.12/0.16.13
三个版本修「空响应」）：

- 头里**只带三个 v5 签名头**：`x-khronos` / `x-helios` / `x-medusa`（没有 x-argus、x-gorgon）；
- medusa 吃的是 **MD5(空字节)**，不是 16 个零字节 —— 空字节只是**签名输入**，这条 GET 本身没有正文，
  所以 `x-ss-stub` / `Content-Type` / `Content-Encoding` 三个头都不能留在请求上；
- query 里的客户端资料换成 v5 那一档：`version_code=74332` / `version_name=7.4.3.32` /
  `channel=vivo_8662_64` / `device_type=PGT-AN10` / `os_version=12`；
- UA 用 tab 那一档：`com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; ...)`；
- 身份三字段（device_id / iid / cdid）照旧在 query 上。

## 改了什么

1. **新增 `V5Playback.tabModel()`**：用 v5 签名造这条 GET（空字节摘要、只三个签名头、
   去掉 POST 的三个头、query 用 v5 那套设备字段、UA 用 tab 那一档）。
2. **`ContentRepository.tabVideoModelOnce()` 改成一次发三套形态**，挨个试：
   ① v5 签名（现行，2026-11-23 实测答得出来）
   ② 上游 0.16.10 的 v4 `signPlayback` 形态
   ③ 我们自己那套 551 Argus 形态。
   后两套留着当兜底 —— 站方哪天换回去还能接住。
3. 单测：新增 `theTabDoorAsksWithTheV5Signature` 锁住形态（三个 v5 头在、v3/v4 头不在、
   POST 的三个头不在、query 用 v5 那套设备字段）；按新协议更新了三个既有断言。

## 验证

- **单集实测**：v5 形态答出 21684 字节的完整模型（`code=0`），播放 1080P 成功；
  同一时刻另外两套形态仍然回 0 字节 —— 所以这次能定位到「签名头」这一层。
- **探针**：real-drama 8 集、comic-drama 8 集全部成功、失败 0，每集实读 1048576 字节媒体字节、
  画质 1080P（2026-11-23 14:17 的检测）。
- **单测**：`:core:test` 423 项全绿；`:app:testDebugUnitTest`、`:app:lintRelease` 全绿。
- **诊断代码已撤干净**：探针 jar 里扫不到 `DIAG-TEMP` / `TabTrace`。

## 没动的东西

弹幕几何/密度/透明度、播放页手势与常量、焦点落点、主题文案、v5 POST 门本身、
身份跨集复用、换号的冷却节奏（11-20 定的：一集最多 2 个身份、两次换号至少隔 30 秒）、
地址缓存 TTL 2 小时 / 上限 120 条，以及 `server/` 那套纯 JS 签名链路 —— 全部原样保留。
