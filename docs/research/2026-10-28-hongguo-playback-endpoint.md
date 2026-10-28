# 站方取址接口：注册身份 + 无签名（2026-10-28 实测）

实测日期：2026-10-27 ~ 2026-10-28。手段：直连站方接口打原始响应定位根因，再让 `kotlin-tv/core` 的 `ContentRepository.stream()` 走出货代码做端到端验证（真实网络 Range 拉流取证）。

## 术语：「站方」

本仓库里「**站方**」= 内容平台自己的服务端，也就是数据来源方。对应到具体域名：

| 域 | 角色 |
| --- | --- |
| `api5-normal-sinfonlineb.fqnovel.com` | 番茄小说系业务 API：播放取址、剧集清单 |
| `log5-applog-lf.fqnovel.com` | 设备注册（`device_register`），给播放接口认身份 |
| `hongguoduanju.com` | 红果官网页面：剧集清单、公开播放页（兜底） |

所以「站方那一路」= 官方接口，「网页版 / 公开页」= `hongguoduanju.com` 的网页兜底（只有前 3 集 360p）。本文讲的「站方」一律指前者。

**站方不是一直配合的。** 同一个端点被改过至少三次（签名搜索接口被弃用、`video_model/v1` 掏空、播放取址改为校验注册身份）。所以每次排查的第一问是「**站方今天答什么**」，而不是「代码有没有 bug」。

## 结论

**2026-10-27 起，站方播放取址不再接受签名请求，改为接受「注册过的身份 + 无签名」；端点本身没消失。**

- 旧路：`POST /novel/player/video_model/v1/` + 签名头 + 现造的 `device_id`/`iid` → **HTTP 200，`Content-Length: 0`**（`X-Dsa-Origin-Status: 200`、`Server-Timing: inner; dur=8`）。源站主动返回空体，不是超时、不是网络问题。
- 新路：先注册匿名设备拿到 `device_id`/`iid`，再**不带任何签名头**打 `POST /novel/player/multi_video_model/v1/` → `code=0`，每集 5 条清晰度，地址明文无加密。

已落地进 `ContentRepository`，`stream()` 现在是三道门：**注册身份 → 旧签名接口 → 公开网页页**。

## 根因：不是端点被删，是身份校验方式变了

判定依据是两组对照：

| 请求 | 结果 |
| --- | --- |
| 签名头 + 现造身份 | 200 + 0 字节 |
| 无签名 + 站方注册过的身份 | 200 + `code=0` + 完整模型 |

同一个端点、同一组参数，只换「身份是否注册过」和「带不带签名」两个变量，结果从空体变成完整答案 —— 所以是**服务端换了校验方式**，不是路径被删。

失败路径实测（都不要再试）：

- `video_model/v1`、`multi_video_model/v1`、`multi_video_model/preload/v1` 在 `b`/`a`/`api5-normal`/`reading.snssdk` 四个域名下，带签名与不带签名都一样：200 + 0 字节。
- `multi_video_model/v1` 无签名且**无注册身份** → `{"Code":110001,"Message":"未知异常"}`。
- 弹幕接口：正确路径无签名回 110001，路径少 `commentapi` 回 404 —— 两者别混。

## 新链路的请求形状

### 1) 注册匿名设备

```
POST https://log5-applog-lf.fqnovel.com/service/2/device_register/
     ?tt_data=a&cronet_version=8d40f833_2026-03-03
     &ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1
```

报文体是 ttEncrypt：`746305100000` + 32 字节随机 + AES-128-CBC/PKCS5( `sha512(gzip)` + gzip(`{"magic_tag":"ss_app_log","header":{…},"_gen_time":now}`) )，
key/iv = `sha512( sha512(random) + hex(KDF_SALT) )` 的前 16 / 后 16 字节，
KDF_SALT = `4dd4c2e6…7524` + `f49baf7f…dd58`。

头：`User-Agent: com.phoenix.read/72232 …`、`Content-Type: application/octet-stream;tt-data=a`、`log-encode-type: gzip`、`x-ss-dp: 8662`、`x-ss-req-ticket`、`x-vc-bdturing-sdk-version`、`lc`、`sdk-version`、`passport-sdk-version`、`x-neptune`、`x-ss-stub` = MD5(body) 大写。

