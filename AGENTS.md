# AGENTS.md

红果 TV（kotlin-tv 电视端 + server 内容服务）的工作约定。

## 源解析检测（每日 03:00）

- 检测脚本：`D:\document\project\time_task\hongguoTV\daily-source-check.ps1`
  （2026-10-29 起归置到 time_task；本仓库 `scripts\daily-source-check.ps1` 是旧副本，不再由 cron 调用）
- 定时任务：Mavis 的 cron「红果TV 源解析每日检测、自动修复与发版」，`0 3 * * *`（Asia/Shanghai）。
- **不推 Bark**（2026-11-04 起按用户要求停用推送）：脚本默认不推，异常只由 cron 会话回报一句。
  要推必须显式加 `-Push`。
  退出码：`0` 正常 / `10` 有异常（结果在脚本 stdout 的「异常明细：」段里）/ `20` 探针没编译。
- **2026-11-05 起任务升级为「检测 + 自动修复 + 发版」**：探针报异常时，任务自己
  读证据 → 定位站方变更 → 改 `kotlin-tv/core` 代码 → 编译 + 跑单测/lint → 重跑探针验证 →
  **推 main + 打 `kotlin-vX.Y.Z` tag → 等 CI 出 release → 验资产和 update.json**。
  验收基线：real-drama / comic-drama 各 8 集全通过、fourcc 全 `hvc1,mp4a`、单测与 lint 全绿。
  最多自动修 2 轮；修不好就如实报告，不要反复瞎改，也不要改测试来凑绿。
  **没修好或测试没过就不发版** —— 半修好的包比不发更糟。
  版本号规则照下面「版本号与发布」那节：CI 从 tag 算 versionCode，新 tag 必须**严格新于所有已发布的
  kotlin-v 版本**（发版前先 `gh release list` 查），一次运行最多发一个版本。
  改动范围只限 `kotlin-tv/`；`server/` 的纯 JS 签名链路已知失效，不要去修。
  每次修完要把新事实补回本文件，并写 `kotlin-tv/RELEASE_<版本>.md` 与 CHANGELOG 条目。

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

### 往 App 输入框里输中文（2026-11-06 实机打通）

- `adb shell input text` **不支持中文**；剪贴板注入在 Android 17 上被封
  （`cmd clipboard set-text` 无输出、`service call clipboard` 回 No items）。
- Gboard 逐字母敲拼音、靠猜候选词，**不要走这条路**
  （实测「hanfubianjun」+空格上屏的是「韩服变军」这种错词）。
- 可行解法是 **ADBKeyboard**（`https://github.com/senzhk/ADBKeyBoard/raw/master/ADBKeyboard.apk`，17 KB）：
  1. `adb install -r -g <apk>` —— **装之前先按 4 关掉 Play Protect 弹窗**，
     否则安装会被弹窗挡掉、`pm list packages` 里根本查不到这个包（当时误判成「没装上」）。
  2. `ime enable com.android.adbkeyboard/.AdbIME` + `ime set com.android.adbkeyboard/.AdbIME`。
  3. 注入用 **Base64 通道**，绕开 PowerShell 5.1 控制台编码：
     `am broadcast -a ADB_INPUT_B64 --es msg <base64(utf8)>`
     （`ADB_INPUT_TEXT` 直接带中文会被控制台编码搞坏）。
- 注入后用 `uiautomator dump` 读回 EditText 里的文字核对，确认真的进去了。
- 拿到文字后：**回车 66 触发搜索**；选中搜索结果卡片后**还要再按一次确认 23** 才进详情页；
  详情页选集时点一下只是移焦点，**同样要按 23** 才会切集播放。
- **验收完必须还原**：`ime set com.google.android.inputmethod.latin/.LatinIME`
  + `ime disable com.android.adbkeyboard/.AdbIME` + `adb uninstall com.android.adbkeyboard`；
  顺手把 `package_verifier_enable` / `verifier_verify_adb_installs` 改回 1。
- 🔴 **2026-11-05 实测更正**：这台 Palm（Android 17）上 ADBKeyboard **收不到广播，文本注不进去**
  （连纯 ASCII 的 `ADB_INPUT` 也不行）—— 详见上面「ADBKeyboard 在这台 Android 17 上注入不进去」。
  走查改用英文关键词（本机索引的模糊匹配能命中英文剧名）。

### 怎么读自绘 UI、怎么操作（2026-11-04 实机补）

- **自绘 UI 拿控件只能靠 uiautomator dump**：
  `adb shell uiautomator dump /sdcard/u.xml` 之后 `cat /sdcard/u.xml`。
  能读到文本、焦点（`focused="true"`）、勾选态 —— 设页、弹幕面板都是自绘的，dump 是唯一读法。
- **`input tap` 不触发 onClick**，只把焦点挪过去。操作一律走 `keyevent`：
  确认 23 / 下 20 / 上 19 / 右 22 / 左 21 / 返回 4。
- **App 的弹幕不是纯白**：半透明填充（HALF 档亮度上限约 200）+ 不透明黑描边。
  统计字高要用**亮度阈值约 165**，用纯白阈值（>225）会一行都检不出来。
- **改设备上的设置**可以直接改 SharedPreferences（`adb shell` 已是 root，**没有 su**）：
  `adb shell "sed -i 's|>MEDIUM<|>HIGH<|' /data/data/com.hongguotv.nativeapp/shared_prefs/native-library-v1.xml"`。
  绕过了 UI，所以**验收完必须改回去**（双引号里写 `\"` 会失败，用不含引号的 `>MEDIUM<` 模式）。
  ⚠️ **但外部改 prefs 对「弹幕密度」不生效**（2026-11-04 实测）：App 启动后会把自己那份 prefs
  覆盖回去，设置页摘要仍显示旧档位。**密度只能走 App 自己的设置页 UI 改**；
  回读 prefs 只证明「落盘了」，**不证明字号真的变了** —— 必须用像素量。
- **改密度的 UI 路径**：设置页 → 左侧「弹幕设置」→ 第 4 行「弹幕数量」→ 确认展开候选
  （候选竖排：少 6 / 中 12 / 多 24，**每次打开光标都停在第一项**，不记忆上次）→ ↓ 几下选档
  → **再按一次确认生效，面板不关**。选「多」= 两下 ↓，选「中」= 一下 ↓。
- **录屏必须阻塞式**：`adb shell screenrecord --time-limit 45` 要同步等它跑完。
  后台化会被打断，写不出 moov atom，文件只有几十 KB —— 用「文件 > 400 KB」判断是否真有画面。
