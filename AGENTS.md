# AGENTS.md

红果 TV（kotlin-tv 电视端 + server 内容服务）的工作约定。

## 源解析检测（每日 03:00）

- 检测脚本：`scripts\daily-source-check.ps1`
- 定时任务：Mavis 的 cron「红果TV 源解析每日检测」，`0 3 * * *`（Asia/Shanghai）。
- 脚本自己判定并推送（Bark，group=`HongguoTV源解析`）：正常静默，异常才推一条。
  退出码：`0` 正常 / `10` 有异常（已推送）/ `20` 探针没编译。

### 探针是什么

`kotlin-tv/core` 是 Kotlin JVM 模块，`application { mainClass = "com.hongguotv.core.ProbeKt" }`。
探针跑的是**电视 App 本体的解析链路**（`ContentRepository` + `Signer` + `RemoteVideo`），
不是另写一份模拟：

    分类页取剧集 → 详情取分集 → 站方签名接口解析播放地址 → 按 Range 真读 1MB 媒体字节

常用参数：

    --play=real-drama     # 真人剧目录前 8 部，逐一解析并播放（端到端，最常用）
    --play=comic-drama    # 漫剧目录
    --search=<关键词>     # 走真实搜索路径
    --appsearch           # 站方 App 搜索接口的签名探针（带对照组）
    --index               # 全库索引重建 + 检索（慢，几十分钟）

### 跑探针

探针要先编译一次（产物在 `kotlin-tv\core\build\install\core`）：

    cd kotlin-tv
    .\gradlew.bat :core:installDist --console=plain --no-daemon
    .\core\build\install\core\bin\core.bat --play=real-drama

坑：

- Windows 下 **PowerShell 5.1 读无 BOM 的 .ps1 会按 ANSI(GBK) 解**，中文脚本必须存成 **UTF-8 with BOM**。
  探针的控制台输出也是 GBK，但它自己写的 `probe-report.txt` 是 UTF-8 —— 解析结果要用后者。
- 探针的 `probe-report.txt` 写在**当前工作目录**，每次运行覆盖。
- `probe-report.txt` 和 `logs/source-check/` 都是本地产物，已在 `.gitignore` 里，不要提交。

## 实机验证（Palm / 电视）

- **测完要把 App 退出**：`adb shell am force-stop com.hongguotv.nativeapp`。
  别把测试版留在前台接着播：它会一直拉流、一直写本机索引，还占着用户的机器。
- 临时探针（`Log` 埋点、读数条、上报）**只能活在临时包里**：正式交付包必须撤干净，
  撤完重新出包、重新装一次，别拿带探针的包去推电视。
- 覆盖安装要求**同签名 + versionCode 严格大于**已装版本，否则
  `INSTALL_FAILED_VERSION_DOWNGRADE`；debug 包（versionCode 1）盖不了官方包。
- 验证前后各回读一次版本：`dumpsys package com.hongguotv.nativeapp | Select-String versionName`。
- **投影上的推装服务掉了要用户自己去开**，别在后台无限轮询等它（2026-11-01 用户交代）。
- Palm 息屏时按键不生效：`input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` 之后才能操作。
- 应用是强制横屏的：坐标按 **1280x720** 算，别按 `wm size` 报的 720x1280。

## 弹幕入场（2026-11-01 定，别改回去）

- 站方一窗给的内容时间**包含已经看过的那一段**：问 48 秒那窗，回来的是 33~47 秒的内容。
  所以第一批数据几乎总是「晚到」的。
- 晚到的弹幕**入场时间锚到「数据到手时的播放位置」**（`DanmakuAnchors.reanchor` 里的
  `delta=nowMs-minOffset`），整批一起挪、彼此间隔不变。
  早先锚到 `nowMs-晚到阈值` 是错的：那批一露面就已经飞了 800 毫秒（全程 8 秒），
  四个字那条整条都摆在画面右边 —— 用户说的「整条突然出现在右边」。
- 判定「晚到」的阈值是 **0**：内容时间已经过去就算晚到。差一点（几百毫秒）照飞的话，
  一样会带着几百毫秒的「已飞路程」露面。
- 判据用探针的硬指标，别用看图：「第一次露脸时已飞多久」和「落在屏宽百分之几」。
  修好后应全部 ≤100ms、≥99%。视觉模型问「最靠右弹幕在哪儿」统计上没有区分度。

## 弹幕密度（2026-11-01 定）

- 挡屏幕的是**条数**，不是叠字。实测一集同屏最高 36 条、铺在 7 行轨道上（AUTO 模式
  720 高 → 字号 26px、行高 38px、上半屏 42%），屏幕上就是一片白字。
- 同屏上限是用户选的**固定档位**（`DanmakuDensity`：少 6 / 中 12 / 多 24，默认「中」）。
  跟「弹幕行数」（轨道数）是两个独立旋钮，别混成一个。
- 超限的弹幕**最多晚飘 2 秒**（`DanmakuTimeline.MAX_DELAY_MS`），超了就丢。
  等一条弹幕走完要 8 秒，所以「排队等位置」几乎总是超时不候 —— 爆发时是**丢**的，不是排队的。
- 被延后的弹幕**必须改入场时间**（`item.copy(enterAtMs=at)`）。不改的话它一露面就摆在画面中间，
  那是刚修好的「凭空冒出来」毛病。
- 透明度分**填充和描边两档**（`DanmakuAlpha`）：描边是黑边，白字全靠它才看得清，
  所以半透明/淡这两档描边必须比填充更实（`edge >= 160`）。
- 改密度要**从全量弹幕重排**，不能从「已经排好的」重排：被上限丢掉的那几条得留着，
  用户把密度调高时它们该回来（`DanmakuOverlay.source`）。

### 在 Palm 上走设置页（自绘 UI，uiautomator 拿不到控件）

- 焦点在**顶部导航栏**（6 个页签）时，「设置」是第 6 个：从首页先按两下 `keyevent 19`（↑）
  走到导航行，再按 5 下 `keyevent 22`（→）到「设置」，然后 `keyevent 23`（确认）。
- 设置页**左列**从上到下：默认清晰度 / 默认倍速 / 画面模式 / 自动连播 / 弹幕开关 /
  弹幕大小 / 弹幕行数 / **弹幕数量** / **弹幕透明度**；右列是设备相关三项。
- 竖排选择器里**光标每次打开都停在第一项**（不记忆上次选中的）：选「中」要按两下 ↓，
  选「淡」要按两下 ↓。按 → 是换列，在竖排列表里不换项。
- 落盘位置：SharedPreferences `native-library-v1`，键 `danmakuDensity` / `danmakuAlpha`
  （存的是 `LOW`/`MEDIUM`/`HIGH`、`SOLID`/`HALF`/`LIGHT`）。
  读法：`adb shell "su -c 'cat /data/data/com.hongguotv.nativeapp/shared_prefs/native-library-v1.xml'"`。
- 播放中的 HUD：`↑` 开更多面板 → 「播放设置」在第 5 项（先 `↓` 再 `→`），进去的子菜单要往下滚才看得到
  「弹幕数量/弹幕透明度」；HUD 面板会自动隐藏，走键要快（每步 250~400ms）。
- 写临时 .ps1 时**正文只能有 ASCII**（跟上面 ANSI/GBK 那条同源）：中文注释会让 5.1 解析报
  「Unexpected token '}'」，不是语法写错了。