实现见 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PlaybackDevice.kt`。

### 2) 无签名取址

```
POST https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?<DEVICE_BASE>&device_id=..&iid=..&_rticket=..
User-Agent: com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)
Accept: application/json; charset=utf-8,application/x-protobuf
Content-Type: application/json; charset=UTF-8

{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,
  "need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},
 "mixed_video_id_map":{"1004":["<vid>"]}}
```

`DEVICE_BASE` 是固定的 29 个设备参数（`aid=8662`、`version_code=71332`、`device_type=25053RT47C`、`resolution=1280*2772` …），按 App 的写入顺序排列，App 自己的百分号编码会把 `*` 编成 `%2A`。

**响应形状（实测）**：

- `data[vid].video_model` 是**一个 JSON 字符串**（不是对象），要再 parse 一次。
- 它的 `video_list` 是**数组**；`codec_type` / `definition` / `vheight` 都在 `video_meta` 里，不在条目顶层。
- `encrypt_info{encrypt:true, kid, spade_a, encryption_method:"cenc-aes-ctr"}`；`key_seed` 为空 → **地址是明文**，不需要 `decryptUrl`。
- `fallback_api` 是 JSONObject（不是字符串），只有部分答案会带；带了才需要问第二个地址。

## 最大的坑：注册响应里没有 `code` 字段

真实答案长这样：

```json
{"server_time":1793148714,"device_id":709657789759495,"install_id":709657789763591,"new_user":1,
 "device_id_str":"709657789759495","install_id_str":"709657789763591","device_token":"AAAA…"}
