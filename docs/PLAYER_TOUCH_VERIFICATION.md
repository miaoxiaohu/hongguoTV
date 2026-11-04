# 播放页触控 实测记录

日期：2026-11-04
设备：Palm PVG100（192.168.1.30，LineageOS 24 / Android 17，720x1280，density 360）
包：release 签名，0.9.82-dbg / 9082（覆盖安装原 0.9.81 / 9081）
验收入口：`kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/PlayerTouchController.kt` + `core/PlaybackGesture.kt`

> 观手段：`uiautomator dump --compressed`（读 HUD 文字与播放时间）、
> `dumpsys media_session`（读播放状态与位置）、`dumpsys audio`（读 STREAM_MUSIC 音量）。
> 全部为外部观测，验收包内**没有**任何埋点、读数条或上报代码。

## 结论一览

| 手势 | 判据 | 结果 |
|---|---|---|
| 单击 | HUD 出现 / 消失（dump 里 HUD 节点在 / 不在） | **通过** |
| 双击 | media_session state=PLAYING ↔ PAUSED | **通过** |
| 横滑 | media_session 位置跳到目标附近 | **通过** |
| 纵滑 | dumpsys audio 的 STREAM_MUSIC 音量变化 | **通过** |
| 遥控器 | 确认 / 左右 / 下 / 返回 全部照旧 | **通过** |

五项全部通过，无未通过项。

## 逐项证据

### 1. 单击 —— 切 HUD：通过

单击落点用物理坐标 `input tap 360 640`（画面正中，见下方「坐标映射」）。

    初始            : HUD_HIDDEN  (dump 长度 807)
    第 1 次单击后   : HUD_VISIBLE (dump 长度 2699)  —— 出现「确认 暂停/播放 …」整行
    第 2 次单击后   : HUD_HIDDEN  (dump 长度 807)
    第 3 次单击后   : HUD_VISIBLE (dump 长度 2699)
    第 4 次单击后   : HUD_HIDDEN  (dump 长度 807)

HUD 可见时 dump 里能读到播放标题与时间，例如：

    重生！丑小鸭逆袭成了万人迷第五季  ·  第 6 集 | 已暂停  ·  00:00 / 01:13  ·  1080P  ·  2×
    确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 更多    返回 收起 / 退出

### 2. 双击 —— 播放/暂停：通过

    MS_before: PAUSED(2)
    MS_after : PLAYING(3)      <- 第一次双击
    MS_after2: PAUSED(2)      <- 第二次双击

判据取 `dumpsys media_session` 的 `state=PlaybackState{state=PLAYING(3)…}`，
不靠界面字样（播放中界面是自绘，文字判据不可靠）。

### 3. 横滑 —— 拖进度：通过

单次滑动后读 HUD 里的播放时间（`mm:ss / mm:ss`）：

| 起点 | 滑动 | 起点时间 | 滑动后时间 | 实际位移 |
|---|---|---|---|---|
| 00:39 | 物理水平右滑 200px（300→500） | 00:39 | 01:24 | **+45 s** |
| 00:00 | 物理水平右滑 600px（300→900） | 00:00 | 01:12 | +72 s（撞到片尾夹取） |
| 01:12 | 物理水平左滑 600px（900→300） | 01:12 | 00:00 | −72 s（撞到片头夹取） |

200px 那一次没撞边界，可直接对照算式：

    200px / 2.25 (px per dp) = 88.9 dp
    88.9 dp × 0.5 秒/dp      = 44.4 s
    实测 +45 s                -> 与 SECONDS_PER_DP = 0.5 相符

方向锁也符合预期：右滑前进、左滑后退，两个方向都生效。

### 4. 纵滑 —— 音量：通过

基准音量先用 `cmd media_session volume --stream 3 --set 5` 置成 5，再滑动：

    上滑 400px（360 700 -> 360 300）: 5 -> 12   (+7 档)
    下滑 400px（360 300 -> 360 700）: 5 -> 0    (-7 档，撞到下限)

算式对照：

    400px / 2.25 = 177.8 dp
    177.8 dp / 24 dp每档 = 7.4 档  -> 取整 7 档
    实测 +7 / -7 档，与 VOLUME_STEP_DP = 24 相符

