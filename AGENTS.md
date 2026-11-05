# AGENTS.md

红果 TV（kotlin-tv 电视端 + server 内容服务）的工作约定。

## 源解析检测（每日 03:00）

- 检测脚本：`D:\document\project\time_task\hongguoTV\daily-source-check.ps1`
  （2026-10-29 起归置到 time_task；本仓库 `scripts\daily-source-check.ps1` 是旧副本，不再由 cron 调用）
- 定时任务：Mavis 的 cron「红果TV 源解析每日检测」，`0 3 * * *`（Asia/Shanghai）。
- **不推 Bark**（2026-11-04 起按用户要求停用推送）：脚本默认不推，异常只由 cron 会话回报一句。
  要推必须显式加 `-Push`。
  退出码：`0` 正常 / `10` 有异常（结果在脚本 stdout 的「异常明细：」段里）/ `20` 探针没编译。

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
4. **ADB**：电视没开 adb over network，这条路目前不通。

### 推装服务的老规矩（别再踩）

- 推之前**必须先 `GET /api/info` 探活**，否则表现成「上传失败」而不是「服务没起」。
- 掉线后等 1~6 分钟通常能自己回来，**值得等**（`autoStart: true`）。
- 端口关着时 10000~10030 都不通、电视 ping 得通 —— **电视活着 ≠ 服务活着**。
- 电视上 8080 会开着但**不响应 HTTP**（是别的服务，别误认成推装服务）。

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