- **「在播」的可靠判据是 `dumpsys media_session` 里的 `state=PLAYING(3)`**。
  不能用「界面上没有『立即重试』按钮」判断（重试中的页面也没有那个按钮）。
  **播放中绝不能按 23**（那是暂停键），会把正在播的片停掉。

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

## 弹幕横向几何（2026-11-01 定，别改回去）

用户要求：「弹幕应该是从右边第一个字出来，左最后一个字结束」。

- 飞行区间是**整个屏幕**（overlay 宽度），**不再跟视频画面矩形走**。算式钉在 core
  `DanmakuMotion`（`xAt` / `visibleTextRange`），app 层只负责用：
      x = bandWidth - (elapsed/lifetime) * (bandWidth + textWidth)
- 起止都放在**屏外**：起手时整条在屏幕右边外面（一个字都看不见），第一个字才从**右缘**露头；
  收尾时最后一个字最后在**左缘**收尾。整条都在屏外时直接不画。
- **这推翻了 0.9.50（`git show 3eecda9`）「弹幕跟画面矩形走」的决定**：那一版画的时候没裁剪，
  起点落在画面右缘，文字整条画在右边那条黑边上 —— 一出场就整条可见，用户说的
  「整条突然出现在右边」。竖屏短剧在 16:9 电视上左右留黑边时更是如此。
- 竖向（轨道 / 字号 / 行数 / 密度 / 透明度）**一律不动**。
- 判据用**像素**，别用看图：临时 `-dbg` 包注入合成弹幕（纯黑底 + 固定文字），录屏后逐帧量亮像素
  的 x 范围 —— 首帧应当只亮右缘 1~2 列，末帧只亮左缘几列，中间整条可见。
  合成弹幕的时钟用探针自己的 `SystemClock.elapsedRealtime()`，不跟播放进度走。

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
- 设置页**左列**从上到下：默认清晰度 / 默认倍速 / 画面模式 / 自动连播 / **弹幕设置**；
  右列是设备相关三项。「弹幕设置」那一行显示摘要，形如 `开 · 标准 · 自动 · 中 · 半透明`。
- 竖排选择器里**光标每次打开都停在第一项**（不记忆上次选中的）：选「中」要按两下 ↓，
  选「淡」要按两下 ↓。按 → 是换列，在竖排列表里不换项。
- 落盘位置：SharedPreferences `native-library-v1`，键 `danmakuDensity` / `danmakuAlpha`
  （存的是 `LOW`/`MEDIUM`/`HIGH`、`SOLID`/`HALF`/`LIGHT`）。
  读法：`adb shell "cat /data/data/com.hongguotv.nativeapp/shared_prefs/native-library-v1.xml"`
  （adb shell **已经是 root，没有 su**，别套 `su -c`）。
- 播放中的 HUD：`↑` 开更多面板 → 「播放设置」在第 5 项（先 `↓` 再 `→`）；子菜单是 6 项，
  **「弹幕设置」是第 6 项**，进去是五行面板（开关/大小/行数/数量/透明度），按确认就地展开候选；
  HUD 面板会自动隐藏，走键要快（每步 250~400ms）。
- 弹幕设置面板的用法：**按确认展开候选 → 上下选 → 再按确认生效（面板不关）**；
  **返回键在候选展开时先收候选，再按才关面板**。
- 写临时 .ps1 时**正文只能有 ASCII**（跟上面 ANSI/GBK 那条同源）：中文注释会让 5.1 解析报
  「Unexpected token '}'」，不是语法写错了。

### 量弹幕像素的两个判据（2026-11-04 实机建立）

实机验收弹幕样式**只能靠像素**，别用看图，也别信设置页摘要。工具在 `tmp/`（本地产物，不提交）：

    python tmp\danmu_h.py <video.mp4> <outdir> 300 165   # 字高：亮度阈值 165，只看 y<300 的弹幕区
    python tmp\tracks.py <outdir> 165 300              # 轨道间距：同行相邻行带起点差的众数

- **轨道间距是更硬的判据**：行高 = 字号×1.5，只跟样式算式有关，不受字形和画面内容干扰。
  720 高：MEDIUM = 25.92×1.5 = **38 px**，HIGH（字号 ×0.85）= **33 px**。
- **字高是辅助判据**：字形会因内容而变，只能看众数/中位数。MEDIUM 实测众数 24 px，
  HIGH 众数 21 px（24×0.85 = 20.4）。两档分布几乎不重叠才算数。
- **要量的是同一集、同一屏、同一亮度阈值的两个档位**，否则没有可比性。

## 版本号与发布（2026-11-02 定）

- **versionCode 是 CI 从 tag 算出来的，不是手填的**：
  `code = major*1000000 + minor*1000 + patch`（`.github/workflows/release.yml`）。
  所以 tag `kotlin-v0.9.81` → 9081；tag `kotlin-v0.9.80` → **9080**，跟 9081 差一档。
- **文档里写的 versionCode 必须等于 CI 算出来的那个数**。两边对不上，后果不是「文档难看」：
  电视端 `AppUpdater` 只在 `新 versionCode > 已装 versionCode` 时提示更新，
  只要有人照文档装了一个比官方 tag 算出来的数更大的包，**这一支就再也发不出更新了**
  （2026-11-02 的 0.9.80 就差点这么栽：文档写 9081，CI 出的是 9080，最后整版改成 0.9.81/9081）。
- 本地手工出包用 `HONGGUOTV_VERSION_CODE` / `HONGGUOTV_VERSION_NAME` 注入，**必须与 tag 算出来的一致**。
  出包前先删 `app/build`（AGP 的 `GenerateBuildConfig` 不跟踪版本号，不删会留着上一次的版本号）。
- **`-dbg` 实验版不能跟正式版抢同一个 versionCode**：实验版要**严格小于**正式版，
  否则正式版推过去电视也不提示更新。实验版占掉 9080，正式版就得是 9081（→ versionName 0.9.81）。
- 推 tag 前查一遍已发布的版本：`gh release list --repo miaoxiaohu/hongguoTV`，
  CI 守卫要求新 tag 的 versionCode **新于所有已发布的 kotlin-v 版本**。

### 发布实测（2026-11-05，kotlin-v0.9.84）

- 推 tag 后 CI 的 `Build and publish TV update` 约 **5 分钟**出 release（另有 `Verify Kotlin standalone APK` 约 4 分钟、
  `Verify and publish Docker` 约 1 分钟，都跟着 main 推送跑）。
- release 资产四个：`hongguotv-kotlin-<版本>-android8.apk`、`hongguotv-kotlin-v<版本>-source.zip`、
  `SHA256SUMS`、`update.json`。**电视端读的是 `releases/latest/download/update.json`**，
  所以新 release 必须抢到 `latest` 指针（`gh release` 发布时会自动设）。