```

**它没有 `code`。** 之前按「`code` 不为 0 就失败」来读，把「字段缺失」读成了 `code=-1`，于是**每一次注册成功后都被当场判为失败**。表现是：注册失败 → 回落签名路径 → 签名路径 200 空 body → 回落公开页 → 只剩前 3 集 360p。这正是「只有前 3 集能播」的老症状。

**更贵的是：测试没抓到这个。** 测试夹具写的是 `{"code":0,…}` —— 一个站方**从不返回**的形状，于是 293 个测试全绿、真机全挂。教训：**这类响应形状会变的接口，夹具必须照抄实抓的原文**，否则测试只会证明夹具自己是对的。

已修：夹具改成真实形状，并补两条回归测试 ——「无状态字段 = 成功」「有状态字段且非 0 = 拒绝」。

## 实测结果

### 全剧覆盖（`ContentRepository.stream()` 走出货代码）

目标剧《和离后我带女儿横扫魔域》（`series_id=7698294694886788121`，1000 集）：

| 集 | 清晰度 | key | HTTP | Content-Range | 首字节 |
| --- | --- | --- | --- | --- | --- |
| 1 | 1080P | 16B | 206 | `bytes 0-65535/27424404` | `ftypisom` |
| 4 | 1080P | 16B | 206 | `bytes 0-65535/30130348` | `ftypisom` |
| 50 | 1080P | 16B | 206 | `bytes 0-65535/9407675` | `ftypisom` |
| 100 | 1080P | 16B | 206 | `bytes 0-65535/9542144` | `ftypisom` |
| 500 | 1080P | 16B | 206 | `bytes 0-65535/7793257` | `ftypisom` |
| 1000 | 1080P | 16B | 206 | `bytes 0-65535/15163218` | `ftypisom` |

### 跨剧普适性

7 部剧（目标剧 + 首页 6 部）各抽首/中/末 3 集，共 **21/21 全通**，全部 1080P、Range 206、真 mp4。

## 实机验证：Palm 手机（192.168.1.30）

设备：Palm PVG100（`pepito`），LineageOS 24 / Android 17，1280×720 横屏。装的是**同签名**的 release（0.9.69 / 9069），`adb install -r` 覆盖安装。

**新链路在设备上确实走通了。** 设备侧 `tcpdump -i wlan0` 抓到 `123.129.199.132:443`、`123.129.199.131:443`（取址接口）与 `222.75.36.107:443`（CDN，下行 5500 包）；App 日志 `Episode prepared key=7698309760885476376:1080` —— 1080P 来自新端点。

**但一开始播不出来**（界面：已自动重试 3 次，仍无法播放；按钮「尝试 720P」也无效 —— 720p 以下是 bytevc2，本来就没有可解码的 hvcC，App 直接跳过）。

### 数据是好的（电脑侧取证）

用 `kotlin-tv/core` 的 `MediaCrypto` 解密同一集的前几个视频样本，得到干净的 NAL 序列：

```
sample#0: VPS(32) SPS(33) PPS(34) SEI(39) IDR_W_RADL(19)   ← 70487 B
sample#1: TRAIL_R(1)  sample#2: TRAIL_R(1)
```

hvcC 的真实参数：`profile_idc=1`（Main）、`chromaFormat=1`（4:2:0）、8 bit、**`general_level_idc=186`（level 6.0）**、1920×1080@30；hvcC 共 204 字节，数组为 VPS 29 B / SPS 84 B / PPS 7 B / SEI 41 B。

### 假线索：Qualcomm 的 `extra-data` 刷屏

```
E/OMX-VDEC-1080P: Error: Insufficient size allocated for extra-data      ← 每帧一条
```

一开始把它当成元凶（于是做了「强制软件解码器」的实验）。但实验之后才发现：**这条刷屏是良性的** —— 硬解照样出画面、照样能播，硬解同时打这行日志（一次成功播放里有 794 条）。它只是这台机器的 Qualcomm codec 在喊，不影响出帧。

### 真正的元凶：DNS

App 侧日志给出的真凶是别的：

```
W/HongguoTV: Playback failure: UnknownHostException
I/HongguoTV: Episode preparation failed key=7698308076092279832:1080 elapsedMs=10110 UnknownHostException
```

**是取址主机的域名解析失败**，不是取址失败、不是解密失败、不是解码失败。

在手机上量：`ping api5-normal-sinfonlineb.fqnovel.com` → **15 次里 1 次 `unknown host`**；CDN 主机 `v9-reading-video.qznovelvod.com` 则 10/10 正常。

顺着往路由器上查：

| 环节 | 表现 |
| --- | --- |
| 手机 → 路由器 DNS | 该主机偶发解析失败（≈7%） |
| 路由器 DNS（192.168.1.1） | 中位 2–11 ms，但**偶发 1.3–1.6 s** |
| 直连 114.114.114.114 | 该主机 avg 84 ms / **max 1623 ms**；CDN 主机则 avg 3 ms |
| 直连 223.5.5.5 | 该主机 avg 1 ms / max 3 ms |

根因在 Passwall 的分流 DNS：chinadns-ng 用 `china-dns tcp://114.114.114.114` 处理 chnlist 里的域名，而**取址主机正好在 chnlist 里**（`fqnovel.com` / `cdnbuild.net` / `bytedns3.com`），它那条 4 跳 CNAME 链在 114 上又慢又不稳；Android 的解析器等不了那么久 → `unknown host` → 播放失败 → 自动重试 3 轮 → 「仍无法播放」。

**改在路由器的整网上**（不是给 App 单独开设置），把 Passwall 的国内 DNS 换掉：

```sh
uci set passwall.@global[0].direct_dns='223.5.5.5'   # 原 114.114.114.114
uci commit passwall
/etc/init.d/passwall restart
```

改完之后：手机解析 **50/50 全通**（改之前 15 次里失败 1 次），App 用**干净构建**（无任何诊断代码）直接播：

```
I/HongguoTV: Episode prepared key=7698309394479451160:1080 elapsedMs=1236
I/HongguoTV: Playback first frame episode=19 … elapsedMs=718
I/HongguoTV: Playback ready     episode=19 … elapsedMs=749
```

`UnknownHostException` 计数 **0**，首页「上次看到」推进到第 19 集 / 01:04 —— **是真的在放**。

### 但尾巴没消干净：换上游 ≠ 修好 DNS

