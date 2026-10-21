# 红果弹幕接口实测

实测日期：2026-10-21。手段：`kotlin-tv/core` 的 Probe 框架（`--danmu`），见 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Probe.kt`。

## 结论

**红果短剧有弹幕，接口可用，而且本项目自己的签名器就能签通** —— 不需要移植上游那套 metasec 签名。

上游 `huangxd-/danmu_api` 的实现是另一套签名方案（metasec 风格 `X-Argus`/`X-Gorgon`/`X-Ladon`）。实测用本项目 `Signer`（drpys 风格，含 `x-gorgon`/`x-argus`/`x-ladon`/`x-helios`/`x-medusa`）请求同一个接口同样返回 `code=0`，所以搬签名代码这条路可以省掉。

## 请求

```
POST https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/{vid}/v1/
```

- 查询参数：设备身份 + `aid=8662` + `ts` + `_rticket`，与 `video_model/v1/` 同一套。
- 必需请求头：
  - `comment-source: 601`
  - `server-channel: 1000`
  - `x-ss-stub: ""`（空串即可；填 body 的 MD5 也返回 `code=0`）
- 请求体：

```json
{
  "comment_source": 601,
  "server_channel": 1000,
  "group_id": "<vid>",
  "group_type": 30,
  "comment_type": 20,
  "sort": 1,
  "business_param": {
    "book_id": "<series_id>",
    "start_offset_time": 0,
    "playlet_item_duration": 600000,
    "need_danmaku_guide_type": [1, 3, 4, 2]
  },
  "count": 90,
  "cursor": "",
  "aid": 8662,
  "compliance_status": 0
}
```

## 响应

`code=0`，`data` 下：

| 字段 | 含义 |
| --- | --- |
| `data.data_list[]` | 弹幕数组 |
| `data.common_list_info.cursor` | 游标，形如 `{"start_offset_time":0,"end_offset_time":30000}` |
| `data.common_list_info.has_more` / `total` | 是否还有下一页 / 本片总数 |
| `data.common_list_info.danmaku_window_count` | 按时间窗的累计条数，如 `{"0":2057,"60000":2511}` |
| `data.extra.next_query_danmaku_list_time` | 下一窗口的起点（毫秒） |

单条弹幕（`data_list[i].comment`）：

| 字段 | 含义 |
| --- | --- |
| `common.content.text` | 文本，含 `[昵称]` 之类的标记 |
| `expand.offset_time` | 相对本集起点的偏移，**毫秒** |
| `stat.digg_count` | 点赞数 |
| `comment_id` | 去重用的 id |

## 分页

一次返回 **30 秒窗口**（`end_offset_time=30000`）。要整集的弹幕就按 `extra.next_query_danmaku_list_time` 往后翻，`start_offset_time` 带上一次返回的时间。上游 `danmu_api` 用的窗口也是 30 秒。

## 实测样本

```
样本：盲盒摆摊王 全集 series=7694653385668037657 vid=7694655440692448281 集数=86
[A 签名 + 空 x-ss-stub] bytes=64806  code=0
danmaku: 本页 85 条, has_more=false, total=85
  [0] 0ms    digg=24 ...
  [1] 203ms  digg=12 ...
  [2] 499ms  digg=57 ...
  [3] 547ms  digg=40 ...
  [4] 726ms  digg=30 ...
  [5] 1960ms digg=63 ...
[C 无签名] bytes=125  Code 110001 未知异常
```

## 坑

1. **无签名返回 `Code 110001`，不是 HTTP 错误码。** HTTP 是 200，body 里才有错。看 HTTP 状态码会把「被拒」当成「成功但没数据」。
2. **对照请求不能省。** 同一轮里 `video_model/v1/` 的对照请求出现过 HTTP 200 + 0 字节（设备指纹被限），而弹幕请求照样 `code=0`。两者都答 200，不带对照就分不清「被拒」和「被封」。
3. **`offset_time` 是毫秒**，不是秒。直接当秒用会让弹幕整体偏移 1000 倍。
4. 每条文本里混着 `[昵称]` 之类的标记，是平台自己拼进去的，不是用户输入的一部分。

## 落地建议

- **core 层**：新增 `DanmakuRepository`，复用 `Signer` + `ContentRepository.http`；按 30 秒窗口分页取，按播放进度预取。
- **app 层**：`MainActivity` 里播放器是 `PlayerView` 挂在 `root` 上（`useController=false`，HUD 是叠在上面的一列），在其**上方**插一个透明的自绘 overlay，用 `player.currentPosition` 驱动即可。
- 参考实现里的「合并全集弹幕」是给弹幕服务端用的，本项目按集播，不需要。
