# Kotlin 0.9.103：取址请求换成站方现行的那套 medusa 明文字段（今天又改了签名）

<!-- notes -->
站方今天又改了取址请求的签名内容：x-medusa 里那串明文换成了新的字段集，
我们还在用旧的那一套，于是所有取址门都被静默封锁（HTTP 200 + 0 字节），
表现为「播放失败 / 只剩 10 秒试看」。
这一版把 medusa 明文与 query 里的设备档案一起换成站方现行的形态，
当天 real-drama 8 集、comic-drama 8 集全部恢复（每集实读 1MB 媒体、1080P）。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9103。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 0.9.102 及更早版本并保留本机收藏、观看记录和设置。

## 现象

用户报「现在不正常播放失败」。取址门整族空答：HTTP 200 + 0 字节，
不是错误码、不是超时 —— 站方的软拦截。降级链一路退到公开页，
最后每集只取到 10 秒试看片段。

## 根因：站方换了 medusa 明文的字段集

取址请求的签名头 x-medusa 里带一段 protobuf 明文（设备信息、环境、消息头）。
站方 App 今天起改成了另一套字段，我们还在发旧的，于是被静默封锁。

旧形态（0.9.102 在用的，被封锁）：

- flags 全 0；
- device 只有 13 个字段（1,2,3,4,5,6,12,13,22,25,30,31,32,34）；
- env 只有 4 个字段（6,8,12,14）；
- nested 是 (1,1)(2,1)(3,1)(5,1)；
- message 没有 f(16)，f(21) 是 377；
- device 的 f(5) 是裸的进程 nonce，不是 sec_device_token。

新形态（站方现行，跟随上游 lvguo-tv 的 v5.go）：

- flags = 4480000000000000；
- device 补齐到 30 个字段（含 f(7)~f(11)、f(14)、f(16)~f(21) 的浮点、f(23)、
  f(24)、f(26)~f(29)、f(33)、f(35)、f(36)、f(38)）；
- device 的 f(5) 换成 25 字符的 sec_device_token（首位固定 A），message 里再加一份 f(16)；
- env 补齐到 9 个字段（加 f(1) 启动序号、f(2)/f(3) 常量、f(5)、f(7) 进程号、f(13) 子结构）；
- nested 换成 (1,305)(2,4)(3,694367)(5,1621072674)；
- message 的 f(21) 从 377 改成 312。

## 第二处：query 里的设备档案不完整

medusa 明文里的身份字段是从 query 上取的（device_id / version_name /
device_type / device_brand / os_version / channel）。我们 model 门那一条 query
只有 13 个字段，缺 channel、device_brand 等 —— 签名根本编不出来。

这一版把两道门的 query 都换成完整设备档案（上游 openhongguo 的 29 字段固定顺序）：

- model 门（POST /novel/player/multi_video_model/player/v1）：29 字段；
- tab 门（GET /reading/bookapi/video_tab/multi_video_model/v1/）：29 字段 +
  rom_version + 取址参数段；
- 注册响应里的 klink_egdi 跟随上游带上（实测带上、不带站方都放行，不是根因）。

## 消融实验：哪一项才是必需的

| 形态 | 结果 |
|---|---|
| 新 medusa + 完整 query + 只带 3 个 v5 头 | real-drama 8/8 通过 |
| 新 medusa + 完整 query + 带 7 个 v5 头（多 x-gorgon/x-argus/x-ladon/x-xs-from-web） | real-drama 8/8 通过 |
| 旧 medusa + 完整 query + 7 个头 | 0/8，全失败 |
| tab 门单独验证（不经过 model 门） | 22531 字节、code=0、模型完整 |

结论：必需项是 medusa 的字段集；头集合带 3 个还是 7 个都行。
最终保持 3 个头（x-khronos / x-helios / x-medusa）——
与站方那套 Rust 实现、以及 0.9.102 的既有形态一致，现有的单测断言也不用改。

## 改了什么

1. Signer.kt：medusaV5() 换成站方现行的字段集（flags / device / env / nested /
   message / sec_device_token），新增 secDeviceToken()。
2. V5Playback.kt：抽出 deviceProfile()，两道门共用完整设备档案；
   model 门的 query 从 13 字段升到 29 字段。
3. PlaybackDevice.kt：注册响应里解析 klink_egdi，取址请求带上它。

## 验证

- 探针：real-drama 8 集、comic-drama 8 集全部成功、失败 0，
  每集实读 1048576 字节媒体字节、画质 1080P。
- tab 门单独验证：22531 字节、code=0、模型完整（不走 model 门也拿得到）。
- 单测：core:test 423 项、app:testDebugUnitTest 60 项全绿；
  app:lintRelease、app:assembleRelease BUILD SUCCESSFUL。
- 诊断代码已撤干净：临时诊断测试全删，git diff 里扫不到 DIAG / TabTrace。

## 没动的东西

弹幕几何/密度/透明度、播放页手势与常量、焦点落点、主题文案、
取址门的降级顺序与换号冷却（11-20 定的：一集最多 2 个身份、两次换号至少隔 30 秒）、
地址缓存 TTL 2 小时 / 上限 120 条、server/ 那套纯 JS 签名链路 —— 全部原样保留。
klink_egdi 带上了，但注释里写明了它实测不是根因，别再拿它当结论。