- **本地手工出的包和 CI 出的包 sha256 不一样**（签名时间戳等），但**证书指纹一致** → 能互相覆盖安装。
  所以核对「能不能覆盖」要看证书，不要比 sha256：
  `apksigner verify --print-certs <apk>`（本机在 `D:\Programs\android-sdk\build-tools\36.0.0\apksigner.bat`）。

## 搜索结果的焦点落点（2026-11-05 定，别改回去）

搜索页的**工具条**（输入框 + 搜索/历史/手机输入/续建索引）往下，焦点一律落在
**结果列表的第一条**，不是按屏幕几何对齐的那一列。

- **根因**：焦点图（app 的 `FocusPlan`）默认按几何对齐落点 —— 从「搜索」按钮往下会落到
  与它同一列的**第五张**卡，从输入框往下会落到**第二张**（输入框横跨大半屏，按"重叠最多、
  中心最近"挑中了中间那列）。可搜索结果对用户是一份**列表**，入口就该是第一条。
- **实测证据（2026-11-05，Palm 192.168.1.30，0.9.86 包，搜到 6 条结果）**：焦点在「搜索」按钮
  （x≈804~878）时按下键，焦点落在第 5 张「遇棠方知涉：被偏执大佬黏上了」（x≈844~1021）。
- **修法**：不改通用几何规则。`FocusPlan.build` 增加可选的 `downOverrides`（钉死「往下」的落点），
  `FocusRouter` 增加同名的 `downOverrides`（view id → view id），搜索页在 `catalogGrid` 里把
  整条工具条钉到当前页第一张卡。**换屏必须清空**（`catalogGrid` 开头清 + `releaseCatalogViews`
  里的 `focusRouter.reset()`），指向已销毁 view 的钉子会被 `FocusPlan` 忽略。
- **附带**：输入框在**有输入法**的机器上会把方向键吃掉（EditText 自己处理 DPAD_DOWN，
  实测按下键焦点纹丝不动），所以输入框上另有 `setOnKeyListener` 兜底，直接把焦点交给第一张卡。
- **附带修掉的两个既有缺陷（2026-11-05 实机发现，不是本次改动引入的）**：
  - **网格里的卡片按「上」出不了结果区**：焦点图给的上方目标在 ScrollView 之外，系统自己的
    焦点搜索跨不过去（ScrollView 把方向键当滚动吃掉了），焦点就钉在网格里。修法：卡片上另有
    `setOnKeyListener`，目标在 ScrollView 之外时手动 `requestFocus`（在网格里就交回系统，
    它会连滚动一起做对）。
  - **输入框按「上」「下」都不动**：EditText 自己处理方向键。修法：输入框的 `setOnKeyListener`
    接管 —— 下 = 结果第一条，上 = **「搜索」页签**（不是几何算出来的导航行某页签：输入框横跨
    大半屏，按几何会落到靠右的「最近观看」）。
- **判据**：实机 `uiautomator dump` 看 `focused="true"` 的节点 —— 应当是第一张卡
  （`content-desc` = 第一部剧名，`bounds` 的 x 等于第一列的位置）。单测在 `FocusTest.kt`：
  `theToolRowCanBePinnedToTheFirstResult` / `pinningOnlyChangesTheDownTarget` /
  `aPinToSomethingThatIsNotOnScreenIsIgnored` / `upFromTheFirstResultLandsOnTheSearchInput`。

### ADBKeyboard 在这台 Android 17 上注入不进去（2026-11-05 更正上面那条）

`adb install -r -g` 成功、`ime enable` / `ime set` 成功、进程在跑、`mBoundToMethod=true`，
但 `am broadcast -a ADB_INPUT_B64 --es msg <base64>` **注不进任何文本**（连纯 ASCII 的
`ADB_INPUT` 也不行），显式加 `-p com.android.adbkeyboard` 也不行；进程日志里没有异常。
→ **别再指望这条路输中文**。走查用英文关键词即可：本机索引的模糊匹配会命中英文剧名
（搜 `love` 能搜到《苦果 The Price of Love》《诱情染指之黎野入怀》这类真实结果，
站方还会回十条同类推荐），焦点落点照样验得了。

## 手机输入页（2026-11-07 定，别改回去）

电视端有个「手机输入」功能：App 起一个小 HTTP 服务，手机扫二维码/打开 URL 在网页上打字，
提交后电视上直接出搜索结果。两个坑已经踩过并修好，**别改回去**：

### 一、会话不能跟着 App 退后台一起关

- **根因（实测取证，不是猜的）**：`MainActivity.onStop()` → `tvTools.close()` → 关掉 PhoneSearchServer 的
  ServerSocket。同一段会话实测：App 在前台 `curl GET` = **200**；按 HOME 退后台后 `curl GET` = **000 / exit 7**
  （连接被拒）。浏览器表现 = **页面能开（GET 早到）、提交失败（POST 晚到，会话已死）** = ERR_CONNECTION_REFUSED。
- **修法**：把「关界面」和「关会话」拆开。
  - `TvTools.background()` —— 退后台**只收二维码弹窗、保留会话**（服务继续应答）；
  - `TvTools.foreground()` —— 回前台把二维码弹窗**重摆**（会话还在就重显，过期才作废）；
  - `TvTools.expirePhoneSession()` —— 会话最长 **30 分钟**（0.9.90 起），超时才真正关；
  - 后台收到的查询词先进 `pendingPhoneQuery`，回前台再搜（`MainActivity.submitPhoneQuery()`）。
- **没有加前台服务，也没加开机自启** —— 用户明确排斥常驻进程，别自作主张。

### 二、网页 input 必须声明文字键盘

- 手机浏览器不一定给未声明 `inputmode` 的框弹文字键盘；回车键也不显示「发送」。
- **`required` 必须去掉**：浏览器原生气泡是英文（「请填写此字段」），会挡在提交前面，
  用户只看到气泡。空提交交给服务器回中文提示（「请输入 1—80 个字符的剧名」）。
- 现在用的声明：`type="text" maxlength="80" autofocus inputmode="text" enterkeyhint="send"
  autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="off"`。
- 页面 CSP 是 `default-src 'none'`，**加内联 JS 需削弱 CSP** —— 这次没用 JS，只改声明。

### 三、这台 Palm 上验证网页表单的两道坎