换了上游之后**路由器侧的 A 查询延迟尾巴纹丝不动**（改前 avg 67 ms / max 1309 ms，改后 avg 65 ms / max 1593 ms）。于是把整条 DNS 链路逐跳拆开量了一遍。

**R86S(192.168.1.1) 上跟 DNS 有关的进程，一共有 5 层：**

| 层 | 监听 | 上游 | 归属 |
| --- | --- | --- | --- |
| 系统 dnsmasq | `0.0.0.0:53` | `/tmp/resolv.conf.d/resolv.conf.auto` | 系统 |
| passwall dnsmasq (default) | `192.168.1.1:11400` | `127.0.0.1#15354` | Passwall |
| passwall dnsmasq (acl cfg1230ec) | `192.168.1.1:11401` | `127.0.0.1#11501` | Passwall |
| chinadns-ng (default) | `:::15354` | `china-dns tcp://223.5.5.5` + `trust-dns 127.0.0.1#15353` | Passwall |
| chinadns-ng (acl cfg1230ec) | `:::11501` | 同上（ACL 实例） | Passwall |
| sing-box | `127.0.0.1:15353` | `remote: 208.67.222.222`（走 socks）/ `direct: 223.5.5.5` | Passwall |

**关键点 1：LAN 客户端根本走不到系统 dnsmasq。** iptables 的 `PSW_DNS` 链把来自 LAN 的 53 端口全部重定向：

```
-A PSW_DNS -p udp --dport 53 -j REDIRECT --to-ports 11400     # 默认
-A PSW_DNS -p udp --dport 53 -j REDIRECT --to-ports 11401     # ACL 实例（按 MAC 匹配）
```

所以 `/etc/dnsmasq.conf` 里那份 `resolv-file=` 指向的 `resolv.conf.auto`（含 `2400:3200::1` / `2001:dc7:1000::1` / `114.114.114.114` / `2408:8000:aaaa::` / `2408:8888::8`）**在客户端路径上是不生效的** —— 排查时别把它当嫌疑人。IPv6 上游也实测正常（`ping6 2400:3200::1` = 6.3 ms）。

**关键点 2：延迟尾巴出在 chinadns-ng 那一层。** 逐跳量（PC → 路由器，各 20 次真实域名）：

| 目标 | avg | max |
| --- | --- | --- |
| 系统 dnsmasq `:53` | 9.2 ms | 83 ms |
| passwall dnsmasq `:11400` | 1.5 ms | 2 ms |
| **chinadns-ng `:15354`** | **54.5 ms** | **311 ms** |
| chinadns-ng `:11501`（ACL） | 44.1 ms | 101 ms |
| 直连 223.5.5.5 | 1.6 ms | 3.6 ms |
| 直连 114.114.114.114 | 1.1 ms | 1.6 ms |

**上游本身是 1 ms 级的，chinadns-ng 却凭空加了 35 ms 的地板延迟。** 原因是 chinadns-ng 的分流结构：它对 chnlist 里的域名（本例取址主机就在 chnlist 里）会**同时**发往 `china-dns` 和 `trust-dns` 两路，`trust-dns` 指向 sing-box → socks 出去 → `208.67.222.222`，这一路的往返与代理节点抖动叠加，就是那个 1.3–1.6 s 的尾巴。改 `china-dns` 的上游治不了它。

**关键点 3：`all-servers` 会放大这个问题。** passwall 的 dnsmasq 配了 `all-servers`，即同时向 `127.0.0.1#15354` 和 `::1#15354` 发同一份查询，两者指向同一个 chinadns-ng，等于把它的延迟翻倍。

### 处置：整网关掉 Passwall

按「修网络问题要改路由器的整网出口、不要给单个 App/设备单独开设置」的既定做法，最终处置是**把 Passwall 整个停掉**，让 LAN 走系统 dnsmasq 直连上游：

```sh
/etc/init.d/passwall stop
```

停掉之后：11400 / 11401 / 15354 / 11501 全部消失，`PSW` 相关 iptables 规则清零，只剩系统 dnsmasq 监听 53。

**效果（同一批真实域名，PC → 路由器）：**

