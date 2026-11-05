# Kotlin 0.9.86：电视端「又播不了」的根因修好了

<!-- notes -->
上一版修完触控手势之后，电视上还是播不了。这一版把根因挖到底了，而且根因不在网络、也不在签名 —— 是站方按我们请求里的一个参数决定发什么编码。我们之前把它写死成了 3，站方因此一直发 ByteVC2（电视解不了的格式）；改成 1 之后站方发的是真 HEVC，电视能硬解。附带把「每集注册一次」的取址方式改成「一次注册换一整批剧集」，根治了另一条导致大面积播放失败的链路。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 9086。单 APK，安装后联网使用，无需额外服务。沿用发布签名，可覆盖安装 0.9.84 / 0.9.85 及更早版本并保留本机收藏、观看记录和设置。

## 这一版改了什么

### 一、根因：站方按 `device_level` 决定发什么编码

播放取址接口 `novel/player/multi_video_model/v1/` 的请求体里有一个 `biz_param.device_level`。
**这个值决定站方返回的媒体编码**，跟签名对不对、身份注册没注册，都没关系。

同一集《悍妇边军第一季》EP `7702043421581511704`，同一套签名、同一套身份，只改这一个参数：

| `device_level` | 站方给的编码 | 抓前 256 KB 实测容器 fourcc | 电视能解吗 |
|---|---|---|---|
| **3**（旧代码写死的值） | 5 条 rendition 全是 `bytevc2` | `bvc2,mp4a` | ❌ 不能 |
| **0** | `bytevc1` | `hvc1,mp4a` | ✅ 能 |
| **1**（本版采用） | `bytevc1` | `hvc1,mp4a` | ✅ 能 |

所以旧包的现场表现是：**能取到地址、能显示时长、能显示清晰度，一按播放就黑屏或直接报错** ——
地址里躺的是 ByteVC2 流，电视的硬解器根本不认识。

这一版把取值固定为 `ContentRepository.PLAYBACK_DEVICE_LEVEL = 1`（批量体和单集体都用它）。
`device_level=0` 和 `1` 站方给的答案完全一样，选 1 是为了跟站方其它客户端的取值一致。

**顺带排掉的几条“看起来像”修法的路**（实测都无效，别再试）：

- `use_os_player=true`、`prefer_codec=h264`、`detail_page_version=1` + `video_codec_type=1` ——
  在 `device_level=3` 下**全都无效**，站方照样发 bytevc2。
- 换 `fallback_api` 并改 `codec_type`（4/0/5/1）—— 同样无效。
- 走单集端点 `novel/player/video_model/v1/`（用注册身份）—— 确实有回答（15929 字节，`code=0`），
  但它给的 `video_list` 还是 5 条 bytevc2。

### 二、连带修的：一次注册换一整批剧集

站方的注册接口对高频注册限流，被拒时返回 `device_id=0` / `install_id=0`。
而**一个注册身份只能问一次地址**（实测：同一身份连续问 10 次，第 1 次成功、其余 9 次全空；
重新注册后立刻又成功）。旧逻辑是「一集一次身份」，等于每播一集就注册一次，几十集下来必然触顶，
表现就是**开头几集能播，后面大面积播不了**。

这一版改成批量取址：

- 一次注册 + 一次签名请求，body 用 `mixed_video_id_map` 一次问 **15 集**（站方硬上限 20，
  超过报 `code:100001` / `video_id list len over limit:20`）。
- 取址结果进 **地址缓存**：TTL 2 小时、上限 120 条。同一剧的后续集直接命中，耗时 0ms。
- 注册被拒（`device_id=0`）不再硬失败、也不立刻重试：走 **10 分钟长退避**，
  并把「站方暂时不给注册」这个真实原因透到界面上（以前一律显示「播放失败」）。
  普通失败的 5 秒重试窗口不变，两者不要混。

### 三、落点

- core `ContentRepository.kt`：`PLAYBACK_DEVICE_LEVEL`、批量取址、地址缓存、长退避、可播 rendition 过滤。
- core `PlaybackDevice.kt`：`register()` 改为显式区分「注册被拒」（`PlaybackRegistrationRefusedException`）。
- app `MainActivity.kt`：详情页备集、下一集备集都把剧集窗口传给取址，播放与预取共用同一次注册。

## 验证

- **窗口探针（生产链路）**：`ok=15/15`、`registrations=1`、`elapsedMs≈1671`；
  EP1 真读 262144 字节，EP2~15 全部命中缓存（0ms），quality 全 1080P，容器 fourcc 全 `hvc1,mp4a`。
- **core 单测**：全绿（`ContentRepositoryTest` 98 个用例，0 失败）。
- **Palm 实机（0.9.86 / 9086，覆盖安装成功）**：`dumpsys media_session` 显示 `state=PLAYING(3)`，
  logcat 里 `qcom.decoder.hevc` 在解码，`Playback first frame episode=11 ... elapsedMs=445` ——
  **HEVC 硬解正常，首帧 445ms**。

## 还没有做的

- **电视端（XGIMI 电视 / 投影）还没实测**。发布时投影上的推装服务（`192.168.1.26:10000`）不可达，
  整台设备当时不在局域网内，所以这一版走的是 CI 发布 + 电视端 App 内更新。
  电视上打开 App → 设置 →「版本与更新」手动触发即可（Android 8 需要在系统弹窗里确认一次安装）。
- 《悍妇边军第一季》在 Palm 上还没点开验证（搜索框不支持中文输入，App 也没有深链入口），
  但同一剧集的 15 集在探针里逐集真读过媒体字节，结果全绿。

## 升级方式

- 电视/投影：App 内「版本与更新」，或等它每 6 小时自动轮询一次。
- 手机/平板等有 adb 的设备：`adb install -r hongguotv-kotlin-0.9.86-android8.apk`（同签名，可覆盖安装）。
