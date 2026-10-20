# 内容服务 API v1

服务面向家庭网络，原生客户端共享此协议。所有成功 JSON 响应均使用 UTF-8；失败返回非 2xx 和 `{ "error": "可显示的信息" }`。上游失败不会返回空列表伪装成成功。

| 请求 | 返回 |
| --- | --- |
| `GET /health` | `service: hongguotv`、`version`、`apiVersion: 1`、`upstream: unverified`；只检查进程 |
| `GET /api/home?page=1` | `{ items: Series[], page }` |
| `GET /api/search?q=关键词&page=1` | `{ items: Series[], page }` |
| `GET /api/series/:id` | `Series` 加 `episodes: Episode[]` |
| `GET /api/episodes/:id/play?fresh=1` | `{ path, contentType: video/mp4 }`，媒体路径相对当前服务地址；`fresh=1` 强制重新解析 |
| `GET /api/episodes/:id/stream` | MP4 媒体流，支持单区间 Range、后缀区间、HEAD、Content-Length / Content-Range |

`Series` 字段：`id`、`title`、`cover`、`description`、`badge`、`tags`。
`Episode` 字段：`id`、`number`、`title`。ID 始终保留为字符串。

客户端按 `剧集ID + 分集ID + 播放秒数` 保存续播记录，不保存临时媒体 URL。一次播放错误会重新解析一次；仍失败时显示重试入口。

服务对非本机监听要求 `BRIDGE_TOKEN`：JSON 和视频请求均需 `Authorization: Bearer <口令>`，播放器也必须携带该请求头。监听地址不是回环地址而口令为空时，服务拒绝启动 —— 它会在本机完成 CENC 密钥派生并返回已解密的 MP4，开放监听而不带口令等于把整站内容交给同网段任意主机。当前没有浏览器跨域白名单配置，因此 Web 端需额外适配，不能当作已交付。

输入错误使用 400，口令错误使用 401，不存在的路径使用 404，无效媒体范围使用 416，源站失败使用 502。媒体请求限制为 4 个 —— `/play` 解析播放地址和 `/stream` 取媒体共用这 4 个名额，超出使用 503。服务不接受用户指定的任意代理 URL。

媒体代理按 1 MiB 分块处理并遵守客户端背压；对开放区间返回完整请求范围，而不是只返回首个 1 MiB。客户端不读数据时，等待有 30 秒上限，超时即断开该连接并释放名额 —— 一个不读也不断开的客户端占不满整个服务。`/play` 与 `/stream` 都接受 `fresh=1` 强制重新解析。元数据 / 视频缓存定时清理并限制数量。源站 URL 和签名材料不输出到客户端错误和服务日志。

搜索的 `page` 上限 100。站点网页搜索不翻页，因此深页取不到更多时会回落到网站搜索的同一批结果。接口不会把「没取到」伪装成空列表：过滤后为空会继续尝试网站来源，仍为空才报错。

服务为首版家庭使用实现。进程内存、长时间稳定性、不同编码的设备兼容性、源站自然过期后的行为及公网环境均需另行验证。