| | 改前（Passwall 开） | 改后（Passwall 关） |
| --- | --- | --- |
| 路由器 A 查询 avg | 65 ms | **5.5 ms** |
| 路由器 A 查询 max | 1593 ms | **92.9 ms** |
| 路由器 AAAA avg | 2 ms | 1.6 ms |
| 手机解析成功率 | 60 次里偶发 `unknown host` | **60/60 全通** |

尾巴基本消失。

### 压测结果（Palm 手机实机）

关掉 Passwall 之后，在 Palm（192.168.1.30）上做了两轮压测。压测方式：每轮冷启动 App → 进剧 → 用 `KEYCODE_MEDIA_NEXT` 连切 5–6 集，每切一次都等首帧（`Playback first frame`）并统计异常。

> 踩坑记录：播放器里 `KEYCODE_DPAD_RIGHT` 是**快进 seek(1)**，不是切集；切集是 `KEYCODE_MEDIA_NEXT`（`skipEpisode(1)`）。一开始用方向键测，出现「取址成功但没有首帧」的假失败，纯粹是键按错了。

**第一轮（Passwall 关掉后，5 轮 × 5 切 = 25 次）：**

```
switches attempted : 25
switches played    : 25
Playback first frame: 25
Playback ready      : 25
UnknownHostException : 0
Playback failure    : 0
recovery mentions   : 0
switch latency: avg=3.1s p50=3s max=5s
```

**25/25 全通，零失败、零 `UnknownHostException`、零自动恢复重试。** 对照之前 0.9.69 在同一台手机上「一次切集 = 12 个取址簇 + 3 轮自动恢复全败」的状态，这是彻底的翻转。

**第二轮（路由器重启后，确认 Passwall 没自己回来）：**

- 路由器：`11400 / 11401 / 15354 / 11501 / 15353` 全部无监听，`PSW` iptables 规则数 = 0，`/etc/rc.d/` 只剩 `K15passwall`（卸载项），`passwall.@global[0].enabled='0'`
- DNS：路由器 A 查询 avg **3.9 ms** / max **33.4 ms** / 0 失败；AAAA avg 1.6 ms
- 手机：取址域名解析 **40/40 全通**
- 播放：3 轮 × 6 切 = **18/18 全通**，切集延迟 avg **3 s** / max 3 s，零 `UnknownHostException`、零 `Playback failure`、零自动恢复重试

**两轮累计 43/43 成功。**

**持久化处置**（否则路由器一重启，Passwall 就自己回来、DNS 分流链重新接管 53 端口）：

```sh
uci set passwall.@global[0].enabled='0'
uci commit passwall
rm -f /etc/rc.d/S99passwall /etc/rc.d/S99passwall_server
/etc/init.d/passwall stop
```

> 副作用要知情：Passwall 停用后，**境内直连 + GFW 代理都没了**，需要翻墙的流量会失效。要恢复代理又保住干净的 DNS，只能走「关掉 `dns_redirect`、让 DNS 留在系统 dnsmasq」的路线，本轮没做。

### 顺带的两条教训

1. **CodecCapabilities 里写的 level 不可信**：media3 打印 `NoSupport [codec.profileLevel, hvc1.1.6.L186] [c2.android.hevc.decoder]`（软件解码器自己声明的 level 不够）却照样播了；Qualcomm 那边**没有**任何「不支持」提示，却在运行时喊 error。
2. **看到刷屏的 codec error 先别定罪**：先看 App 自己报的异常类型。真正的异常是 `UnknownHostException`，而刷屏的那行恰好在同一时间出现 —— 很容易被误当成元凶。
3. **压测脚本本身也会骗你**：播放器里 `KEYCODE_DPAD_RIGHT` 是快进、不是切集，用它测出来的「取址成功但没首帧」是**按键用错**的假失败。改用 `KEYCODE_MEDIA_NEXT` 后 43/43 全绿。

## 落地决定

1. **三道门，按这个顺序**：`registeredStream()`（注册身份 + 无签名）→ `signedStream()`（旧签名路）→ `webStream()`（公开页）。
   - 旧签名路**保留**：它在 2026-10-27 之前一直有效，可能恢复；它还是「注册身份这条路被拒」时的第二道门。
   - 公开页**只当兜底**：只有前 3 集 360p，不做主路径。
