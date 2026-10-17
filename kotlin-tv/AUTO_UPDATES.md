# 应用内更新与发布链路

> **当前状态：自动更新已打通，发布链路也已改为在本仓库直接发布。**
> 2026-10-16 起，本仓库 `miaoxiaohu/hongguoTV` 已从**私有改为公开**。
> 这是此前一直失效的根因：GitHub 对私有仓库的 release 附件对未登录请求返回 404，
> 而电视端不带任何 GitHub 凭据，因此 `update.json` 永远取不到。
> 仓库公开后匿名访问即返回 200，`update.json` 的全部字段也都能通过
> `AppUpdates.parse()` 的严格校验。
>
> 2026-10-17 起发布侧一并整理：取消「同步到另一个公开仓库」的双仓库模式，
> 改为**打 `kotlin-vX.Y.Z` tag 直接在本仓库签名构建并发布**；
> 版本号由 tag 派生、签名断言对齐当前证书、CI 恢复在 `main` 上生效。
>
> 下面的说明分三部分：**现在能用到什么程度**、**校验设计**、**怎么发版**。

## 一、现状

### 1.1 地址指向与仓库可见性：已修好

| 位置 | 指向 | 电视能否拉到 |
|---|---|---|
| `core/.../AppUpdates.kt:21` `REPOSITORY` | `miaoxiaohu/hongguoTV` | ✅ 已公开，匿名 200 |
| `core/.../AppUpdatesTest.kt:52`（注释） | 同上 | ✅ |
| `scripts/sync-kotlin-public.py:11,29` | `N3urda/hongguoTV-updates` | ❌ 该仓库零 release，脚本目标已过时 |
| `kotlin-tv/README.md:10`（下载链接） | 已改指本仓库 | ✅ |

这里有两个先后踩的坑，值得记下来：

1. 提交 `a89e6d6`（2025-10-14）把 `AppUpdates.REPOSITORY` 从上游改成了本仓库，
   对应 release 名为「修自动更新通道」。**但根因不是地址写错而是仓库私有，那次修复并没有真正打通。**
2. 2026-10-16 把仓库从私有改为公开，通道才真正生效。

实测（2026-10-16，转公开后）：

```
GET .../releases/latest/download/update.json                                        → 200
GET .../releases/download/kotlin-v0.9.9/update.json                                 → 200
GET .../releases/download/kotlin-v0.9.9/hongguotv-kotlin-0.9.9-...-android8.apk     → 200
```

线上 manifest 逐条通过 `AppUpdates.parse()` 的断言：`schema=1`、包名 `com.hongguotv.nativeapp`、
`versionCode=18`、`minSdk=26`，`tag`/`fileName`/`versionName` 三个正则、`sha256` 格式与 `size` 范围全部合规。

### 1.2 版本号：已统一为 tag 派生

**规则**：`versionName` 就是 tag 里 `kotlin-v` 后的部分，必须是 `X.Y.Z`；
`versionCode` 由版本号派生，**任何环节都不允许手填另一个数字**。

```
versionCode = major × 1,000,000 + minor × 1,000 + patch
```

例：`kotlin-v0.9.10` → versionName `0.9.10`、versionCode `9010`。

这个映射保证了三件事：

- **与线上已有产物兼容**：线上 0.9.6–0.9.9 用的是 15–18，新规则从 9009 起，远大于 18，
  电视上装过旧版也能正常收到升级提示。
- **单调且可复现**：同一个 tag 无论重跑多少次都算出同一个 versionCode，
  同一次发布的重跑不会换成另一个版本号。
- **不重叠**：minor 与 patch 各占 3 位，上限 999，`release.yml` 和
  `publish-tv-update.py` 都会拒绝越界的版本。

`build.gradle.kts` 的本地默认值已改为 `versionCode=1` / `versionName=0.0.0-dev`。
这是**故意压到极低**的：`AppUpdates.kt:16` 按 versionCode 严格递增比较，
本地默认若填成 22/23 这种看起来合理的数字，电视装了本地包后会认为「已是最新」，
从此再也不提示更新——本地开发包理应永远低于任何已发布版本。