- **LineageOS Jelly 抢键盘**：网页 input 即使 `focused="true"`，`input text love` 的字符仍进
  **地址栏**（dump 里出现「他love一样」），网页 input 为空 → 服务端 POST 后回
  「请输入 1—80 个字符的剧名」。这是 Jelly 自身的按键分发行为，**网页侧改不了**，别去迁就 Jelly。
  端到端验证用 **Chromium 内核浏览器**（PC 端内置 Browser）做：navigate → inspect 拿 input/button 的 ref →
  `fill(ref, "love")` → `click(buttonRef)` → 页面变「已发送到电视」。
- **中文拼音上屏无法自动验证**：这台机只有 AOSP LatinIME 与 Gboard，`input text` 不支持中文，
  ADBKeyboard 在这台 Android 17 上收不到广播（见上面那条）。**这是设备 IME/自动化环境的限制，
  不是代码问题** —— 如实报告，让用户在真机键盘上敲一次中文确认。

### 四、抓包与取证手法（这次定位靠它）

- 会话存活判据：对电视给的 URL `curl GET`，前台 200 / 后台 000 —— 一条命令分清「服务关了」还是「签名坏了」。
- 提交到达判据：`adb -s 192.168.1.30:5555 root`（Palm userdebug 直接 root，**没有 su**）→
  `adb shell 'nohup tcpdump -i any -n -s 0 -w /sdcard/cap.pcap tcp port <PORT> >/sdcard/tcp.log 2>&1 &'` →
  `adb shell 'tcpdump -r /sdcard/cap.pcap -n -A'`。能看到 `POST /<token>/submit` 才算真的到服务端。
- **测完必须收尾**：`pkill tcpdump`；`adb shell rm` 会被权限门拦（delete-like），用新 pcap 文件名代替。
- Jelly/Gboard 的 UI 节点混在一起：网页 input 是 `class="android.widget.EditText"`，
  页面按钮是 `class="android.widget.Button"`；**Gboard 自己的按钮 package 是
  `com.google.android.inputmethod.latin`**，按 package 过滤时别误点。
- 会话 TTL 过期后页面显示「二维码已失效…」（0.9.90 起 TTL 30 分钟，过期后还有 10 分钟宽限期）；`am start` VIEW 同一 intent **不会重新加载**
  （提示 "intent has been delivered to currently running top-most instance"），
  要 `am force-stop org.lineageos.jelly` 后再打开。
- App 回前台时二维码弹窗会自动重摆（新加的 `foreground()`），会挡住导航 —— 脚本里先按 23 关掉它。
### 五、遥控器走不到「手机输入」按钮（2026-11-07 实机发现）

- **现象（小米电视 192.168.1.25，0.9.88 包）**：焦点停在搜索框上按**右键**，焦点**纹丝不动**。
- **根因**：Android 的 `EditText` 把 `DPAD_RIGHT` 当成「移动文本光标」**自己处理掉**了，不往外派发，
  焦点系统就没机会换到右边的控件。0.9.88 已经在搜索框上接管过「下」「上」，**「右」漏了** ——
  于是工具条上「搜索 / 历史 / 手机输入 / 续建索引」这一排按钮**用遥控器永远走不到**，
  手机输入等于废了一半（实测当时是靠触屏 tap 才打开二维码弹窗的）。
- **修法（0.9.89）**：搜索框的 `setOnKeyListener` 接管 `DPAD_RIGHT` → `nextFocusRightId`（算不出就退回
  `searchButton`）requestFocus。
- 🔴 **别把「监听器」和「有没有搜索结果」绑在一起**：0.9.88 为了避免「钉死的落点指向一张不存在的卡」，
  在没有结果时把 `setOnKeyListener(null)` 整个撤掉 —— 结果刚打开搜索页（还没输词）时右键同样到不了
  「手机输入」，而那恰恰是手机输入**唯一**的入口。
  现在：**只清 `downOverrides`，监听器留着**；「下」在没有结果时不抢焦点。
- **实测差异**：电视上 `input tap` 会**直接触发 onClick**（Palm 上只是挪焦点），走查时别把两者搞混。

### 六、会话过期不能「静默失效」（2026-11-07 定，别改回去）

- **现象**：电视上开着二维码弹窗，手机扫码打开网页。等一会儿再提交，手机上**一直转圈到超时**，
  电视屏幕上还是那张旧二维码，看不出任何异常 —— 用户既不知道码过期了，也判断不了是不是网络问题。
- **根因**：会话 TTL 到期后 `serve()` 循环退出、Socket 直接关掉。**端口一关，手机浏览器就连到了一个
  没人监听的地址**，只有等 TCP 超时才知道连不上，拿不到任何原因。
- **修法（0.9.90）**：
  - TTL **5 分钟 → 30 分钟**（core `PhoneSearchServer.DEFAULT_TTL_MILLIS`）：掏手机、开浏览器、输剧名
    常常正好踩在 5 分钟边上。
  - **过期之后端口再留 10 分钟宽限期**（`GRACE_MILLIS`）：这段时间访问旧码拿到的是
    **HTTP 410 + 中文提示**（「二维码已失效，请在电视上重新打开「手机输入」。」/「这个二维码已经用过了…」），
    而不是超时。
  - **电视上自动换新二维码**（`TvTools.expirePhoneSession()`）：会话到期就在原位置生成新码，
    用户不用自己回搜索页重新找按钮。电视在后台时不弹窗（免得弹一个看不见的对话框），只重建服务，
    等回前台 `foreground()` 再摆出来。
  - `expiredNotified` 保证过期回调只通知一次（`serve()` 的 finally 与循环里都可能触发）。
- **判据**：过期后 `curl` 旧 URL 应拿到 **410 + 中文**，不是 000/exit 7。

### 七、iPhone 上打不开这个网页（2026-11-07 定案：两个独立的原因）

**这一节之前判错过一次**（当时写的是「不是代码问题」），后来做了对照实验才发现有两个原因叠在一起。教训在下面。

#### 原因一：响应缺 `Date` 头（代码问题，已修 0.9.91）

- **现象**：iPhone 打开电视的会话页一直转圈到超时。
- **根因**：RFC 7231 要求 origin server 必须发 `Date` 头，我们一个都没发。
  curl 和 Android 的 HttpURLConnection 都不在乎，**iOS 的 CFNetwork 把缺 `Date` 的响应判成非法**，
  页面根本不显示。
- **取证办法（可复用）**：在 PC 上起**只改响应头的对照服务**，逐个在 iPhone 上打开。
  8895（电视全套头 + `Date`）能开、8896（同样头但缺 `Date`）打不开 —— 只差这一个头。
  → **以后遇到「某台设备打得开、某台打不开」的网页问题，就用这个办法：把响应头一个个拆开做对照。**
- 顺带修的：状态行以前写死 `HTTP/1.1 $code Response`（`Response` 不是标准原因短语），改成标准的
  `OK` / `Not Found` 等。
