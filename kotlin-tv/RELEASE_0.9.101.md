# Kotlin 0.9.101：取址门同时发两套形态，换号加冷却不再自烧配额

<!-- notes -->
一、站方 11-20 上午把取址这一族门整体软拦截了（HTTP 200 + 0 字节），与请求形态无关。
这一版让 tab 取址门同时发两套形态（上游现行形态 + 我们自己那套 551 Argus），不再赌单一形态。
二、同时把换号节奏改成「两道门都空答才换、两次换号之间至少隔 30 秒」——
原先一集最多换 8 个号，一次检测 16 集就能把站方发号配额打爆，
配额用光之后剩下的集数直接「注册被拒」+ 10 分钟退避，整个 App 十分钟放不了。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9101。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 0.9.100 及更早版本并保留本机收藏、观看记录和设置。

## 现象

2026-11-20 14:00 的每日检测报异常：real-drama 8 集、comic-drama 8 集全部失败。
前 6 集是 `SampleClipException: 只取到 10 秒试看片段，不是完整剧集`（取址门空答，
降级链一路退到公开页），后 2 集是 `RegistrationUnavailableException`（站方没发号）。

## 第一层根因：站方整体软拦截取址门（不在我方代码）

- 所有取址门一律回 **HTTP 200 + 0 字节**，`server: volc-dcdn` —— 与 11-11 签名收紧、
  11-16 接口关闭同一个长相。目录页 / 详情页 / 搜索全部正常，所以不是网络、不是节点。
- **同一份代码 03:37 跑还全绿，14:00 就全红** → 收紧发生在 11-20 上午。
- **与请求形态无关**：一次注册、同一身份，把九种形态全试了一遍 —— v5 门（上游形态，
  带 / 不带会话 cookie、完整 profile）、tab 门（完整 profile + PLAYER_UA，带 / 不带 cookie、
  最小字段、VIDEO_UA）、上游 0.16.10 的主力单集 POST 门（两个域）—— **全部同一个空答**。
- **旁证**：上游 N3urda/hongguoTV 当天同样中招（issue #81「0.16.10 并未修复空响应」、
  #82「0.16.10 返回为空」、#83「最新版播放不了」）—— 他们 11-19 发的 0.16.10 也没解决。
- 站方在 **2026-11-21 03:01** 恢复放行，这一版的检测随即转绿。

## 第二层根因：我方的换号节奏会烧光站方的发号配额（真缺陷）

原先 v5 取址门一轮空答就换一个身份，一集最多换 8 个（`PLAYBACK_TAB_MAX_IDENTITIES`）。
换号 = 一次注册 = 一次配额，站方的配额很紧：一次检测 16 集最多能烧掉 128 次注册，
实测第 30 集起注册就被拒（`RegistrationUnavailableException` + 10 分钟退避）——
表现从「有几集放不了」变成「十分钟都放不了」。11-20 那天 16 集里最后 2 集正是这样挂的。

## 改了什么

1. **tab 取址门同时发两套形态**（`ContentRepository.tabVideoModelOnce()`）：
   先发上游 0.16.10 的现行形态（完整设备 profile + `Signer.signPlayback` 签名 +
   `PLAYER_UA` + 注册时下发的会话 cookie），再发我们自己那套 551 Argus 形态
   （11-20 03:37 还在答的那一形态）。不赌单一形态，站方按哪种口径放行都能接住。
2. **换号改成链级、带冷却**（`streamDoors()` + `rotatePlaybackIdentity()`）：
   v5 门空答只往 tab 门走，**两道门都空答**才换号；一集最多 `PLAYBACK_IDENTITY_ROUNDS` = 2 个身份；
   两次换号之间至少隔 `PLAYBACK_IDENTITY_ROTATE_COOLDOWN_MS` = 30 秒（与上游同量级）。
   老门丢号也走同一道冷却闸门。
3. **会话 cookie 各归其位**：注册响应下发的 `store-region` / `install_id` / `ttreq`
   随身份存着（`PlaybackIdentity.cookie`），tab 门带上、v5 门不带（上游就是这样）。
4. 诊断埋点全部撤干净，探针 jar 里也扫不到关键字。

## 验证

- **探针**：real-drama 8 集、comic-drama 8 集全部成功、失败 0，每集实读 1048576 字节媒体字节、
  画质 1080P（2026-11-21 03:01 的检测）。
- **单测**：`:core:test` 422 项全绿（新增 `anEmptyV5DoorDoesNotBurnTheRegistrationQuotaByItself`、
  `theIdentityIsNotRotatedAgainWhileTheCooldownLasts`，并按新策略改了换号相关的两个用例）；
  `:app:testDebugUnitTest`、`:app:lintRelease` 全绿。
- **诊断代码已撤干净**：`git diff` 里没有 `[DIAG-TEMP]` 残留，探针 jar 解压 172 个 class 扫不到该关键字。

## 没动的东西

弹幕几何/密度/透明度、播放页手势与常量、焦点落点、主题文案、取址签名本身
（真签名、query 编码、`cdid`）、身份跨集复用、地址缓存 TTL 2 小时 / 上限 120 条，
以及 `server/` 那套纯 JS 签名链路 —— 全部原样保留。
