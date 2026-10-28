# Kotlin 0.9.70：站方改了取址规矩，这次换个身份就能拿到全部剧集

<!-- notes -->
站方把播放取址的规矩改了：旧的「带签名 + 现造一个临时身份」不再被接受，200 但返回空。0.9.70 改成先用一次匿名设备注册换一个正式身份，再拿这个身份不带签名去取址 —— 于是整部剧都能取到地址，不再只剩前 3 集。旧签名那条路留作后备。
<!-- /notes -->
最低 Android 8.0 / API 26，versionCode 9070。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.69 并保留本机收藏、观看记录和设置。

## 这一版改了什么

- **站方取址改成「注册身份 + 无签名」**。2026-10-27 起，站方 `multi_video_model/v1` 端点虽然还在、HTTP 200，但**签名 + 现造身份的请求一律返回空体**；不签名且没有注册身份时返回 `{"Code":110001,"Message":"未知异常"}`。真实做法是两步：先向 `log5-applog-lf.fqnovel.com/service/2/device_register/` 注册一个匿名设备（ttEncrypt 报文体）换到 `device_id` / `iid`，再拿这个身份**不带任何签名头**去请求播放取址，地址就回来了。
- **三道门按顺序退**：① 注册身份 + 无签名 → ② 旧签名接口（保留，站方若恢复就还能用）→ ③ 公开网页页（只有前 3 集 360p，只当兜底）。旧签名路**没删**，它是「注册身份这条路被拒」时的第二道门。
- **注册身份挂在 `ContentRepository` 实例上**，不是进程级单例：生产上一次会话注册一次；测试之间不会互相继承身份。
- **注册失败一分钟内不重试**（`PLAYBACK_REGISTRATION_RETRY_MS`），免得每次点播放都付一次注册代价。
- **两道门都判定「网络死了」才跳过公开页**（`fallbackCannotHelp`）—— 网络没了的时候再敲一次公开页只是把等待翻倍。

### 顺手修掉一个「假绿」

注册响应**没有状态字段**（真实形状是 `{"server_time":…,"device_id":…,"install_id":…,"new_user":1,…}`）。原来的实现把「字段缺失」读成「状态非 0」，于是**每次注册成功都被自己误杀** → 回落签名路径 → 再回落公开页 → 只剩前 3 集 360p。改成：**无状态字段 = 成功**；有状态字段且非 0 = 拒绝。

这条坑值得单独记一笔：测试夹具如果写成 `{"code":0,…}`，293 个测试会全绿而真机全挂 —— **夹具必须照抄真实响应形状**。

## 怎么量的

| 路径 | 改之前 | 改之后 |
|---|---|---|
| 1000 集剧抽第 1/4/50/100/500/1000 集 | 取不到（只剩前 3 集 360p） | 全部 1080P、key 16 B、Range 206、真 mp4 |
| 跨剧普适性（7 部各抽首/中/末） | — | **21/21 全通** |
| Palm 手机实机压测（2 轮 × 冷启动 + 连切） | 一次切集 3 轮自动恢复全败 | **43/43 全通**，切集延迟 avg 3 s |

实机日志里可直接读到的三句：`Episode prepared key=…:1080 elapsedMs=…`、`Playback first frame episode=…`、`Playback ready episode=…`。

**压测的 43/43 里，`UnknownHostException` / `Playback failure` / 自动恢复重试都是 0。**

## 验证记录

