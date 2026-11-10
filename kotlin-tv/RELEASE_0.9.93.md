# Kotlin 0.9.93：取址接口被站方关掉了，改走官方 App 现在用的那条路

<!-- notes -->
站方把播放地址的 POST 接口关掉了 —— 带签名的请求一律回 HTTP 200 + 0 字节，电视上表现为
「播放失败，已自动重试 3 次」。这一版改走官方 App 自己现在用的那条 GET 接口
（参数全在 query 上，模型内联在回答里），取址一次成功。真人口播与漫剧各 8 集实测全部播通。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9093。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.92 及更早版本并保留本机收藏、观看记录和设置。

## 这一版改了什么

### 一、站方关掉了播放地址的 POST 接口

**现象（投影 192.168.1.26，Android 8.0，App 0.9.91 / 9091）**：
界面上是「播放失败…已自动重试 3 次」。同一台机器上：Wi-Fi CONNECTED + VALIDATED，
ping 零丢包，目录、详情、搜索都正常 —— 只有取播放地址这一下过不去。

**取证**：把站方的取址接口逐个试了一遍：

| 接口 | 结果 |
|---|---|
| `POST /novel/player/multi_video_model/v1/`（带签名） | HTTP 200，`content-length: 0` |
| `POST /novel/player/video_model/v1/`（body 顶层 `video_id`） | HTTP 200，0 字节 |
| `GET /reading/bookapi/video_tab/multi_video_model/v1/` | HTTP 200，`code=0`，约 23 KB |

空答里没有任何错误码，响应头是 `server: volc-dcdn` —— 这是站方的软拦截，不是超时也不是拒绝。
而且是**国内直连**下发生的（不走代理），所以跟网络、跟 Passwall 节点都无关。
上游那个 JS 实现（sunnysky123/hongguo-web）跑下来也是同一个空答。

**这一版**：取地址改走官方 App 自己现在用的那条 GET 路 ——

```
GET /reading/bookapi/video_tab/multi_video_model/v1/
    ?video_id=<集 id>&video_id_type=0&device_level=1
    &need_all_video_definition=1&need_mp4_align=0&use_os_player=0
    &video_platform=1024&source=0&from_video_id=
```

三个要点，少一个就又是 0 字节：

1. **参数全在 query 上，body 是空的**。少一个参数站方回 `100103 PARAM_INVALID`。
2. **身份是一次性的**：同一个注册身份第二次取流就回 0 字节。实测同一个身份连取十次，只有第一次有回答。
   所以每一集都要新注册一次身份 —— 代码里沿用既有的「身份用掉即弃」那套语义，正好对上。
3. **签名照旧**：六个签名头一个不少，body 为空时 body 的 md5 记 16 个零字节（与站方 App 一致）。
   User-Agent 用视频请求的那个（`VIDEO_UA`），站方靠它把两种请求区分开。

模型挂在 `data.video_model_data[<集 id>].video_model` 里，五档清晰度**内联**在回答中，
不用再取第二个地址。

### 二、老的三道门退成回退

原来的顺序是「注册身份门 → 签名 POST 门 → 公开页」。现在第一道换成新门，老三门整体退到后面：

- 新门答得出来 → 直接播，不碰老门；
- 新门空答 → 走老门（注册身份 → 签名 POST → 公开页）；
- 网络死了 / 被取消 / 身份注册不到 → 不回头，老规矩（照样不问公开页，免得白等）。

公开页（hongguoduanju.com）仍然只有 10 秒试看，当最后一条路。

### 三、全档 ByteVC2 的集：换 codec_type 再要一次备用地址

有一类集的**内联模型和它的备用地址都只给了 ByteVC2**（电视解不了的编码）。
官方播放器这时会带着 `codec_type` 再要一次备用地址，2026-11-10 实测（漫剧《重生后我给emo修士当狗》第一集 7703473857562151961）：

| codec_type | 给回来的编码 |
|---|---|
| 不带 / 0 / 1 / 2 / 4 / 5 | 只有 bytevc2（电视放不了） |
| **3** | **bytevc1（HEVC，电视能放）** |

代码里于是补了这一步：内联档位全不可播时，用 `codec_type=3` 的备用地址再取一次。

## 落点

- core `ContentRepository.kt`：新增 `tabStream` / `tabVideoModel` / `tabVideoModelOnce` / `tabVideoModelText`，
  新门常量 `PLAYBACK_TAB_PATH`；原三门整体搬进 `streamDoorsLegacy` 作回退；
  `episodeStream` 增加「换 codec_type 再取一次备用地址」。
- core `ContentRepositoryTest.kt`：测试用的假服务器认识新接口；「哪道门先问」「取址请求带什么」
  这些断言按新事实重写；老门的覆盖仍然留着（老门随时可能恢复）。

## 验证

**探针（`kotlin-tv/core`，跑的是电视 App 本体的解析链路）**：

- `--play=real-drama`：成功 8 / 失败 0，全部 1080P、实读 1 MB 媒体字节；
- `--play=comic-drama`：成功 8 / 失败 0，全部 1080P、实读 1 MB 媒体字节。

**实机（投影 192.168.1.26，XGIMI TV / Android 8.0 / 1920x1080，CI 出的 0.9.93 正式包）**：

- 覆盖安装 0.9.91 → 0.9.93 成功（versionCode 9091 → 9093，同签名，本机记录保留）。
- **《小江神豪》**（原先「播放失败、已自动重试 3 次」的那部）：从第 63 集起播，
  播完自动连播到**第 67 集 00:11** —— 每换一集都要重新注册一次身份，连续四集全部取址成功。
- **《觉醒伪神：神明弃我我自为神8》第 1 集**（全新一集，必然走新取址路）：正常播放，
  回到首页时进度已到**第 2 集 01:01**。
- 判据：`dumpsys media_session` 里 `state=3`（PLAYING）；播放页 `uiautomator dump` 无「失败/重试」节点；
  两帧截图平均通道绝对差 70（画面在动，不是静态错误页）。
- 验证完已 `am force-stop com.hongguotv.nativeapp`，App 不留在前台拉流。

**单测**：`:core:test` 全绿（396 项，其中 ContentRepositoryTest 98 项）。
**lint**：`:app:lint` BUILD SUCCESSFUL。

## 版本号说明

- versionCode 由 CI 从 tag 算（`major*1000000 + minor*1000 + patch`）：tag `kotlin-v0.9.93` → 9093。
- 上一版已发布的是 `kotlin-v0.9.92`（9092），新 tag 必须严格新于所有已发布的 kotlin-v 版本。

## 已知限制

- **站方接口会变**：这次是 POST 族被关掉、新 GET 族顶上。哪天新 GET 族也被关，就得再找。
  探针每天 03:00 跑一次，异常会自动回报。
- **公开页只有 10 秒试看**：它不是可用的兜底，只是最后一条路。
