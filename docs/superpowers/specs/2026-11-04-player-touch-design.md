# 播放页触控逻辑设计

日期：2026-11-04
状态：待实施

## 背景

`kotlin-tv` 是遥控器驱动的电视端应用。播放页的所有操作集中在
`MainActivity.dispatchKeyEvent`（`kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt:2000`）：

- 左右 = ±10 秒（长按连续跳）
- 确认 = 暂停 / 播放
- ↓ = 选集面板
- ↑ / 菜单键 = 更多操作（倍速、清晰度、画面、定时、弹幕）

全工程**没有任何 `onTouchEvent` 代码**，也没有音量 / 亮度相关实现。
需要新增一条触控输入通道，与遥控器并存、互不干扰。

## 目标

1. 播放画面支持手势操作，触屏电视与手机/Palm 上都能用。
2. 遥控器行为**一行不改**，两边同时存在不打架。
3. 首版只做播放画面，不扩展到列表页 / 详情页。

## 范围

### 做

| 手势 | 语义 |
|---|---|
| 单击画面 | 显示 / 隐藏 HUD 控制条 |
| 双击画面 | 暂停 / 播放 |
| 左右拖动 | 跟手拖动进度：实时预览目标时间，**松手才跳转** |
| 上下拖动 | 系统媒体音量增减 |

### 不做（首版）

- HUD 进度条直接拖拽定位
- 亮度手势
- 倍速手势（倍速仍从「播放设置」菜单选）
- 双击左右分区快进 / 快退
- 列表页 / 详情页的触控

## 现状约束

- 播放页视图层级：`root`(FrameLayout) 自下而上叠
  `PlayerView` → `DanmakuOverlay`（自绘全屏）→ `ScrollView(hud)`。
- `DanmakuOverlay` 是全屏 `View`，默认不消费触摸，但它在 `PlayerView` 之上，
  放在它上面的独立触摸层会被 HUD 的 `ScrollView` 挡掉一部分区域。
- `hud` 里的按钮（播放 / 上一集 / 下一集 / 选集 / 播放设置 / 剧集详情）
  是可点击的 `TextView`，走 `button()` → `setOnClickListener`。
- `danmakuSession` / `danmakuOverlay` 与手势无关，不参与。

## 方案选择

| 方案 | 做法 | 结论 |
|---|---|---|
| **A. Activity 层拦截** | `dispatchTouchEvent` 里接管；命中 HUD 可点击子控件则放行 | **采用**。唯一能同时做到「HUD 按钮仍可点 + 画面空白区可手势」的做法 |
| B. 独立透明触摸层 View | 播放页插全屏透明 View 收手势，HUD 放它上面 | HUD 所在条带区域点击被吞；还要管这个 View 的生命周期 |
| C. 让 `DanmakuOverlay` 兼任触摸层 | 复用已有全屏 View | 弹幕层职责混在一起；HUD 区域同样被吞 |

## 架构

### core：手势判定（纯 Kotlin，可 JVM 单测）