- **别再凭「curl 能通」就断定服务端没问题** —— curl 会自己补 `Date`。

#### 原因二：屏幕上那张码被**上一张码**的过期定时器换掉了（代码问题，已修 0.9.92）

- **现象**：弹窗里二维码和地址都在，手机扫了就是「无法访问此网站」；同一时刻 PC `curl` 同一地址 **200**。
- **判据（这次的决胜观测）**：盯着「屏幕上的地址」和「这个地址还通不通」两条线一起看。
  2026-11-07 22:31 生成的码（`:40897`）22:33 还是 200，22:35 屏幕上换成了 `:33347`，
  22:36 `:40897` 连接被拒 —— **会话只开了 4 分钟就被换掉，而 TTL 是 30 分钟**。
- **根因**：`PhoneSessionPolicy.attach()` 换新会话时**故意不关旧会话**（要留宽限期，让手机扫到旧码看到「已失效」提示）。
  但旧会话自己的 30 分钟定时器还在跑，一响就回调 `onExpired` → `expirePhoneSession()`，
  而它里面的 `closeSession()` 关的是**当前**挂着的会话 —— 也就是刚换上的新码。
  新码被顶掉、又生成第三张，用户手上那张从此永远连不上。
  时间线也对得上：22:05 排查时生成过一张码，30 分钟后正好是 22:35。
- **修法（别再退回）**：过期回调先认身份 —— `PhoneSessionPolicy.expire(candidate){}` 只在
  `session===candidate` 时才执行换码；`deliverPhoneQuery(query, from)` 同理，只有提交的是当前码才收弹窗。
  **凡是「会话的回调」，都要先问一句「你还是当前那个吗」，否则旧对象的定时器会拆掉新对象。**
- **教训**：`curl` 能开 ≠ 用户能开。**要复现用户的失败，得用他手上那个地址**，而不是自己新生成一个。

#### 原因二的旧结论（已撤回）

这一段以前写的是「iPhone 的入站被路由器按客户端隔离挡掉了、代码侧无解」，**这个判断是错的**。
2026-11-07 22:45 复核：iPhone 醒着时电视 ping 它**通**（4 收 2，RTT 44—120 ms），
Safari 打开新生成的码**正常显示输入页**（0.9.91、0.9.92 各验一次）。
之前那些 `Destination Host Unreachable` / `100% 丢包` / ARP `FAILED`，全是**手机锁屏进省电**时采的样：
iOS 锁屏后不回 ARP，看起来就跟被隔离一模一样。
→ **别拿 ping 判断 iPhone 的死活，要让它真打开一次页面。**

以下几条是这轮排查留下的**仍然有效**的资产，别丢：

- **网络拓扑**（root 已拿到，别再从头查）：`192.168.1.1` 是 x86 iStoreOS，**没有无线**，
  br-lan = eth1 + eth2，DHCP 由它发；Wi-Fi 由 `192.168.1.2`（小米 BE36「MIFI」，2.4G `wl1` ch6 / 5G `wl0` ch40）
  和 `192.168.1.177`（Redmi AX1800「MIFI-2F」）两台 AP 提供。
  三台无线终端的 MAC **都学在 iStoreOS 的 eth2 后面**，它们之间的流量不过主路由：
  `nf_call_iptables=0`、没有 ebtables、nft 无 bridge 表 —— **主路由不可能背锅，别在 .1 上翻防火墙**。
- **认 AP 的方法**：电视 `iw dev wlan0 link` 拿到的 BSSID 与 AP 的 MAC 只差 +2
  （`50:4f:3b:ec:50:86` ↔ `192.168.1.2` 的 `50:4f:3b:ec:50:84`），据此可以确认终端挂在哪台 AP。
- **小米路由器管理接口**（两台密码不同，`192.168.1.2` = `MEnbjs1989`，`192.168.1.177` 未拿到）：
  登录要 `nonce = 0_<mac>_<epoch>_<rand>` + `sha256(nonce + sha256(密码 + a2ffa5c9be07488bbb04a3a47d3c5f6a))`，
  POST `/cgi-bin/luci/api/xqsystem/login` 拿 `token`，之后 `/cgi-bin/luci/;stok=<token>/api/...`。
  有用端点：`misystem/status`（终端流量，`online` 是秒数）、`misystem/device_detail?mac=`、`xqnetwork/wifi_detail_all`（SSID/信道/`bsd`）。
  注意 `misystem/status` 只列有流量的终端，**不是完整的关联表**。
- **`nc -L` 裸 TCP 探针**（`toybox nc -s <ip> -p <port> -L sh -c 'cat >> /data/local/tmp/hit.txt'`）：
  它**只收不回**，浏览器打开必然转圈 —— 那是预期行为；看 hit 文件里有没有那条 UA 才算数。
- **别用 `/proc/net/tcp` 判断会话端口死活**：Android 把应用自己的 socket 从这张表里滤掉了，
  活着时也查不到。要验就 `curl` 打一下。

### 八、监听器必须装在 `displayed.isEmpty()` 分支之前（0.9.89 的教训）

- 0.9.89 把搜索框的 `setOnKeyListener` 从「有搜索结果」块里挪出来，但**安装语句本身还在
  `displayed.isEmpty()` 的提前 `return` 之后** —— 搜索页刚打开、还没输词时那段代码根本走不到，
  监听器压根没装上，右键仍然到不了「搜索 / 历史 / 手机输入」。
- **现在**：监听器装在空结果分支**之前**，结果第一条用 `var firstSearchCard` 往下传；
  右键落点走候选链（`nextFocusRightId` → `searchButton` → 工具条整体），挨个试 `requestFocus()`。
- **通用教训**：解耦「监听器装不装」和「有没有搜索结果」时，**安装点要提到所有提前 return 之前**，
  别只把代码挪出 if 块。

## 播放页触控（0.9.83 起）

- 四类手势：**单击画面**切 HUD 显隐、**双击画面**播放/暂停、**左右拖**跟手拖进度（实时预览目标时间，**松手才跳转**）、
  **上下拖**调系统媒体音量（`STREAM_MUSIC`，与电视音量键同一条路）。
- 接入方式是 **Activity 层拦截**：`MainActivity.dispatchTouchEvent` 里接管，**`dispatchKeyEvent` 一行不动**
  （遥控器与触控并存，互不干涉）。判定机在 core `TouchGesture.kt`（纯 Kotlin，不依赖 Android SDK）。