手动发版时传两个环境变量即可（`scripts/build-kotlin.sh` 会读取）：

```sh
HONGGUOTV_VERSION_CODE=9010 HONGGUOTV_VERSION_NAME=0.9.10 ./scripts/build-kotlin.sh
```

### 1.3 签名断言：已对齐当前证书

线上 release 使用的证书实测为：

```
CN=HongguoTV Local Build, OU=Local, O=Local, L=Local, ST=Local, C=CN
SHA-256: 4f4cf502b9ea2b2cccada54ab62031de1ef4dc9aecdf3addbcf4099dd853cf08
```

`publish-tv-update.py` 里原先断言的是 `DELIVERY.md:14` 记录的 0.2.1 时期证书
`3579f7a8…`，执行必然失败。现已改为上面的 `4f4cf502…`，并作为常量固定在脚本顶部，
注释写明「改了它用户就得卸载重装、丢掉本机记录」。

### 1.4 CI：已在本仓库 `main` 上生效

- `kotlin-native.yml` 监听 `main` 与 PR，跑 `:core:test` + `:app:lintDebug` + `:app:assembleDebug`。
  它不再监听 tag，因为 `release.yml` 在发布构建里已经把这两项作为质量门禁。
- `release.yml` 由 `kotlin-v*` tag 触发，负责签名构建与发布。
- `kotlin-public-sync.yml` 已停用（见下文「发布链路」）。

`kotlin-tv/DELIVERY.md:7` 与 `COMIC_SPEED_VERIFICATION.md:3` 里的
`codex/kotlin-standalone` 属于历史记录，保留了当时的样子。

### 1.5 电视端行为（参数已核对代码，仍然准确）

即使通道修好，下面这些是 `AppUpdater.kt` 的实际行为：

- 前台浏览时每 6 小时检查一次最新正式版（`AppUpdater.kt:60`），首次进入应用有延迟。
- 默认自动检查并下载，设置 → 版本与更新可关闭，也可手动检查。
- 下载失败保留当前应用，自动下载重试至少间隔 30 分钟（`AppUpdater.kt:138`）。
- 选择「稍后」推迟该版本提醒一天（`AppUpdater.kt:124`）。
- 下载完显示版本信息，选择「安装新版」。Android 8 没有静默安装权限，仍须在系统确认。
- 播放或离开前台会取消更新请求。

## 二、APK 校验设计（这部分是完好的）

`update.json` 的字段校验与下载后的流式校验都已实现，这部分无需改动：

- 下载使用 HTTPS，文件长度与 SHA-256 校验（`AppUpdates.kt` `parse` / `UpdateFiles.verify`）。
- 校验 APK 实际包名、versionCode、versionName、最低 SDK 与当前安装应用的签名。
- 版本号、包名、tag、文件名、长度、SHA-256 都有格式断言（`AppUpdates.kt:30-34`）。
- APK 写入应用私有缓存，仅临时授权系统安装程序读取。
- **APK 中不保存 GitHub 令牌**，应用也不登录 GitHub——能匿名拉取的前提就是仓库为公开；
  改回私有会立刻让通道重新失效。

## 三、怎么发一个版本

```sh
git tag kotlin-v0.9.10
git push origin main kotlin-v0.9.10
```

`release.yml` 被 tag 触发后依次做：

1. 校验 tag 形如 `kotlin-vX.Y.Z`，并从 tag 派生 `versionName` 与 `versionCode`。
2. **单调性护栏**：新版本必须严格高于当前 latest，同名或倒退直接失败退出。
   这道闸很关键——`AppUpdates.kt:16` 只认 `versionCode > installed`，
   一旦发布出比线上更低的版本号，电视会永远收不到后续更新，而且没有任何报错。
