# Kotlin 0.9.98：站方换取址协议，v5 取址门上线

<!-- notes -->
一、站方在 2026-11-16 下午换了取址协议：老的 GET /reading/bookapi/video_tab/... 与
POST /novel/player/multi_video_model/v1/ 一律回 HTTP 200 + 0 字节（软拦截，什么错误码都没有），
所以整部 App 取不到地址 —— 表现就是「又放不了了」。
这一版把官方 App 现在真正在用的 v5 取址门（POST /novel/player/multi_video_model/player/v1，
body 是 gzip 过的 JSON，头里只带 x-khronos / x-helios / x-medusa 三个 v5 签名头）接了进来，
并放在取址门的第一位；老的两道门保留成兜底，站方再切回去也还能放。
二、换版本号是**没有用的**：拿 73932 与 73970 两个版本号实测过，旧接口照样 0 字节。
真正的原因只有一个 —— 接口换了。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9098。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.97 及更早版本并保留本机收藏、观看记录和设置。

## 现象

用户报的是「**又放不了了**」。剧集列表、封面、详情都正常，一点播放就取不到地址。

## 根因：站方在 2026-11-16 下午换了取址协议

- 老的 GET 门（`/reading/bookapi/video_tab/multi_video_model/v1/`）与老的 POST 门
  （`/novel/player/multi_video_model/v1/`）**一律回 HTTP 200 + 0 字节**：
  没有错误码、没有错误消息，就是一个空答复。这是站方的软拦截。
- 注册接口还正常发身份，所以「身份拿到了，取址拿不到地址」。
- **版本号不是原因**：拿 73932 与 73970 两个版本号各试一轮，旧接口照样 0 字节。
  单纯「换个号」救不了 —— 上游（`N3urda/hongguoTV`）也是这么判的。

现在还在答的是官方 App 自己用的 v5 那条：

    POST https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1
    query: aid / app_name / version_code / channel / device_type / os_version / iid / cdid / device_id / _rticket
    body : gzip 过的 JSON（mixed_video_id_map.1004 = 这一集的 id）
    头   : Content-Encoding: gzip、x-ss-stub（gzip 字节的 MD5 大写）、
           以及 x-khronos / x-helios / x-medusa 三个 v5 签名头

## 这一版怎么改

- 新增 `V5Constants.kt`：v5 的 magic、许可证号、签名 key 与 v5 专用 sbox。
- `AesV3.kt`：加 v5 分支（专用 sbox、con/order、initial、wire/compact 位序），
  非 v5 那条老路的逻辑一个字没动。
- `Signer.kt`：加 `branch()` 的 v5 映射、`hashF13()` 的 v5 变体、`medusaV5()`、
  `helios()` 的许可证号切换，以及新的 `signPlayback()`（v5 只保留三个签名头）。
- 新增 `V5Playback.kt`：v5 取址请求 + 回答解析。
- `ContentRepository.kt`：新增 `v5Stream()`，并把 **v5 门放在取址门的第一位**；
  tab 门与老的三道门降级成兜底（站方再切回去也还能放）。

## 落点

- `core/src/main/kotlin/com/hongguotv/core/V5Constants.kt`（新增）
- `core/src/main/kotlin/com/hongguotv/core/V5Playback.kt`（新增）
- `core/src/main/kotlin/com/hongguotv/core/AesV3.kt`、`Signer.kt`：v5 分支
- `core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt`：`v5Stream()` 与门序
- `core/src/test/kotlin/com/hongguotv/core/ContentRepositoryTest.kt`：
  新增 `theV5DoorIsAskedFirstAndItsModelIsPlayed`（v5 门第一个被问，答得出来时老门一个都不问）；
  其余 mock 补上 v5 分支（站方空答），老门的行为照旧被测。

## 验证

- **单测**：`:core:test` 全绿；`:app:testDebugUnitTest` 全绿。
- **lint**：`:app:lintRelease` BUILD SUCCESSFUL。
- **探针**：real-drama 8 集全部成功、失败 0；comic-drama 8 集全部成功、失败 0；
  每集实读 1048576 字节媒体。
- **实机**：XGIMI 投影（192.168.1.26）上装 0.9.98 实测播放正常。

## 版本号说明

- versionCode 由 CI 从 tag 算（`major*1000000 + minor*1000 + patch`）：tag `kotlin-v0.9.98` → 9098。
- 上一版已发布的是 `kotlin-v0.9.97`（9097），新 tag 必须严格新于所有已发布的 kotlin-v 版本。

## 已知限制

- **v5 取址门现在只有我们这一条实现**：它是照上游 0.16.5 的实现移植的，
  站方再改协议时同样要重新对。
- **老的 tab 门与老 POST 门仍留在代码里当兜底**：它们现在一律 0 字节，
  真到站方切回去那天不用再改代码。
