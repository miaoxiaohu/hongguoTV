# Kotlin 0.9.94：站方又收紧了签名校验，换上真的 551 Argus / Ladon

<!-- notes -->
站方 2026-11-11 收紧了播放取址的签名校验 —— 原来那套只是把时间戳做 base64 的占位签名，
现在一律被软拦截成 HTTP 200 + 0 字节，电视上表现为「播放失败，已自动重试 3 次」。
这一版把真的 551 Argus / Ladon 签名移植进电视端（纯 Kotlin），取址一次成功。
真人口播与漫剧各 8 集实测全部播通。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9094。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.93 及更早版本并保留本机收藏、观看记录和设置。

## 这一版改了什么

### 一、根因：占位签名被站方软拦截了

上一版（0.9.93）把取址改走了官方 App 现在用的那条 GET 路，接口本身是通的，
但签名头是**占位的** —— core 里的 `Signer` 只做了这些：

```
x-ladon = base64(be32(ts))
x-argus = base64(le32(ts))
```

也就是说，签名头里根本没有跟设备、跟 query 绑定的内容，就是时间戳换个 base64。
站方在 2026-11-11 收紧了校验之后，这种签名**一律被软拦截**：

- HTTP 状态码 **200**，不是 403；
- 响应体 **0 字节**，不是错误码；
- 响应头 `server: volc-dcdn` —— 是站方 CDN 层的软拦截，不是超时、不是限流、不是网络问题。

所以现象是「什么都没报，就是没内容」，很容易被误判成取址接口又挂了。
对着网络层排查（换节点、改路由、清代理）全是白费功夫 —— 0.9.93 已经实证过一回，
这一版不再重复那条路，直接从证据下手。

**取证方式**：同一份请求，签名头换成上游的真签名，站方就正常回内容；
把 query 里一个字符的百分号编码改掉（多编一个 `*`），站方又回 0 字节。
两个对照一起说明：**站方在按 query 原文 + body 原文算签名**，占位签名和「多编一个字符」都被判为无效。

### 二、真签名：551 Argus / Ladon，纯 Kotlin 移植

新增 `core/src/main/kotlin/com/hongguotv/core/Argus.kt`，实现站方客户端公开的那套协议常量与算法：

- **Argus**：protobuf 拼装 → Simon 分组密码（128 位密钥、64 位分组、72 轮）→ AES-CBC。
- **Ladon**：34 轮 ARX。
- 依赖的 SM3 摘要、base64、hex 等助手 core 里本来就有，没新增任何第三方依赖。

算法与常量对照的是上游 JS 实现（sunnysky123/hongguo-web 的 `playback-sign.js`），
Kotlin 代码移植自 N3urda/hongguoTV 的同名文件（它本身又是 ch6vip/fqapp 的 rust 实现移植）。
三者都是 GPL-3.0-only，本仓库同样是 GPL-3.0，已按许可证要求保留署名并记入 `kotlin-tv/NOTICE.md`。

**签名头只有四个**：`x-ss-req-ticket` / `x-khronos` / `x-ladon` / `x-argus`。
这条 GET 没有正文，所以不带 `Content-Type`、不带 gzip、不带 `x-ss-stub`；
另外补上站方 App 那一档的 tab 头（`Accept`、`sdk-version=2`、`x-xs-from-web=0`、
`passport-sdk-version=50561`、`x-ss-dp=8662`、`x-tt-store-region=cn`、`x-tt-request-tag=t=0;n=0` 等）。

### 三、query 的三个细节，少一个就又是 0 字节

**1. 参数顺序要跟 App 一字不差。** 签名是按 query 原文算的，顺序变了就是另一份签名。
现在的顺序是「设备字段 → `device_id`/`iid`/`cdid` → 业务参数 → `ts` → `_rticket`」。

**2. 百分号编码要照抄 App 的规则。** App 用的是 `encodeURIComponent` 的语义，外加把 `!'()` 显式转义 ——
跟 `encodeURIComponent` 只差一处：**`*` 不转义**。
老代码把「非 unreserved 一律转义」，于是 `resolution=1280*2772` 里的星号变成了 `%2A`，
多编一个字符，站方算出来的摘要就对不上。
这一条是**契约的真实变更**，不是为了让测试变绿：对应的单测断言已按上游行为同步更新。

**3. 身份要带 `cdid`。** 取址的 query 上有 `device_id`、`iid`、`cdid` 三个字段，
而 `cdid` 必须和注册 body 里那个是同一个值 —— 所以 `PlaybackIdentity` 从两个字段扩成三个，
注册时生成一次、随身份一起带出去。

另外，取址这条 GET 现在用**独立的一份客户端版本资料**（`73932` / `7.3.9.32`）和独立的 UA，
跟注册 body 对得上；老 POST 门用的那份（`71332` / `7.1.3.32`）原样保留，两条路不混用。

## 落点

- core `Argus.kt`（新增）：真签名实现。
- core `ContentRepository.kt`：`tabQuery` 拼一次 query（两个域名共用同一份 query 与签名）、
  `tabVideoModelText` 改用真签名头 + tab 头；新增 `VIDEO_TAB_DEVICE_PARAMS` / `PLAYBACK_TAB_UA`；
  `playbackEncode` 的 `*` 不再转义。
- core `PlaybackDevice.kt`：`PlaybackIdentity` 增加 `cdid`。
- core `ContentRepositoryTest.kt`：签名头断言由六个占位头改为真签名的四个，
  补 `cdid=` / `version_name=7.3.9.32` 断言，编码断言按新契约更新。

## 验证

**探针（`kotlin-tv/core`，跑的是电视 App 本体的解析链路）**：

- `--play=real-drama`：成功 8 / 失败 0，全部 1080P、实读 1 MB 媒体字节；
- `--play=comic-drama`：成功 8 / 失败 0，全部 1080P、实读 1 MB 媒体字节。

**媒体编码核对**：对三个真实 `streamUrl` 各 Range 抓前 512 KB，扫 moov —— 三条全部
`moov=True hvc1=True bvc2=False avc1=False mp4a=True`（HEVC + AAC，电视能解）。

**单测**：`:core:test` 全绿（396 项，0 失败）。
**lint**：`:app:lint` BUILD SUCCESSFUL。

## 版本号说明

- versionCode 由 CI 从 tag 算（`major*1000000 + minor*1000 + patch`）：tag `kotlin-v0.9.94` → 9094。
- 上一版已发布的是 `kotlin-v0.9.93`（9093），新 tag 必须严格新于所有已发布的 kotlin-v 版本。

## 已知限制

- **站方接口会变**：0.9.93 是 POST 族被关掉、0.9.94 是签名校验收紧。
  探针每天 03:00 跑一次，异常会自动回报。
- **真签名也是客户端公开协议**：算法常量取自客户端实现，站方可以随时再改校验口径。
- **公开页只有 10 秒试看**：它不是可用的兜底，只是最后一条路。