3. 从 Actions Secrets 恢复签名密钥到 runner 临时目录（`umask 077`）。
4. 跑 `:core:test`、`:app:lintRelease`、`:app:assembleRelease`——测试不过就不会发布。
5. `scripts/publish-tv-update.py` 校验证书、包名、versionCode/versionName、minSdk 后，
   建草稿 → 上传 APK / 源码 ZIP / `update.json` / `SHA256SUMS` → 逐个核对附件摘要 →
   正式发布。比 latest 更旧的构建**不会**抢占 latest 标记。
6. 无论成败都删除 runner 上的临时密钥。

已经发布过的构建被重跑时会原样保留附件，不会覆盖电视上已安装的 APK。

### 本地构建

日常开发直接用默认值即可（`0.0.0-dev` / versionCode 1）：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug
adb install -r kotlin-tv/app/build/outputs/apk/debug/app-debug.apk
```

要手工复现某个线上版本，传入该 release `update.json` 里的两个值：

```sh
HONGGUOTV_VERSION_CODE=9010 HONGGUOTV_VERSION_NAME=0.9.10 ./scripts/build-kotlin.sh
# 产物：out/hongguotv-kotlin-0.9.10-android8.apk
```

### ⚠️ 改版本号后必须 clean 重建

Kotlin 会把 `BuildConfig.VERSION_NAME` / `VERSION_CODE` 当常量**内联进字节码**。
只改 `build.gradle.kts` 再直接 `assembleRelease`，`BuildConfig.java` 会正确重新生成，
**APK manifest 也会显示新版本号**，但 `classes.dex` 里可能仍是旧值——
因为增量构建判定 Kotlin 源没变，`compileReleaseKotlin` 根本没重跑。

后果不只是显示错：应用内显示旧版本名，且 `AppUpdater.kt` 的
`update.newerThan(BuildConfig.VERSION_CODE)` 会拿旧值比较，**更新提示永远不触发**，全程无报错。

`aapt dump badging` 查不出这个问题（它只看 manifest）。因此：

- 改过版本号后请先 `./kotlin-tv/gradlew -p kotlin-tv :app:clean` 再构建；
- 推给电视或上传 release 前，跑一遍 `scripts/publish-tv-update.py` 的
  `dex_carries_version()` 检查，它会在 APK 的 dex 里确认版本号确实存在。

CI 每次都是全新 checkout、不存在增量缓存，所以云端构建不受影响；**这个坑只咬本地发版**。

## 四、双仓库模式已停用

原设计是「私有源码仓库 → 同步到公开更新仓库 → 在那里构建发版」，只为让**私有**源码
也能发公开 APK。仓库公开后这套流程没有存在理由，且它的目标仓库
`N3urda/hongguoTV-updates` 从未有过任何 release。

- `.github/workflows/kotlin-public-sync.yml` — 改为只保留 `workflow_dispatch`，
  手动触发会直接报错并指向 `release.yml`。文件保留以便回退。
- `scripts/sync-kotlin-public.py` — 逻辑原样保留，仅在文件头注明已停用。
  其 `line 11` 的白名单仍写着 `N3urda/hongguoTV-updates`，**启用前必须改**。

若将来要把源码改回私有：另建一个**公开**的 release-only 仓库，改白名单，
把 `AppUpdates.REPOSITORY` 指过去，并配置 `TV_UPDATES_DEPLOY_KEY` 与 `TV_UPDATES_ENABLED`。

## 五、签名与 Secrets

配置动作需仓库所有者授权。以下 Secrets 配好后 `release.yml` 才能跑通：

- `HONGGUOTV_KEYSTORE_B64`：与现有 release 同一 keystore（证书 `4f4cf502…`）的 Base64 编码。
- `HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_KEY_PASSWORD`：对应签名配置。

签名私钥只在 runner 临时目录中恢复，任务结束删除；源码与 APK 都不包含这些凭据。

**不要改成在 APK 中嵌入 PAT。** 那等于把凭据分发给每一个安装者。
撤销自动发布只需禁用 `release.yml`；签名 Secrets 可以单独撤销而不影响代码。

## 六、边界

电视系统没有安装器、禁止未知来源或无法访问 GitHub 时，自动更新不能完成；旧版仍可继续使用。
最低 API 26 不等于 Android 8 实体电视已验收。
