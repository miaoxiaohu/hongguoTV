# 弹幕设置合并为一个面板（2026-11-01）

## 要解决什么

用户反馈：**「合并字幕设置，现在菜单里的字幕设置太分散了」**。

先取证澄清：仓库里**没有真正的字幕（subtitle）功能** —— 没有字幕轨、没有字幕样式、没有字幕开关。
`grep -i '字幕|subtitle'` 命中全部是弹幕语境（`DanmakuStyle.kt` 里「下面留给字幕和 HUD」的措辞、
`MainActivity.kt` 画面模式那句「会裁掉部分画面与字幕」的提示文案）。

用户确认：**「字幕设置」就是弹幕那 5 项**（开关 / 大小 / 行数 / 数量 / 透明度）。

分散的证据 —— 这 5 项在**两个菜单里各写了一遍**，切换逻辑也各写一遍：

| 项 | 设置页 → 播放偏好（左列） | 播放中 HUD → 播放设置 |
|---|---|---|
| 弹幕 开/关 | `MainActivity.kt:1165` | `MainActivity.kt:1943`（第 6 项） |
| 弹幕大小 | `MainActivity.kt:1171` | 同上（第 7 项） |
| 弹幕行数 | `MainActivity.kt:1173` | 同上（第 8 项） |
| 弹幕数量 | `MainActivity.kt:1175` | 同上（第 9 项） |
| 弹幕透明度 | `MainActivity.kt:1177` | 同上（第 10 项） |

设置页左列 9 项里占了 5 项，HUD 菜单 10 项里也占了 5 项。

## 用户拍板

1. **合并对象**：弹幕那 5 项
2. **形态**：一个「弹幕设置」面板 —— 设置页与 HUD 各留 1 个入口；点进去是一个面板，5 行并列显示当前值，
   焦点落在行上按确认再选值
3. **开关也收进面板**：主列表里不留独立的「弹幕 开/关」行

## 决定

### 入口（两处，各留 1 个）

**设置页 → 播放偏好（左列）**：9 项变 5 项。

```
默认清晰度 · 1080P
默认倍速 · 1×
画面模式 · 完整画面
自动连播 · 开启
弹幕设置 · 开 · 标准 · 自动 · 中 · 半透明      ← 替换原 5 项
```

**播放中 HUD → 播放设置**：10 项变 6 项。

```
倍速 1× / 清晰度 1080P / 画面 完整画面 / 定时 / 从头播放
弹幕设置 · 开 · 标准 · 自动 · 中 · 半透明      ← 替换原 5 项
```

摘要 = 开关 + 4 个样式当前值，`·` 分隔。实测宽度约 430px，左列可用宽约 558px，一行放得下。

### 面板本体

新建 `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuSettingsDialog.kt`，
`android.app.Dialog`（**不是** AlertDialog —— AlertDialog 的 list item 无法承载「名称 · 当前值」再下钻）。

内容 = 一条竖排 5 行：

| 行 | 当前值取值 |
|---|---|
| 弹幕开关 | 开启 / 关闭 |
| 弹幕大小 | 小 / 标准 / 大 / 特大 |
| 弹幕行数 | 自动 / 3 行 / 5 行 / 8 行 / 12 行 |
| 弹幕数量 | 少（同屏 6 条）/ 中（同屏 12 条）/ 多（同屏 24 条） |
| 弹幕透明度 | 不透明 / 半透明 / 淡 |

交互：

- **↑↓** 在 5 行之间移动
- **确认键**（`DPAD_CENTER` / `ENTER`）→ 在该行**下方就地展开**候选值（竖排子列表），焦点跳到第一个候选
- **←→** 在候选之间移动
- **候选上的确认** → 立即生效 → 刷新该行文字 → 收起候选 → 焦点回该行
- **返回** → 候选展开时先只收起候选；否则关闭面板
- 关闭后焦点回锚点（设置页那个「弹幕设置」按钮 / HUD 的「播放设置」按钮）；锚点不在窗口里就不抢焦点

**改完不关面板**，5 项当前值一眼可见 —— 这是「一次看全部」的核心价值。

### 生效链路（沿用现有，不新增）

- 开关 → `library.danmakuEnabled` + `applyDanmakuSetting()`（弹幕会话启停）
- 4 个样式 → `library.danmakuSize/Rows/Density/Alpha` + `applyDanmakuStyle()`（`setStyle` 走全量重排）
- 落盘仍是 `native-library-v1` 的 `danmakuEnabled/danmakuSize/danmakuRows/danmakuDensity/danmakuAlpha`
- 备份/恢复格式**不动**（`LibraryBackup.BackupSettings` 不变），老用户设置不丢

