# Kotlin 1.0.0：取址协议里的数据抽成可远程下发的档案（站方下次改字段不用发版）

<!-- notes -->
这一版把取址请求里「站方会改的那部分数据」抽成一份 JSON 协议档案，
端上启动时从远端拉取并覆盖内置默认。
站方下次再改字段集（medusa 明文字段表、magic/sbox、门地址、设备档案），
改一下仓库里那份 JSON 就行，不用再发版；拉不到、解析不过、版本号不新，
一律继续用内置默认，行为与上一版完全一样。
本次改造行为零变化：real-drama 8 集、comic-drama 8 集全部通过。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 1000000。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 0.9.103 及更早版本并保留本机收藏、观看记录和设置。

## 为什么做这件事

过去三个月站方改了三轮取址协议（11-16 换 v5 取址、11-23 上午改签名要求、11-23 下午换 medusa 字段集），
每一轮都要发一版 APK。回头看，三次改的全是**数据**：

- medusa 明文里写哪几个字段、写成什么值；
- magic / license / signKey / sbox 这几个常量；
- 取址门的 host / path / User-Agent；
- query 里的设备档案（哪些字段、什么顺序、哪些取自身份）。

而**算法**一次都没动过：xmxor、hashF13、sm3、AesV3 的表与位序、随机量的取法与顺序、
protobuf 的编码规则。

所以把「会变的那部分」抽成一份档案端上可覆盖，站方下次改字段就不用发版 ——
改一份 JSON、推一次 main，端上重启就换。

## 外置了什么

- medusa 明文的五张字段表（device / env / envSub13 / nested / message）；
- magic、license、signKey、sbox（v5 的字节替换表）；
- 签名头集合（当前三个：x-khronos / x-helios / x-medusa）；
- 单集门（POST model）与分集门（GET tab）的 host / path / User-Agent；
- query 的设备档案（29 个字段的固定顺序 + 哪些取自身份、哪些是字面量、哪些按条件才带）；
- 分集门的业务参数（video_id / device_level 等）；
- 单集门的请求正文模板。

## 没外置什么（刻意为之）

- xmxor / hashF13 / sm3 / AesV3 的表与位序；
- 随机量的取值方式**与顺序**（envLaunch → envPid → 明文里的 rand → nonce → 包头 rand）；
- protobuf 的 varint 编码规则；
- 取址门的降级顺序、换号与冷却策略。

理由：那些是算法，站方一次都没动过；外置它们等于给签名多加一个出错的地方。
档案只描述「说什么」，不描述「怎么算」。

## 端上的行为

- 启动时先读本地缓存（`filesDir/playback-profile.json`），换上生效的那份；
- 后台从 `https://raw.githubusercontent.com/miaoxiaohu/hongguoTV/main/playback-profile.json` 拉一次；
- 拉取规矩跟更新包一样：只走 HTTPS、只认白名单里的域、响应有大小上限；
- **版本号只增不减**：远端档案比当前旧就丢掉（防止缓存回退）；
- 拉不到 / 解析不过 / 校验不过 / 版本号不新 —— 一律继续用内置默认，**行为与改造前完全一致**。

内置默认就是这一版的实测形态，所以第一次上线拉到远端档案时行为零变化。

## 站方下次改字段集怎么操作

1. 改 `PlaybackProfile.builtin()` 里的数据（那是事实来源）；
2. 跑导出器生成仓库根的 `playback-profile.json`：
   `cd kotlin-tv` → `.\gradlew.bat :core:test --tests 'com.hongguotv.core.PlaybackProfileExportTool' -Dprofile.revision=N --console=plain --no-daemon`
3. 跑 `python kotlin-tv/tools/order_profile.py` 排键序（好 diff）；
4. **revision 加一**再提交 —— 不加一端上不会采纳。

回滚同理：把档案内容改回旧值并把 revision 加一。

## 验证

- 单测：core:test 440 项、app:testDebugUnitTest 60 项全绿；
  app:lintRelease、app:assembleRelease BUILD SUCCESSFUL。
- **逐字节对照**：档案驱动的签名与改造前那版 Signer 的输出逐字节一致
  （medusa 明文与 helios 头，参照件是 test 目录里的 `LegacyV5Signer`）。
- 档案自检：仓库里下发的那份与内置默认逐项一致（单测守着）。
- 探针：real-drama 8 集、comic-drama 8 集全部成功、失败 0，
  每集实读 1048576 字节媒体字节、画质 1080P。
- 坏档案一律拒绝：版本号不认识、sbox 长度不对、设备档案缺 medusa 需要的字段、
  引用了不存在的取值 —— 宁可继续用内置默认，也不拿半对的档案去签名。
- 诊断代码已撤干净：临时诊断测试全删，git diff 里扫不到 DIAG / TabTrace / --show。

## 没动的东西

弹幕几何/密度/透明度、播放页手势与常量、焦点落点、主题文案、
取址门的降级顺序与换号冷却、地址缓存 TTL、server/ 那套纯 JS 签名链路 —— 全部原样保留。
老的那套 POST 门、旧 tab 形态与 legacy 降级分支也还在代码里，没删。