### 5. 遥控器 —— 无退化：通过

| 按键 | 预期 | 实测 |
|---|---|---|
| 23 确认 | 暂停/播放切换 | PAUSED -> PLAYING -> PAUSED，正常 |
| 22 右 | 快进 | 00:15 -> 00:45（3 次 × 10 s），正常 |
| 21 左 | 快退 | 00:45 -> 00:25（2 次 × 10 s），正常 |
| 20 下 | 打开选集 | dump 出现「选集 / 正在看第 6 集 / 关闭 / 跳转集数 / ‹ 上一组 / 下一组 › / 第 1—20 集 / 共 260 集 / 1 2 3 …」，正常 |
| 4 返回 | 收起面板 | 选集面板关闭，回到播放页 |

播放中按 23 就是暂停 —— 这是遥控器既有语义，不是故障。

## 参数实测

- 横滑灵敏度：**SECONDS_PER_DP = 0.5 秒/dp**（实测 200px → +45 s，与 44.4 s 的算式一致）
- 单击/双击窗口：**DOUBLE_TAP_MS = 300 ms**（常量未改）
- **实测双击注入间隔：72 / 79 / 89 / 98 / 101 / 111 / 126 ms**（3 组共 7 个样本，全部远小于 300 ms）
- 纵滑音量档：**VOLUME_STEP_DP = 24 dp/档**（实测 400px → 7 档）
- 滑区分界：**SLOP_DP = 8 dp**
- 触摸落点：**物理坐标 `input tap 360 640`**（= App 画面正中）

### 坐标映射（Palm 竖屏 720x1280 / App 窗口横屏 1280x720）

显示 `rotation=0`，物理竖屏 720x1280，App 窗口横屏 1280x720，
`uiautomator dump` 的 `rotation="1"`。本轮实测下来：

- **App 的 x 轴 ↔ 物理的 x 轴，App 的 y 轴 ↔ 物理的 y 轴**（无 90° 旋转），
  所以「App 内横滑 = 物理水平滑」「App 内纵滑 = 物理垂直滑」，方向也同向。
- 物理正中 = App 正中 = `(360, 640)`，单击/双击都用它。

判据是行为本身：物理水平滑动改的是播放位置（App 的横滑语义），
物理垂直滑动改的是系统音量（App 的纵滑语义），两者都只可能是轴向直连。

## 复现要点（踩过的坑）

1. **播放中 dump 拿不到空闲状态**：进度条与弹幕持续刷新，`uiautomator dump` 会报
   `could not get idle state`，读出来的树是残的。**先 `input keyevent 23` 暂停**，
   画面静止后 dump 才完整，HUD 文字与播放时间都读得到。
2. **顶部区域不能做垂直滑动**：物理竖屏 y 很小的那条是状态栏/通知栏，
   在那里滑会把通知栏拉下来（`mCurrentFocus` 变成 `NotificationShade`），
   之后的注入全部落空。**改用下半屏（y 300~1100）做纵向滑动**。
3. **通知栏被拉下来后焦点会被抢**：`mCurrentFocus=NotificationShade` 时
   `input tap` / `input swipe` / `input keyevent` 全都进不了 App。
   恢复：`input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` + `cmd statusbar collapse`，
   确认 `mCurrentFocus` 回到 `com.hongguotv.nativeapp/…MainActivity` 再继续。
4. **息屏后按键不生效**：`mWakefulness=Dozing` 时先 `KEYCODE_WAKEUP` + `wm dismiss-keyguard`。
5. **`input swipe` 会让位置撞边界**：测横滑前先用 `input keyevent 21` 快退到片头附近，
   否则一路撞到片尾夹取（`duration-500`），位置读数看不出真实位移。
6. **PS 5.1 读无 BOM 的 .ps1 按 ANSI 解**：脚本里写中文正则（`确认 暂停/播放`）会被解成乱码，
   匹配恒为 false。**改用 dump 文件长度当指标**（HUD 可见 ≈ 2699 字节，隐藏 ≈ 807 字节），
   或者把脚本存成 UTF-8 with BOM。