## 实现选择

三条路子，选第 1 条：

| 方案 | 做法 | 取舍 |
|---|---|---|
| **① 自定义 Dialog + 就地展开**（选定） | 新写 Dialog 类，自己管 5 行焦点与候选展开 | 代码量最大（约 200 行），但完全满足「一次看全部、确认即选、改完原地可见」 |
| ② 两层 AlertDialog | 复用 `tvTools.choose`，选完重建第一层 | 几乎零新代码，但改一项要走「面板→选值→回面板」，且 `TvTools.open` 会关掉前一个 dialog，得手动重建面板刷新 |
| ③ Activity 内 overlay View | 面板画在 catalog/player 自己的视图层 | 焦点图要按场景各写一套，最容易出「焦点跳飞」 |

### ① 的落地细节

**状态**：`expandedIndex: Int?`（哪一行展开了候选）。

**渲染**：每次状态变化就**整体重建**视图树（比手动增删 view 干净得多），重建后把焦点恢复到目标行。
主行 `TextView` 设 `focusable=true` / `focusableInTouchMode=true`，用 `setNextFocusUp/Down` 把 5 行串成链，
边界不循环。候选行缩进显示在所属主行下方。

**按键**：主行与候选行各自 `setOnKeyListener` 消费 `DPAD_CENTER` / `ENTER`。
返回键交给 Dialog 级 `setOnKeyListener` 拦截：候选展开时返回 true（只收候选），否则不拦（正常关闭）。

**与 TvTools 的关系**：面板是独立 Dialog，**不走** `TvTools`（`TvTools.open` 会 dismiss 掉自己手上的 dialog，
会把面板顶掉）。面板关闭时的焦点恢复自己做。

## 改动清单

### 新增

- `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuSettingsDialog.kt`

### 修改 `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`

1. 设置页：删掉 5 个弹幕按钮（现 1164~1177 行），换成 1 个「弹幕设置」按钮
2. HUD「播放设置」菜单：10 项 → 6 项，`choice` 映射重排（原 5~9 → 现在的 5）
3. 新增 `danmakuSettingRows()`：产出 5 个 `DanmakuRow`（标题 / 当前值 / 候选 / 应用回调）
4. 新增 `danmakuSummary()`：产出入口摘要文字
5. 新增 `showDanmakuSettings(anchor, inPlayer)`：打开面板
6. 删掉 `showDanmakuSizePicker` / `showDanmakuRowsPicker` / `showDanmakuDensityPicker` / `showDanmakuAlphaPicker`
   （选值 UI 交给面板的候选行，这 4 个函数只剩「应用 + 刷新行文字」的逻辑，并入 `danmakuSettingRows()`）
7. 设置页的「弹幕」开关按钮与 HUD 里的「弹幕 开/关」项一起消失 —— 开关只在面板里

### 不动

- `core/` 全部：`DanmakuSize/Rows/Density/Alpha` 枚举、`DanmakuTimeline`、`LibraryBackup`、`LibraryIndex`
- SharedPreferences 键名与存储格式
- 弹幕渲染层（`DanmakuOverlay`）

## 验收

core 无改动 → **不需要 core 单测**。这是纯 UI 改动，验收靠实机：

1. 设置页进「弹幕设置」→ 面板出现，5 行当前值与设置项一致
2. ↑↓ 能走遍 5 行；确认展开候选；选完行文字立即变
3. 改大小/行数/数量/透明度 → 播放中的弹幕立刻按新样式重排
4. 关掉开关 → 弹幕停；再开 → 弹幕会话恢复
5. 面板里改完退出 App 重进 → 设置仍是新值（落盘）
6. HUD「播放设置」→「弹幕设置」→ 同一面板，改完即时生效
7. 返回键：候选展开时只收候选，再按才关面板

实机环境：**Palm（`192.168.1.30:5555`）是替身机**，可 adb 截图 + keyevent 验证；
投影/电视上最终确认（等推装服务可用）。

## 打包时机

并入 **0.9.76 正式包**（与「播放总是重试」的修复一起出，探针撤干净后重新出包、重新装）。
版本号必须越过电视上现装的 0.9.74/9074 与探针包 0.9.75-dbg/9075。
