# Kotlin 0.9.96：取址随机拦截修掉了，自动连播也不再自己冒出进度条

<!-- notes -->
一、有些剧集会突然「播不了」：站方在按请求随机拦截取址接口，一半的时候只回空内容，
地址就落到了网页上那段 10 秒试看片，播十秒就报失败。这一版改成空答就换一个新身份再问，
一直问到问出真地址为止。
二、自动连播切到下一集时，屏幕底部会自己冒出一条进度条和一行状态字，像是有人在按遥控器。
其实没人按过 —— 那是切集那一瞬 HUD 自己弹了出来，而进度条此时进度还是 0。
这一版让自动连播切集保持安静；手动切集照旧弹 HUD，但进度条要等真的有进度了才出现。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9096。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.95 及更早版本并保留本机收藏、观看记录和设置。

## 另一处修复：站方随机拦截取址接口（这一版的主因）

### 一、现象

11-14 凌晨的检测里，real-drama 与 comic-drama 各有 1 集失败，报
`SampleClipException: 只取到 10 秒试看片段，不是完整剧集`。手动重跑一次，失败的是**另外几集**。

### 二、根因

站方对取址那条 GET（`/reading/bookapi/video_tab/multi_video_model/v1/`）是**按请求随机**回空答的
（HTTP 200 + 0 字节），跟片源、跟设备、跟出口都没关系。2026-11-14 实测（real-drama 8 集、22 个请求）：

| 门 | 结果 |
| --- | --- |
| 主域 `api5-normal-sinfonlineb.fqnovel.com` | **10/10 全空**（对本站客户端一直不给） |
| 备用域 `api5-normal-hl.fqnovel.com` | 12 次里 7 次有内容 → **42% 空答** |

一个「尝试」= 1 个**全新身份** + 两个域各问一次 → **单次成功率只有 58%**。
老代码一个集只试 2 次，于是每集有 0.42² ≈ **17.6%** 的概率两连败：
16 集里每轮就有 2~3 集掉到公开页（只有 10 秒试看）被判失败 → 探针 exit 10。

**决定性证据**：失败的是哪几集**每轮都换** —— 同一集上一轮 OK、下一轮就挂
（例 `7704590737282714649`、`7703473850738035737`）。所以这是站方按请求在拦，
不是那几集没有片源，也不是本机网络或节点的问题。

### 三、这一版怎么改

- **空答就换一个新身份再问**：`ContentRepository.PLAYBACK_TAB_ATTEMPTS = 8`，
  问到答出模型为止。命中即停，所以**期望注册数几乎不变**（≈1.7 次/集，跟只试两次时同量级），
  涨的只是尾巴：八次全空的概率 0.42⁸ ≈ **0.09%**。
- **注册被拒不再被空答盖住**：`takePlaybackIdentity()` 抛 `RegistrationUnavailableException` 时
  **立刻上抛**（走长退避），不再当成一次普通失败继续重试、最后以「空内容」掉到公开页 ——
  那会让电视放 10 秒试看再报「播放失败」，完全看不出是注册的事。

### 四、验证

- 探针：`real-drama` 8/8、`comic-drama` 8/8 全部通过（修复前连续两轮都是 13/16、14/16）。
- 单测新增两项：`anEmptyAnswerIsFollowedByAFreshIdentityAndAnotherAsk`（空答之后换新身份再问）、
  `aRefusedRegistrationIsNotHiddenBehindAnEmptyAnswer`（注册被拒立刻上抛，不被空答盖住）。

## HUD 那部分：这一版改了什么

### 一、那条进度条是哪来的

`playEpisode()` 里原本无条件地写：

```kotlin
progressBar.visibility = View.VISIBLE
showPlaybackStatus()
```

`showPlaybackStatus()` 会把底部 HUD 整条点亮（标题 + 状态文字 + 进度条 + 操作提示）。
切集瞬间播放器还没开始拉流，`progress = 0`，所以那条进度条画出来是**一条 0% 的横条** ——
什么都不说明，还把画面底部占掉一块。

自动连播走的是同一条路（`playEpisode(episodeIndex+1, chained=true)`），
所以用户什么都没按，屏幕却自己多出一堆东西。

### 二、这一版怎么改

决策全部抽到 core 的纯函数 `PlaybackHudPolicy`，app 层只负责执行：

| 判定 | 结果 |
| --- | --- |
| `showStatusOnSwitch(chained)` | 自动连播切集 `chained=true` → **不弹 HUD**；手动切集 → 照旧弹 |
| `showProgress(playbackReady)` | 进度条**只在就绪后出现**，不再有 0% 的条 |
| `shouldAutoHide(isPlaying, buffering)` | 播放中 / 缓冲中都允许 HUD 自动收起 |
| `autoHideDelayMs(buffering)` | 播放中 4.5 秒收，缓冲中 12 秒收 |

**自动连播的例外**：`STATE_BUFFERING` 那次 `showHud()` 也跳过（`chainedEpisode` 标志）——
用户没要求，就不该自己多出东西。**但出错时照旧弹 HUD**：`playerFailure` 会把失败原因一起亮出来，
那才是该说话的时候，这条别一起去掉。

### 三、顺手修掉的老隐患：缓冲中的 HUD 永远收不掉

老 `hideHud` 的收起条件是 `player?.isPlaying == true`。
可是播放器一旦进入缓冲，`isPlaying` 就是 false，**条件永远不成立 → 收起定时器根本不排 →
HUD 就一直挂在画面上收不掉**。网络一抖，用户就被一条常驻的进度条糊在脸上。