- 常量都在 core companion，**别改**：`SLOP_DP=8f`、`SECONDS_PER_DP=0.5f`、`VOLUME_STEP_DP=24f`、`DOUBLE_TAP_MS=300L`。
- **HUD 按钮的放行是「整轮生效」**：在 `ACTION_DOWN` 那一刻决定这一轮归谁。
  命中 HUD 可点击按钮 → 整轮都不插手（否则点「播放」会先变成一次单击把 HUD 关了，按钮根本没被点到）；
  手指按下后移出按钮范围也不改判（半路抢走会让 core 处于半截状态，算出乱七八糟的目标位置）。
- **Palm 实机坐标映射（2026-11-04 实测）**：App 窗口横屏 1280x720，但显示 rotation=0 竖屏 720x1280，
  **App 的 x 轴 ↔ 物理 x 轴、y 轴 ↔ 物理 y 轴（无 90° 旋转）** —— 画面正中 = **物理 `(360, 640)`**。
  （电视端原生横屏，点画面正中同样是 `640 360`。）
- **换算**：Palm density 360 → **1dp = 2.25px**。横滑位移秒数 = `px / 2.25 × 0.5`；
  音量档数 = `px / 2.25 / 24`（向零取整）。实测：200px → +45s；400px → ±7 档。
- **播放中 uiautomator dump 拿不到空闲状态**（进度条/弹幕持续刷新 → `could not get idle state`，树是残的）：
  **先 `input keyevent 23` 暂停**，画面静止后再 dump，HUD 文字与播放时间都读得到。
- **物理竖屏顶部那条是状态栏/通知栏**：在那里做纵向滑动会把通知栏拉下来，之后 tap/swipe/keyevent 全落空。
  纵向滑动一律用下半屏（y 300~1100）；恢复：`KEYCODE_WAKEUP` + `wm dismiss-keyguard` + `cmd statusbar collapse`。
- 实机验收记录：`docs/PLAYER_TOUCH_VERIFICATION.md`（五项手势 + 遥控器无退化，2026-11-04 Palm 全通过）。

## 播放源取址（2026-11-02 站方变化，别改回去）

- 站方 `novel/player/multi_video_model/v1/` 从 **2026-11-02** 起**重新要求签名**：
  带注册身份但不带六个签名头的请求，会被回成 HTTP 200 + **0 字节**。
  目录、搜索、详情照常，只有「播放」全废 —— 表现是每集只放 10 秒试看片段
  （`hongguoduanju.com/video/public/<id>/360p.mp4`，约 1.2MB，360p），然后报「播放失败」。
- **现在的请求形态**：注册身份 + `Signer.sign` 的六头签名
  （X-Argus / X-Gorgon / X-Medusa / X-Khronos / X-Ladon / X-Helios），
  query 由 `Signer.encode` 生成（带 `ts` 秒 + `_rticket` 毫秒）。core `ContentRepository.registeredStream`。
- **一个注册身份只能问一次地址**（2026-11-05 实测：同一身份连续问 10 次，第 1 次成功、其余 9 次全空；
  重新注册后立刻成功）。所以身份**用一次就扔**：`takePlaybackIdentity()` 取出后立刻把 `registeredIdentity` 置空；
  拿到空体就重新注册再来一轮（`signedPlaybackWithFreshIdentity`，最多两轮）。
- **主域对本客户端回空体，备用域 `api5-normal-hl.fqnovel.com` 才带答案**
  （HTTP 200 / 24406 字节 / `code=0`，`data[vid].video_model`）。两个域各自签名（签名覆盖 query），
  空体就换域重发 —— 主机表在 `ContentRepository.PLAYBACK_HOSTS`。
- 对照实现：`sunnysky123/hongguo-web` 的 `server/src/service/app-source.js` 记着同样三件事
  （2026-10-27 改用注册身份、2026-11-02 重新要签名、空响应换备用域并重注册身份）。
  他们的纯 JS 签名有 branch 0 状态推进的坑（约 1/3 签名无效），**别照抄那段 hack** —— 
  我们的 `Signer` 实测签名有效。
- 排查「接口回空体」的顺序：① 是不是同一个身份问了第二次；② 是不是主域在挑这个客户端；
  ③ 最后才动网络层（**别默认换 PassWall 节点**）。
- 验证手段：`--play=real-drama` 端到端（目录→详情→签名取址→真读 1MB 媒体字节），
  成功/失败数看 `kotlin-tv\probe-report.txt`（UTF-8，每次覆盖）。

### 「又播不了」先分清是哪一边（2026-11-05 定）

用户说「播放不了」时，**先跑探针、再看实机，不要直接改代码**：

1. **跑探针**（`daily-source-check.ps1` 或 `--play=real-drama`）。探针绿 = 站方和我们的代码都没问题，
   问题在**电视上的包是旧的**；探针红 = 站方又变了，回到上面那节做对照实验。
2. **查 Palm 上的版本**（`adb shell dumpsys package com.hongguotv.nativeapp`）。Palm 是替身机，
   装了新版就等于验证过新包本身没坏。
3. **查电视上的版本** —— 电视上没有 adb over network（`192.168.1.26:5555` 直接拒绝连接），
   推装服务 `192.168.1.26:10000` 常年掉线（10000~10030 全不通），**只能靠用户看**：
   电视上打开 App → 设置 → 「版本与更新」，或看更新弹窗里显示的版本号。
4. **判定**：电视上 versionCode ≤ 9083 = 旧包，**必然播不了**（11-02 之后旧包走的是
   「注册身份 + 无签名」形态，站方回空体 → 回落网页门 → 只剩 10 秒试看片）。
   这种情况的正解是**升级电视上的 App**，不是改 core。

### 电视升级到新版的四条路（按可靠性排）

1. **App 内自动更新**（最省事）：电视上打开 App 停在前台，每 6 小时查一次
   （`AppUpdater.kt` 的 poll 间隔，首次进入有延迟）。线上 `releases/latest/download/update.json`
   已是 0.9.84/9084，匿名可取。设置 →「版本与更新」可手动触发。
   - 注意：**点「稍后」会把该版本推迟一天**（`deferred_code`/`deferred_until`），
     推了更新没提示多半是这个。
   - Android 8 没有静默安装权限，下载完仍要在系统弹窗里确认一次。
2. **推装服务**：`scripts/push-to-tv.ps1`（先探活 `/api/info`，再推，再等电视上出现目标版本）。
   **服务掉了要用户自己去投影上开**，不要在后台无限轮询。
3. **电视浏览器手动装**：电视上有 TV Bro（`com.phlox.tvwebbrowser`），
   用本机 HTTP 服务（端口用 **8899**，8000 被 Windows 占着）把 APK 挂出去，
   在电视浏览器里下载后安装。
