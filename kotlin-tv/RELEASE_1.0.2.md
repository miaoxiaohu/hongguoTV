# Kotlin 1.0.2：适配站方官网详情页分集 ID 混淆（改走 App 目录接口）

<!-- notes -->
2026-11-27 凌晨，站方官网详情页将 `vid_list` 从全集纯数字分集 ID 改成了仅 5 条的混淆串（`v1_...`），
导致所有剧集打开报「分集数据异常」。这一版在检测到官网 `vid_list` 为非数字混淆串时，
自动改走站方 App 目录接口（`/reading/bookapi/directory/all_items/v/`）拉取全量纯数字分集 ID
（先用 v4 签名免注册，失败再回退 v5 签名）。
real-drama 8 集、comic-drama 8 集全部通过，恢复全集 1080P 播放。
<!-- /notes -->

最低 Android 8.0 / API 26，versionCode 1000002。单 APK，安装后联网使用，无需额外服务。
沿用发布签名，可覆盖安装 1.0.1 及更早版本并保留本机收藏、观看记录和设置。

## 故障根因

- 2026-11-27 00:08–00:43 之间，站方官网 `detail?series_id=` 页面里的 `seriesDetail.vid_list` 由全集纯数字 ID（如 210 项 `770911...`）改为仅 5 条 `v1_...` 混淆串（如 `v1_QMelE5ysHOf0nDNPVKvRGd5V1fk`）。
- 官网页面本身已不再下发全集真实 `video_id`，客户端纯数字校验全部拦截并报「分集数据异常」（`real-drama` 0/8、`comic-drama` 0/8）。

## 修复方案

- **`ContentRepository.detail()`**：保留官网详情页的剧集元信息（剧名、封面、简介、标签）；当官网 `vid_list` 为空或包含非数字混淆串时，自动调用 `directoryEpisodes()` 请求站方 App 目录接口 `/reading/bookapi/directory/all_items/v/?book_id=<id>&need_version=true` 拿全量纯数字 `item_id`。
- **双签名策略**：先走 v4 签名（免设备注册，浏览详情不消耗发号配额）；若空答或失败再回落 v5 签名（新增 `V5Playback.directoryItems()`，复用已注册身份）。
- 兼容目录接口两种响应形态（带 `code=0` 或无 `code` 字段但 `message=SUCCESS`），并立即透传 `CancelledException`。

## 验证

- 单测：`:core:test`（含新增 `detailFallsBackToAppDirectoryWhenWebVidListIsObfuscated` 回归测试）与 `:app:testDebugUnitTest` 全绿；`:app:lintRelease` 0 errors。
- 探针：real-drama 8 集、comic-drama 8 集全部成功、失败 0，每集实读 1048576 字节媒体、画质 1080P。