- `:core:test` 全绿（293 个），`:app:assembleRelease` 成功（`versionCode=9070` / `versionName=0.9.70`，由 `HONGGUOTV_VERSION_CODE` / `HONGGUOTV_VERSION_NAME` 注入）。
- **0.9.70 正式包装机复验**：`app-release.apk`（2 725 502 B）装到 Palm 后回读 `versionCode=9070 minSdk=26 targetSdk=36 / versionName=0.9.70`；冷启动进首页 → 点海报 → 连续切集，第 68–73 集全部 `Playback first frame` + `Playback ready`，取址全部落在 `:1080`，零 `UnknownHostException`、零 `Playback failure`。
- **Palm 手机（192.168.1.30）实机**：两轮压测共 43 次切集全部出首帧；首页「上次看到」从第 19 集推进到第 68 集 / 00:49 —— 是真在放，不是「看起来没报错」。
- **路由器 DNS 是这轮的真凶**：App 报的异常是 `UnknownHostException`，不是取址失败。根因在 R86S 上 Passwall 的 DNS 分流链（chinadns-ng 对 chnlist 域名同时发 `china-dns` 和 `trust-dns`，后者经代理节点，抖动叠加成 1.3–1.6 s 的尾巴）。**换上游治不了，整层停掉才行**。已在路由器上把 Passwall 整网停用并持久化（重启验证不回来），DNS 延迟从 avg 65 ms / max 1593 ms 降到 avg 3.9 ms / max 33.4 ms，手机解析 40/40 全通。
- **数据本身是好的**：解密 1080p rendition 首样本可见 `VPS(32) / SPS(33) / PPS(34) / SEI(39) / IDR_W_RADL(19)`，`hvcC` 204 B、`profile_idc=1(Main)`、4:2:0 8 bit、1920×1080@30、`general_level_idc=186`。
- **电视上的播放还没实测**：电视（192.168.1.26）不可达、无 adb over network。电视吃同一个路由器，理论上现在也受益，但没实测。

## 已知边界

- **电视端没验过**：验证时电视不可达，本轮只做了电脑侧端到端 + Palm 手机实机。
- **失败真凶是 DNS，不是链路**：手机端「播不出来」是取址主机域名解析失败（路由器 DNS 分流链不稳），已在路由器上处置。**电视用同一个路由器**，但电视上还没实测。
- **路由器上 Passwall 目前是停用状态**（`enabled=0` + 删掉自启链接），**GFW 代理同时失效**。要恢复代理又保住干净的 DNS，只能走「关 `dns_redirect`、让 DNS 留在系统 dnsmasq」这条路，本轮没做。
- 旧签名路**保留但当前不可用**：站方还在软拦它。它是「注册身份这条路被拒」时的第二道门。
- 公开直链 `hongguoduanju.com/video/public/<vid>/{360|540|720|1080|original|source|default}.mp4` 与 `*.m3u8` 实测全 404，**只有 `360p.mp4` 且只在前 3 集可用**；它值不值得接进兜底，还没定。
- 新链路的 UA 与设备参数出自 GPL-3.0 项目（tsukiyo-zero/hongguoTV `DeviceRegister.kt`），来源已记进 `kotlin-tv/NOTICE.md`。
- 站内 360p/480p/540p/720p 的采样条目是 **bytevc2**（`encv` 但无 `hvcC`/`avcC`），Android 抽不出视频轨，App 直接跳过；**只有 1080p 是 bytevc1**（`encv` + `hvcC`）能正常解码。

## 发布状态

- **已发布**：2026-10-28 按 tag `kotlin-v0.9.70` 由 CI 出包，CI 的测试、release lint 与签名校验全绿。发布页 <https://github.com/miaoxiaohu/hongguoTV/releases/tag/kotlin-v0.9.70>，附件 `hongguotv-kotlin-0.9.70-android8.apk`（2 725 502 B）、`update.json`（versionCode 9070）、`SHA256SUMS`、源码包。线上 `update.json` 的 `sourceCommit` 为 `1b4a6df`。
- **发布由 CI 完成**：tag 一推，CI 就会按本文件出包发布（版本号、签名证书、附件完整性都由 CI 校验）。本文件就是发布页正文，电视更新弹窗的短说明出自 `<!-- notes -->` 块。
- 复现发布构建时设置 `HONGGUOTV_VERSION_CODE=9070` 和 `HONGGUOTV_VERSION_NAME=0.9.70`，并使用同一发布证书。
- 完整背景与排查过程见 [站方取址接口实测](../docs/research/2026-10-28-hongguo-playback-endpoint.md)。
- 上一版的记录见 [0.9.69 发布说明](RELEASE_0.9.69.md)。
