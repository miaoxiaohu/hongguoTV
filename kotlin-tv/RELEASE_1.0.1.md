# Kotlin 1.0.1：全量稳定性与边界缺陷修复

<!-- notes -->
这一版对播放取址、远端协议档案、局域网备份传输、弹幕会话与页面生命周期做了一轮全面修复：
补齐协议档案的严格校验与单次请求快照一致性；修复全库索引期间退出崩溃、手机会话/弹幕客户端/选集面板泄漏、
Chromium/iOS 手机备份导入失败、切换清晰度命中旧缓存、断网或取消时误触发换号兜底等 15 项问题。
real-drama 8 集、comic-drama 8 集全部通过。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 1000001。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 1.0.0 及更早版本并保留本机收藏、观看记录和设置。

## 修复内容

### 协议档案与播放取址（`core`）

- **协议档案严格校验与并发一致**：`PlaybackProfile` 拆分单集门与分集门可用引用集合，按阶段校验 `medusa` 字段类型与求值顺序，校验 `modelBody` 为合法 JSON；`PlaybackProfiles.apply()` / `reset()` 加同步保护，单次签名透传同一份 `PlaybackProfile` 快照，消除热更新时的跨版本撕裂；`PlaybackProfileStore.save()` 加锁并在 `finally` 清理 `.part` 临时文件。
- **取址异常、锁与缓存**：`streamDoors` / `v5VideoModel` / `tabVideoModel` 遇到 `CancelledException` 或不可恢复网络错误立即上抛，不再误触发换号与旧链兜底；拆分 `identityLock` 与 `rankingLock`，刷榜单不再阻塞设备注册；`addressCache` 缓存键加入 `maxQuality`，切换清晰度不再命中旧清晰度缓存；修复 `fallbackWithCodec` 单参数 URL 拼接。
- **局域网备份传输兼容**：`LibraryTransferServer` 补齐标准状态行、RFC 1123 `Date` 头、`Referrer-Policy: same-origin`，允许 `Origin: null`（仍拦截跨站与来源不匹配），修复 Chromium / iOS 浏览器导入失败；将 2MB 上限校验提前到打开端口与定时器之前。
- **其余 `core` 修复**：修复 `ApkCompatibility.blocker` 报错文案多余的 `\$` 转义；修复 `CatalogueIndexDisk.decode` 过滤脏行后 `routes` 下标错位；`CatalogueBuilder` 末页回调前先标记已完成路由并在 `pause()` 恢复中断标志；`DanmakuRepository` 实现 `Closeable`、同步限流并接入 `RequestScope`。

### 电视端生命周期与交互（`app`）

- **退出防崩溃与防泄漏**：`Library.closed` 改为 `@Volatile` 并保护全库索引收尾写盘，修复销毁 Activity 时的 `IllegalStateException` 崩溃；`TvTools.destroy()` 释放手机输入与备份传输会话；`EpisodePanel` 翻组与关闭时注销旧 `OnGlobalLayoutListener`；`DanmakuSettingsDialog` 在 `onActivityDestroyed` 同步关闭；统一管理倍速/清晰度/开源许可弹窗生命周期。
- **弹幕与播放页状态**：`DanmakuSession` 主线程回调前二次校验 `epoch`、`stop()` 取消在途请求、`close()` 后台释放 `OkHttpClient`；暂停播放或 `onPause` 时停止 `DanmakuOverlay` 空转刷新；`onConfigurationChanged` 在非前台时不重建播放器；出错或弹选集面板时调用 `touchController?.cancel()` 清理触控预览。
- **首页与搜索状态**：`runSearch()` 退出当前首页 `collection` 并保留 `catalogTotalPages`；`refreshRankingInBackground()` 在主线程先捕获 `library.contentType`；`Preparation.watchdog` 加 `@Volatile`。

## 验证

- 单测：`:core:test`（含新增协议档案校验、APK 兼容报错文案、备份传输响应头/Origin、索引过滤行对齐 4 项回归用例）与 `:app:testDebugUnitTest` 全绿；`:app:lintRelease` 0 errors。
- 探针：real-drama 8 集、comic-drama 8 集全部成功、失败 0，每集实读 1048576 字节媒体、画质 1080P。
