# 播放页触控：与系统返回手势的避让（2026-11-05）

## 问题

用户反馈：**「左右滑屏幕和系统后退手势重了」**。

播放页 0.9.83 起支持横滑拖进度。在全面屏手势导航的设备上（Android 10+ 的手势导航模式），
从屏幕左右边缘起手的横滑会**同时**被两件事响应：

1. App 自己的横滑 → 拖动进度条（快退/快进）。
2. 系统返回手势 → 返回上一页。

结果是「拖进度拖了一半，页面又退了」。

## 取证（2026-11-05，Palm 192.168.1.30，LineageOS 24 / Android 17）

| 项 | 实测值 | 来源 |
|---|---|---|
| 导航模式 | `navigation_mode=2`（手势导航） | `settings get secure navigation_mode` |
| 系统手势区（横屏） | 左 `[0,0][39,720]`、右 `[1241,0][1280,720]` | `dumpsys window displays` 的 `type=systemGestures` |
| 边到边 | `targetSdk=36`，根布局铺满 1280x720 | `build.gradle.kts` / uiautomator dump |
| 遥控器返回 | manifest 已设 `android:enableOnBackInvokedCallback="false"` | `AndroidManifest.xml` |

结论：**冲突只在「系统处于手势导航」的设备上出现**。三键导航与电视设备没有左右边缘返回手势区，
系统手势 inset 为 0，本来就无冲突。

补充事实：`adb input swipe` 注入的事件**不会触发系统返回手势**（系统手势由真实触摸流判定）。
所以这个冲突**无法用脚本自动复现**，必须用手指在实机上试。

## 方案（用户已确认）

**边缘让给系统 + 自动判定**：

- 系统手势区（左右边缘）内起手的滑动 → **App 完全不接管**，交给系统，系统照常返回上一页。
- 屏幕中间起手的横滑 → 照旧拖进度。
- 是否启用这套避让**自动判定**：读系统手势 inset，宽度为 0（三键导航 / 电视）就完全不启用，
  不需要读 `navigation_mode`（普通 App 读不到 `Settings.Secure.navigation_mode`，那是 shell 权限），
  也不需要加设置项。

判定只看**起手位置**：系统返回手势也只看起手位置，从中间起手拖到边缘不会触发系统返回。

## 设计

### core 层（纯 Kotlin，可单测）

新增 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/SystemGestureEdge.kt`：

```
class SystemGestureEdge(leftInsetDp: Float, rightInsetDp: Float) {
    fun blocks(xDp: Float, widthDp: Float): Boolean
    companion object { val NONE }
}
```

- `blocks(xDp, widthDp)`：起手 x 落在左侧 `[0, left)` 或右侧 `(width-right, width]` → true。
- inset ≤ 0 一律 false（三键导航 / 电视 → 恒 false，行为与 0.9.84 完全一致）。
- 放 core 的理由：这是手势判定的纯逻辑，且 core 有单测基建；app 模块没有单测目录。

### app 层

`PlayerTouchController`：

- 新增 `private var edge = SystemGestureEdge.NONE`。
- 新增 `fun setSystemGestureInsets(leftPx: Int, rightPx: Int)`：把系统手势区宽度（px）换算成 dp 存起来。
- `handleTouch` 的 `ACTION_DOWN`：先用 `onHudButton(event)` 判 HUD 按钮（优先级更高，HUD 按钮在底部不在边缘区）；
  再判 `edge.blocks(downXdp, widthDp)`：
  - 命中 → 本轮**不接管**：`blockedBySystem=true`，返回 false（交给 `super.dispatchTouchEvent`）。
  - 后续 `ACTION_MOVE/UP/POINTER_UP` 在 `blockedBySystem` 为真时一律返回 false，不喂手势机。
  - `ACTION_CANCEL` → 清 `blockedBySystem` 并 `machine.reset()`。

`MainActivity`：

- `dispatchTouchEvent` 保持**只加不改**现有分支；把系统手势 inset 传给控制器。
- inset 来源：`ViewCompat.getRootWindowInsets(root)` → `WindowInsetsCompat.getInsets(WindowInsetsCompat.Type.systemGestures())`
  （androidx.core 1.8.0 已支持；API < 29 返回 NONE，天然不避让）。
- 每次 `ACTION_DOWN` 实时读一次（insets 缓存最新值），跟随旋转 / 用户改手势灵敏度。

### 不做的事

- 不动 `dispatchKeyEvent`（遥控器零回归）。
- 不改 core 手势机的四个常量。
- 不加设置项。
- 不改横滑灵敏度、方向锁、单击/双击时序。

## 验收

1. `:core:test` 全绿，新增 `SystemGestureEdge` 用例（左右边缘命中、中间不命中、inset=0 不命中）。
2. `:app:lintDebug` 无 error。
3. Palm 实机（**需要手指操作，脚本注入无效**）：
   - 从左边缘起手右滑 → 页面退回上一页，**进度不动**。
   - 从屏幕中间起手右滑 → 进度正常快进。
   - 遥控器 23/22/20/4 全照旧。
4. 交付前 `adb shell am force-stop com.hongguotv.nativeapp`。

## 版本

- 正式版 **0.9.85 / 9085**（`kotlin-v0.9.85`；CI 算法 `major*1000000+minor*1000+patch` = 9085）。
- 0.9.84 / 9084 已发布（tag `kotlin-v0.9.84`），本次顺延。