4. **ADB over network 直装** —— 🔴 **2026-11-07 实测：这条路在小米电视上是通的，而且最省事。**
   电视 `192.168.1.25:5555` = **小米电视 MiTV-ASTP0（Android 9 / API 28 / 1920x1080）**，adb 连得上，
   `adb install -r <CI出的正式APK>` 一次成功（0.9.86 → 0.9.88 覆盖安装，收藏与设置都保留）。
   - **别把「电视没开 adb over network」当成通用结论** —— 那说的是 `192.168.1.26`（投影/另一台设备），
     `192.168.1.25:5555` 直接拒绝连接（10061）。**先扫一遍 `192.168.1.25:5555`，通了就用它。**
   - 装之前先 pull 已装包验证书指纹：`apksigner verify --print-certs`，与 CI 出的包一致才能覆盖安装。
   - 装的是 **CI 出的 release 资产**（不是本地手工包）：本地包和 CI 包 sha256 不同但**证书指纹一致**，
     能互相覆盖。核对「能不能覆盖」看证书，别比 sha256。

### 推装服务的老规矩（别再踩）

- 推之前**必须先 `GET /api/info` 探活**，否则表现成「上传失败」而不是「服务没起」。
- 掉线后等 1~6 分钟通常能自己回来，**值得等**（`autoStart: true`）。
- 端口关着时 10000~10030 都不通、电视 ping 得通 —— **电视活着 ≠ 服务活着**。
- 电视上 8080 会开着但**不响应 HTTP**（是别的服务，别误认成推装服务）。
- 🔴 **2026-11-05 实测**：不只是服务没开 —— `192.168.1.26` 这台设备**整台不在局域网里**
  （ARP 表查不到、ping 回 "Destination host unreachable"）。全段扫 `10000` 端口 +`10.20.0.0/24` 都没有。
  遇到「扫不到」先分清是**设备没连这个路由器**还是**服务没起**：用 ARP 表和全段 ping 确认设备在不在，
  别反复探同一个死 IP。
- 排查用得到的两张网：本机有 `192.168.1.11`（家宽路由器 192.168.1.1）和 `10.20.0.2`（另一台路由器 10.20.0.1）。
  **两张网都要扫**，设备可能换到另一张。
- 路由器 SSH（192.168.1.1:22）开着但**没有免密凭据**，`/api/dhcp/leases` 之类接口 404/403 ——
  拿不到 DHCP 租约，只能靠 ARP + ping 推断设备位置。

## 播放取址：批量 + device_level（2026-11-06 定，别改回去）

这一节的根因是 **站方用 `biz_param.device_level` 决定它发什么编码**，跟签名、跟身份都无关。

- **`device_level=3` → 站方发 ByteVC2**（`gear_des_key` 里写 `2:bytevc2`，抓前 256KB 实测
  容器 fourcc = `bvc2,mp4a`）。**电视解不了**，表现为「有地址、有时长、一播就黑/报错」。
  之前的代码硬编码 `device_level:3`，所以每一集都取到不可播的地址 —— 这才是「又播不了」的真正原因。
- **`device_level=0` 和 `device_level=1` → 站方发 ByteVC1**（`gear_des_key` 里写 `1:bytevc1`，
  实测容器 fourcc = `hvc1,mp4a`，**就是真 HEVC**，电视能硬解）。
  实测同一集《悍妇边军第一季》EP `7702043421581511704`：level=3 给 5 条 bytevc2；
  level=0 / level=1 给 bytevc1 + `hvc1,mp4a`。**两个值答案一样**。
- 生产代码固定用 **`ContentRepository.PLAYBACK_DEVICE_LEVEL = 1`**（批量体和单集体都用它）。
  🔴 **别改成 3**，也别以为换 `use_os_player` / `prefer_codec=h264` / `video_codec_type=1` /
  `detail_page_version=1` 能绕开 —— **实测这些参数在 level=3 下全都无效，仍给 bytevc2**。
- **判据必须看真实容器 fourcc，不能信 `gear_des_key` 标签**：抓媒体前 256KB 读 moov，
  看 `bvc2` 还是 `hvc1`。标签说是 bytevc1 但实际是 bvc2 的情况也存在。

### 一次注册换一整批剧集（15 集）

- **一个注册身份只能问一次地址**（见上一节）。逐集取址 = 每集一次注册 → 必然触顶
  `device_register` 的限流（`device_id=0` / `install_id=0`）。
- 解法是 **批量取址**：一次注册 + 一次签名请求，body 用
  `{"biz_param":{...},"mixed_video_id_map":{"1004":["<id1>",...]}}`，一次最多 **15 集**。
  回答 160KB，含全部 15 集的 `video_model`。
- **站方硬上限 20**：超过报 `code:100001`、`debug_info:"video_id list len over limit:20"`。
  生产取 **15**（留余量）。
- **省不掉注册**：自造身份批量直接 0/12（站方按身份查设备）。方向只能是「少注册」。
- 取址结果进 **地址缓存**：TTL 2 小时、上限 120 条（`ContentRepository` 的地址缓存）。
  命中缓存的集耗时 0ms。

### 注册被拒的长退避

- `device_register` 返回 `code != 0` 或 device_id/iid 为空 = **被限流**，不是网络故障。
  这时**不要立刻重试**（只会加深限流），走 **10 分钟长退避**
  （`PLAYBACK_REGISTRATION_RETRY_MS`，普通失败仍保留 5 秒窗口，两者别搞混）。
- `PlaybackDevice.register()` 抛 `PlaybackRegistrationRefusedException`，
  core 转成 `RegistrationUnavailableException.reason`，App 把 reason 显示给用户（别只写「播放失败」）。
- 排查顺序：① 是不是同一个身份问了第二次；② 是不是主域在挑这个客户端；③ 最后才动网络层
  （**别默认换 PassWall 节点**）。

### 生产链路实测基线（2026-11-06）

- 窗口探针：**ok=15/15，registrations=1，elapsedMs≈1671**；EP1 真读 262144 字节，
  EP2~15 全部命中缓存（0ms），quality 全 1080P，fourcc 全 `hvc1,mp4a`。
- 0.9.86 / 9086 正式包已出（Palm 实机：HEVC 硬解正常，首帧 445ms）。
- **0.9.86 电视端也已实机确认**：投影 XGIMI TV 上用推装服务装上 CI 包（versionCode 9086，覆盖掉 0.9.85-dbg），
  《悍妇边军第一季》**在投影上正常播放** —— 电视端的 `device_level=1` + 批量取址 这条修正确实闭环。
  推装服务仍是老规矩：先 `GET /api/info` 探活，装完 `/api/launch` 顺手拉起 App；
  服务掉线时电视依然 ping 得通但 10000 端口不通。