2. **身份缓存挂在 `ContentRepository` 实例上**，不是进程级单例。生产上 App 一个会话一个 repository，等于「一次会话注册一次」；但测试之间不会互相继承身份（这是当初 4 个测试失败的直接原因之一）。
3. **注册失败一分钟内不重试**（`PLAYBACK_REGISTRATION_RETRY_MS`），免得每次点播放都付一次注册代价；一分钟后再试。
4. **两道门都判定「网络死了」才跳过公开页**（`fallbackCannotHelp`）—— 网络没了的时候再敲一次公开页只是把等待翻倍，而重试计时器要等它回来才开始。

## 站方改接口时的排查顺序

1. **先问「站方今天答什么」**：直接打原始响应，看是 200 空体、`Code 110001`、404 还是形状变了。
2. **空体 ≠ 端点没了**：先做「同一端点、只换请求内容」的对照（换身份 / 换签名 / 换路径），能一次分清「端点消失」和「校验方式变了」。
3. **对照接口要选对**：拿同 host 的另一个接口当参照（本轮用弹幕接口区分 110001 与 404）。
4. **实抓一份响应原文存进仓库**：夹具和这份原文一致，测试才不是自说自话。

## 已知边界

- **电视端没验过**：验证时电视（192.168.1.26）不可达、无 adb over network，本轮只做了电脑侧端到端 + Palm 手机实机。
- **失败真凶是 DNS，不是链路**：手机端「播不出来」是取址主机域名解析失败（路由器 Passwall 的 DNS 分流链不稳），已在路由器上处置（见上文）。**电视用同一个路由器**，所以电视端大概率吃的就是同一个坑 —— 但电视上还没实测，电视端仍需一次实机验证。
- **Passwall 已整网停用并持久化**（`enabled=0` + 删掉 `S99passwall` 自启链接），路由器重启后确认没有回来；LAN 的 DNS 走系统 dnsmasq 直连上游。**副作用：GFW 代理同时失效**，需要翻墙的流量要另找方案（本轮没做）。
- **DNS 这条坑的通用结论**：分流型 DNS（chinadns-ng / smartdns 之类）会给境内域名的解析叠加**几十毫秒的地板延迟 + 秒级尾巴**，而且**换上游治不了** —— 要么整个分流层关掉，要么让 DNS 别经过它。排查时先量「客户端实际连的是哪个监听端口」（`iptables -t nat -S | grep REDIRECT`），别去猜配置文件。
- 站方取址主机（`api5-normal-sinfonlineb.fqnovel.com`）在 Passwall 的 chnlist 里，走 `china-dns` 这条路；**它和 CDN 主机的 DNS 表现不一样**（CDN 主机一直很快），排查 DNS 时要按域名分开量。
- `multi_video_detail/*` 保留为**剧集清单**来源（1000 集的 vid 与元信息、无付费墙证据），但它**不含播放地址**，不能当取址用。
- 公开直链 `hongguoduanju.com/video/public/<vid>/{360|540|720|1080|original|source|default}.mp4` 与 `*.m3u8` 实测全 404，**只有 `360p.mp4` 且只在前 3 集可用**；它值不值得接进 `webStream` 兜底，还没定。
- 新链路的 UA 与设备参数出自 GPL-3.0 项目（tsukiyo-zero/hongguoTV），来源已记进 `kotlin-tv/NOTICE.md`。

## 附：本次用到的探针

本轮用的是 `kotlin-tv/core` 下的一次性 `Temp*.kt` 探针（注册 + 取址 + Range 拉流 + 解密样本看 NAL），**验证完已全部删除**，`core/build.gradle.kts` 的 `mainClass` 也已改回 `com.hongguotv.core.ProbeKt`。App 侧那处「强制软件解码器」的改动也只是诊断用，已经撤掉。要复现本文的实测，按上面的请求形状手打即可，或临时再写一个探针 —— 但**别把它留在仓库里**。