现在改成 `shouldAutoHide(isPlaying, buffering)`：

- 播放中 → 4.5 秒后收；
- 缓冲中 → 12 秒后收（缓冲可能久一点，但不该永远挂着）；
- **用户自己按的暂停 → 不收**（暂停时 HUD 是给人看的状态，不是噪音）。

## 落点

- `core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt`：
  - 新增常量 `PLAYBACK_TAB_ATTEMPTS = 8`（取址这条 GET 最多换几个身份去问）；
  - `tabVideoModel()` 的重试循环从 2 次改成 8 次，每次都取一个**全新身份**（身份用一次就扔）；
  - `takePlaybackIdentity()` 抛 `RegistrationUnavailableException` 时**立刻上抛**，不再被后面的空答盖掉。
- `core/src/test/kotlin/com/hongguotv/core/ContentRepositoryTest.kt`：新增 2 项单测。
- `core/src/main/kotlin/com/hongguotv/core/PlaybackHudPolicy.kt`（新增）：纯判定，无 Android 依赖。
- `core/src/test/kotlin/com/hongguotv/core/PlaybackHudPolicyTest.kt`（新增）：7 项单测。
- `app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`：
  - `playEpisode()` 新增 `chained` 参数，并据此决定弹不弹 HUD；
  - `hideHud` / `showHud` / `updatePlaybackText()` 改走 policy；
  - 进度条可见性从「切集时硬写 VISIBLE」改成跟随 `playbackReady`。

其余一个字没动：弹幕几何、焦点落点、**取址签名本身（六头真签名、query 编码、device_level=1）**、预取策略都原样保留。

## 验证

- **编译**：`:core:compileKotlin` / `:app:compileDebugKotlin` BUILD SUCCESSFUL。
- **单测**：`:core:test` 全绿、`:app:testDebugUnitTest` 全绿；lint：`:app:lintRelease` BUILD SUCCESSFUL。
- **探针（取址修复的验收）**：修复前 11-14 连续两轮都是 13/16、14/16（每轮各有 1~3 集挂在
  10 秒试看片上）；修复后 `real-drama` 8/8、`comic-drama` 8/8 全通过，失败 0。
  媒体四字节（fourcc）仍全部 `hvc1,mp4a`（真 HEVC，电视能硬解）。
- **实机（投影 XGIMI_TV，Android 8.0，0.9.96 包）**：
  - **自动连播**：一次自然连播全程采样 55 次（跨两次切集，第 97→98 集、第 98→99 集），
    **55 次全部 HUD 不在场、进度条不在场**；另两次 30 次、9 次采样同样全无。
  - **手动切集**：「更多面板 → 下一集」切到第 99 集，t0 采样 `HUD=有 / 进度条=有`，
    状态文字「正在播放 · 00:00 / 03:20 · 1080P · 2×」，4.5 秒后自动收起（21:53:24 有 → 21:53:28 无）。
  - **暂停态**：HUD 常驻、进度条保留，文字「已暂停 · 03:20 / 03:20 · 1080P · 2×」。
  - 0.9.95 的对照证据：自动连播第 88→89 集时 HUD 整条在场，
    含 `ProgressBar bounds=[96,979][1824,985]` —— 正是这一版要去掉的那条。
- **实机二（Palm，Android 17，0.9.96 正式包，设备端连拍 223 帧跑完整段自动连播）**：
  真人剧《全球死考：刚下刑场你让我去考试二季》从第 1 集一路自动连播到第 10 集，
  日志里每次切集都是 `Playback ready episode=N preloaded=true reused=true`（预加载复用 ⇒ 自动连播）。
  - **所有自动连播切集帧，HUD 文字与进度条全部不在场**（逐帧像素判据，见下）；
  - 同一段连拍里，**首次起播**（手动进第 1 集）HUD 照常出现、4.5 秒后收起；
  - **取地址失败重试**时错误 HUD 照常弹出 —— 两条对照都符合设计，改动生效且没误伤。
  - 判据（`tmp/an5.py`，本地产物不提交）：HUD 文字 = y∈[505,575] 内**连续 ≥8 行**每行亮像素 ≥200
    且最右亮像素 x ≤ 900（左对齐小字）；进度条 = y∈[600,660] 内**连续 ≥2 行**亮像素 ≥700。
    ⚠️ 不能只看「某行亮像素数」——画面自己的大字标题/亮字幕会落进同一行带造成误判。
    基线自检：暂停态截图必须判成「HUD 在 + 进度条在」，播放中截图必须判成「都不在」。
  - 采样脚本：`/sdcard/auto3.sh`（`T=$(date +%s)` → `/sdcard/auto3/t$T.png`），
    帧名自带设备时间戳，可与 `logcat -v time` 的事件按相对时间对齐（**设备时钟可能不准，别跟宿主机时间比**）。

## 版本号说明

- versionCode 由 CI 从 tag 算（`major*1000000 + minor*1000 + patch`）：tag `kotlin-v0.9.96` → 9096。
- 上一版已发布的是 `kotlin-v0.9.95`（9095），新 tag 必须严格新于所有已发布的 kotlin-v 版本。

## 已知限制

- **手动切集仍然会弹 HUD（约 4.5 秒）**：用户按了遥控器，等一个答复是合理的。
  如果连手动切集也不想弹，那是另一条规则，别混在这一版里。
- **HUD 只亮 4.5 秒这件事很难采样**：`uiautomator dump` 单次 2.65 秒，
  连拍几次就全落在窗口之外，看起来像「HUD 从不出现」。取证要切完立刻采，或采暂停态。