## server/（Node bridge）播放取址已失效（2026-11-06 实测）

`server/` 是保留的 React Native 版内容服务（Node bridge）。**Kotlin 独立版不依赖它**，
但仍在用旧方式部署的人，它的**播放取址链路已经彻底失效**。

- 失效点**不在 `device_level`，在签名 + 设备身份**：`server/vendor/hongguo.cjs` 用纯 JS 签名
  （`x-gorgon` / `x-argus` / `x-medusa` / `x-khronos` / `x-ladon` / `x-helios`），
  且 `DEVICE`（第 28-36 行）是**写死的** iid/device_id 常量，**没有 device_register 步骤**。
- 2026-11-06 实测（悍妇 EP1 `7702043421581511704`，用 vm 加载 vendor 直接调 `requestVideoModel`）：
  **level=3 / level=1 × 主域 / 备用域 四种组合 100% 失败**，报 `video_model failed: 未知异常`。
  即先卡在签名/身份，根本走不到编码选择那一步。
- 🔴 **别只把 `server/vendor/hongguo.cjs:441` 的 `device_level: 3` 改成 1**：签名先失败，改了也无效
  （实测 level=1 同样「未知异常」）。纯 JS 签名还有 branch 0 状态推进的老坑，别照抄那段 hack。
- 浏览侧（首页 / 搜索 / 详情）走网站 `hongguoduanju.com`，**不依赖这套签名，仍可用**；
  只有 `/api/episodes/:id/play|stream` 这一段坏了。
- 要修就得把 Kotlin 的 `Signer` + `ContentRepository`（注册身份 → 六头签名 → 批量取址）
  重写进 Node，工作量大。**当前交付路径是 Kotlin 独立版**（`kotlin-tv/`），它不连这个服务。
- Kotlin 侧生产值是 `ContentRepository.PLAYBACK_DEVICE_LEVEL = 1`，见上面
  「播放取址：批量 + device_level」。`kotlin-tv/core/.../Probe.kt:101,431` 里两处硬编码的
  `device_level:3` 是**探针对照组**（裸签名请求），不在生产路径上。




## 播放取址接口变更（2026-11-10 定，别改回去）

站方把播放地址的 **POST 取址族关掉了**，改走官方 App 现在用的那条 GET 路。核心
`ContentRepository` 里现在是**新门第一、老三门回退**。

### 一、POST 族已死（不要再试）

- `POST /novel/player/multi_video_model/v1/`、`POST /novel/player/video_model/v1/`
  （body 顶层 `video_id`）：**带签名一律 HTTP 200 + content-length 0**，空答里没有错误码，
  响应头 `server: volc-dcdn` —— 是站方的**软拦截**（不是超时、不是拒绝）。
- **国内直连下也这样**，所以跟网络、跟 Passwall 节点都无关；上游 sunnysky123/hongguo-web 的
  JS 实现跑下来也是同一个空答。
- 身份参数矩阵（app_name / version_code / device_level / cookie / cdid / UA 变体）全试过，
  全是 0 字节。**别再花时间重试这条族。**

### 二、现行可用的新门

    GET /reading/bookapi/video_tab/multi_video_model/v1/
        ?video_id=<集 id>&video_id_type=0&device_level=1
        &need_all_video_definition=1&need_mp4_align=0&use_os_player=0
        &video_platform=1024&source=0&from_video_id=

- **参数全在 query 上，body 是空的**。少一个参数站方回 `100103 PARAM_INVALID`。
- **六个签名头照旧**；body 为空时 body 的 md5 记 **16 个零字节**（与站方 App 一致）。
- **UA 用 `VendorConstants.VIDEO_UA`** —— 站方靠它把视频请求和其它请求区分开，换 UA 就 0 字节。
- 回答形状：`code=0`，约 23 KB，模型在 `data.video_model_data[<集 id>].video_model`，
  **五档清晰度内联在回答里**，不用再取第二个地址。

### 三、身份是一次性的（最容易踩）

- **同一个注册身份第二次取流就回 0 字节**。实测同一身份连取十次，只有第一次有回答。
- 所以**每一集都要新注册一次身份** —— 代码沿用既有的 `takePlaybackIdentity()`「用掉即弃」语义。
- core 里 `tabVideoModel` 循环 attempt 0..1，每次都调 `takePlaybackIdentity()`，
  两次空则抛 `EmptySignedAnswerException`（IOException 子类，消息「播放接口返回空内容」）。

### 四、全档 ByteVC2 的集：换 codec_type=3 再要一次备用地址

有一类集的**内联模型和它的备用地址都只给 ByteVC2**（电视解不了的编码）。
实测（漫剧《重生后我给emo修士当狗》第一集 7703473857562151961）：

| codec_type | 给回来的编码 |
|---|---|
| 不带 / 0 / 1 / 2 / 4 / 5 | 只有 bytevc2（电视放不了） |
| **3** | **bytevc1（HEVC，电视能放）** |

`episodeStream` 里内联档位全不可播时，用 `codec_type=3` 的备用地址再取一次。

### 五、门的顺序（别改回去）

- 新门 `tabStream` 第一；答得出来 → 直接播，**一个老门都不问**。
- 新门空答 → 老三门（注册身份门 → 签名 POST 门 → 公开页）整体搬进 `streamDoorsLegacy` 回退。
- `fallbackCannotHelp = isNetworkFailure || isCancellation || isRegistrationUnavailable` 时**不回头**。
- 公开页（hongguoduanju.com）只有 10 秒试看，只当故障证据与最后退路。

### 六、实机基线（2026-11-10，投影 192.168.1.26 / Android 8.0 / 1920x1080）

- 0.9.93（9093）覆盖安装 0.9.91 成功，同签名，本机记录保留。
- **《小江神豪》**：从第 63 集播到**第 67 集 00:11** —— 连续四集都重新注册身份、重新取址，全部成功。
- 全新一集《觉醒伪神》第 1 集正常播放，回首页进度已到**第 2 集 01:01**。
- 判据：`dumpsys media_session` 里 `state=3`；播放页 dump 无「失败/重试」节点；两帧截图平均通道绝对差 70。
- 验证完必须 `am force-stop com.hongguotv.nativeapp`。

### 七、取证教训

- **别拿「同一台机器别的功能都正常」当结论**：目录/详情/搜索全好，说明网络没问题，
  但不代表取址那条路还开着。取址接口是**单独**被关的。
- **空答 + 200 + `server: volc-dcdn` = 站方软拦截**，看到这个组合就去找新门，别调网络层。
