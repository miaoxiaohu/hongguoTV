# Kotlin 0.9.95：切集时画面正中那个转圈没有了

<!-- notes -->
切到下一集时，屏幕正中会冒出一个转圈的等待图标，叠在上一集的最后一帧上，看着像卡住了。
那是 Media3 播放器自带的缓冲圈，画在画面正中。这一版把它关掉，缓冲的提示改到底部 HUD 的文字上。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9095。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.94 及更早版本并保留本机收藏、观看记录和设置。

## 这一版改了什么

### 一、那个转圈是哪来的

`PlayerView` 里有一块居中的缓冲指示器（Media3 的 `exo_buffering`，系统大圆圈样式）。
本项目在 `MainActivity.kt` 里开的是 `SHOW_BUFFERING_ALWAYS`：

```kotlin
setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.TRANSPARENT)
setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
```

只要播放器处于 `STATE_BUFFERING`，这个圈就转。

### 二、为什么偏偏切集的时候最显眼

切集走的是**复用同一个 ExoPlayer**：`p.setMediaSource(source); p.prepare()`。
换掉媒体源就要重建解码器、重新拉流，中间必然经过一段缓冲状态 —— 这段时间转圈就在转。

而 0.9.58 那两行改动让它变得扎眼：

- `setKeepContentOnPlayerReset(true)`：**上一集的最后一帧留在画面上**；
- `setShutterBackgroundColor(Color.TRANSPARENT)`：遮罩不再盖黑。

于是转圈直接叠在旧画面的正中间。转圈本身没坏，是它出现的时机和位置不对。

### 三、这一版怎么改

1. **关掉系统那个圈**：`setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)`。
   画面正中不再有任何图标。

   为什么不用 `SHOW_BUFFERING_WHEN_PLAYING`：切集时 `playWhenReady` 就是 true，
   `WHEN_PLAYING` 照样会显示 —— 想要「切集不显示」，只能用 NEVER。

2. **提示挪到底部 HUD**（`showPlaybackStatus()`）：
   - 切集/起播时 HUD 留在屏幕上，文字是「正在获取播放地址…」或「正在缓冲…」；
   - 播放中遇到网络抖动、播放器进入 `STATE_BUFFERING` 时，`onPlaybackStateChanged`
     会把 HUD 亮一下，显示「缓冲中」；
   - 恢复播放后 `onIsPlayingChanged` 走一次 `showHud()`，几秒后自动收起。

   净效果：**中间没有图标了，但用户仍然知道在等**，而且不再挡画面。

## 落点

- `app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`：
  - `PlayerView` 的 `setShowBuffering` 改成 `SHOW_BUFFERING_NEVER`；
  - 新增 `showPlaybackStatus()`（切集/起播时点亮底部 HUD）；
  - `playEpisode()` 用 `showPlaybackStatus()` 取代 `hidePlaybackOverlay()`；
  - `onPlaybackStateChanged` 里 `STATE_BUFFERING` 时调 `showHud()`。

其余一个字没动：弹幕几何、焦点落点、取址签名链路、预取策略都原样保留。

## 验证

- **编译**：`:app:compileDebugKotlin` BUILD SUCCESSFUL（只有既有的 deprecated 警告）。
- **单测**：`:app:testDebugUnitTest` 全绿。
- **lint**：`:app:lintDebug` BUILD SUCCESSFUL，0 error。

实机（电视 / Palm）画面效果待验证：切集时中间不再有转圈，底部出现「正在获取播放地址…」「缓冲中」。

## 版本号说明

- versionCode 由 CI 从 tag 算（`major*1000000 + minor*1000 + patch`）：tag `kotlin-v0.9.95` → 9095。
- 上一版已发布的是 `kotlin-v0.9.94`（9094），新 tag 必须严格新于所有已发布的 kotlin-v 版本。

## 已知限制

- **切集时那一下黑屏还在**：那是解码器重建期间画面本身是空的，跟这个转圈是两回事。
  要彻底消除得在片尾之前就把下一集的解码器建好（两个播放器并行），
  老芯片常常只给一个视频解码器，得先在硬件上验证 —— 见 `RELEASE_0.9.58.md`。
- **没有居中图标之后，缓冲的反馈只在底部 HUD 上**：播放中 HUD 是收起的，
  一旦卡住是 HUD 自动亮出来告诉用户，不是常驻提示。