新增 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PlaybackGesture.kt`：

```kotlin
sealed class TouchIntent {
    object ToggleHud : TouchIntent()                      // 单击
    object PlayPause : TouchIntent()                      // 双击
    data class Scrub(val targetMs: Long) : TouchIntent()  // 横向拖动，松手时发出
    data class Volume(val steps: Int) : TouchIntent()     // 纵向拖动，每步一档
    object None : TouchIntent()
}
```

`TouchGestureMachine` 负责把一串触摸事件翻译成 intent：

- **输入**：`onDown(x, y)` / `onMove(x, y)` / `onUp(x, y, upTimeMs)`，
  坐标已换算成 dp，机器本身不认识 Android。
- **方向锁**：位移超过 slop（8dp）后一次性判定横向或纵向，之后不再改判。
  斜滑不能同时触发两个动作 —— 否则一次斜滑既拖进度又调音量。
- **单击 / 双击**：单击要等双击超时（300ms）才确认，这是二者的固有冲突：
  早发单击则双击会先触发一次单击（多一次 HUD 开关），晚发单击则单击有延迟。
  取「晚发」：单击延迟 300ms 确认，双击立即出 `PlayPause`。
- **横向拖动 → Scrub**：纯相对映射，`0.5 秒 / dp`，
  目标位置 = 当前位置 + 累计横向位移 × 0.5s/dp，夹在 `[0, duration-500ms]`。
  拖动过程中**不发** intent，只在 `onUp` 时发一次 —— 真的 seek 会卡。
- **纵向拖动 → Volume**：每 24dp 一档，上滑增、下滑减。

灵敏度常量（`SECONDS_PER_DP`、`VOLUME_STEP_DP`、`SLOP_DP`、`DOUBLE_TAP_MS`）
集中在 core 的 companion 里，实机调过再定稿。

### app：触控控制器

新增 `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/PlayerTouchController.kt`：

- 持有 `TouchGestureMachine`，把 `MotionEvent` 翻译后交给它。
- 音量走 `AudioManager.adjustVolume(AudioManager.STREAM_MUSIC, ±1)`。
- 拖动中显示**居中预览层**：目标时间 + 进度条预演；松手 seek 并显示 HUD。
- 纵滑显示音量指示条，约 1 秒后自动消失。

### 接入点

`MainActivity.dispatchTouchEvent`：

```
if (screen == "player" && !playError && episodePanel == null) {
    if (命中 hud 里可点击的子控件) -> 放行给该控件
    else -> 交给 PlayerTouchController
}
```

- `dispatchKeyEvent` **一行不改** —— 遥控器完全不受影响。
- HUD 按钮走原来的 `setOnClickListener`，与遥控器确认键共用同一套回调。

### 状态边界

- `playError`（播放失败）：不响应手势，失败面板自己的按钮仍可用。
- 选集面板打开（`episodePanel != null`）：不响应手势。
- 非播放页：不响应手势。

## 错误处理

- 音频服务不可用 / `AudioManager` 取不到：吞掉异常，不崩；音量指示条不显示。
- 拖动时 `duration` 未知（还没起播）：不产生 Scrub intent。
- 手势过程中 Activity 被销毁：控制器随 Activity 走，不持有 Context 引用。

## 测试

### core 单测（`kotlin-tv/core/src/test/kotlin/com/hongguotv/core/PlaybackGestureTest.kt`）

- 方向锁：斜滑后方向不再改变，不会同时出 Scrub 和 Volume。
- 横滑目标位置：正向 / 反向、越界夹取到 `[0, duration-500]`。
- 单击延迟确认：单击在 300ms 前不出 intent，超时后出 `ToggleHud`。
- 双击压制单击：两次点击间隔小于 300ms 时，只出一次 `PlayPause`，不出 `ToggleHud`。
- 纵滑步数：位移换算成整数档位。

沿用现有 `:core:test`（当前 205 项全部通过）。

### 实机（Palm，192.168.1.30）

Palm 是工程里唯一有真触摸屏的设备（LineageOS 24 / Android 17，720×1280，density 360）。

- `adb shell input tap` / `input swipe` 在触摸屏上产生**真实 MotionEvent**，
  可以用注入事件复现验收，不依赖手点。
- 判据：
  - 单击 → HUD 出现 / 消失（`uiautomator dump` 读自绘文本，或看截图）。
  - 双击 → `dumpsys media_session` 里 `state=PLAYING(3)` ↔ `PAUSED(2)`。
  - 横滑 → 预览层显示目标时间；松手后 `dumpsys media_session` 的 position 跳到目标附近。
  - 纵滑 → 音量指示条出现，`dumpsys audio` 的 STREAM_MUSIC 音量变化。
- 电视上确认遥控器行为未退化（确认 / 左右 / ↓ / ↑ 全部照旧）。

### 构建

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
```

安装到 Palm：

```powershell
adb -s 192.168.1.30 install -r kotlin-tv\app\build\outputs\apk\debug\app-debug.apk
```

## 版本

功能新增，走 **0.9.82 / versionCode 9082**（CI 从 tag 算：`kotlin-v0.9.82` → 9082）。
推 tag 前用 `gh release list --repo miaoxiaohu/hongguoTV` 确认 9082 新于所有已发布的
`kotlin-v` 版本。实验包若需要，`-dbg` 必须**严格小于** 9082。

## 交付纪律

正式交付包**不含任何诊断代码**：不留读数条、不留埋点、不留上报。
调试期的日志只在开发包里，撤干净后再出正式版。
