# hongguoTV × CodeGraph 使用手册

> CodeGraph 1.6.2 已装在本机（npm 全局），hongguoTV 索引已建好（162 文件 / 3377 节点 / 9720 边，2.6s）。
> 索引位置：`D:\document\project\hongguoTV\.codegraph\`，自动监听文件变化增量更新（约 2s 延迟）。

## 一、MCP 工具（推荐：让 agent 用）

已注册进 `C:\Users\server\.minimax\mcp.json`，名为 `codegraph`：

```json
{
  "type": "stdio",
  "command": "codegraph.cmd",
  "args": ["serve", "--mcp", "--path", "D:\\document\\project\\hongguoTV"]
}
```

工具只有一个：`codegraph_explore`。用法就是用自然语言问它，例如：

- `"首页数据是怎么加载的 home 页面数据请求流程"`
- `"弹幕 danmaku 怎么加载"`
- `"MainActivity playerView 播放失败如何处理"`

返回：相关符号的**逐行带行号原始源码**（可直接 Edit）+ **调用路径**（含 grep 跟不动的动态派发）+ **影响范围**。

想查别的项目，在参数里加 `projectPath`：
```json
{"query": "xxx", "projectPath": "D:\\document\\project\\plam"}
```

## 二、CLI 命令（推荐：你自己手动查）

先 cd 到项目目录：

```powershell
Set-Location 'D:\document\project\hongguoTV'
```

### 查符号在哪
```powershell
codegraph query 'Player' -l 20
codegraph query 'Player' -k class -j      # 按类型过滤，-j 输出 JSON
```

### 看某功能怎么跑（= MCP 工具的等价物）
```powershell
codegraph explore '首页数据是怎么加载的'
```

### 🔥 改代码前：查影响面（最有价值）
```powershell
codegraph impact homeShelves -d 2
```
输出示例：
```
Impact of changing "homeShelves" — 7 affected symbols:
  method homeShelves:1130
  method refreshLibraryCards:1146
  method quickActions:1165
  method refreshFavoriteLabels:1181
  method onCreate:372
  method renderHome:1093
  method catalogGrid:845
```

### 🔥 改完代码后：自动找出该跑哪些测试（hongguoTV 最有价值）
```powershell
codegraph affected 'kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Danmaku.kt'
```
实测：改 `Danmaku.kt` → 命中 23 个测试文件，其中包含
`DanmakuTrackTest` / `DanmakuAnchorTest` / `DanmakuDensityTest` / `DanmakuTest`。
改 `DanmakuStyle.kt` → 换成 `DanmakuStyleTest` / `DanmakuSettingsTest` / `DanmakuDensityTest`。
**不用全量跑测试套件。**

### 查上下游调用
```powershell
codegraph callers 'Danmaku'
codegraph callees 'playEpisode'
```

### 看项目文件结构
```powershell
codegraph files
```

## 三、日常维护

```powershell
codegraph status        # 看索引状态/统计
codegraph sync          # 手动同步变更（平时不用，自动监听着）
codegraph index         # 彻底重建
codegraph unlock        # 卡住时清锁
codegraph uninit        # 删除索引（不想用了就这个）
```

## 四、hongguoTV 专属注意事项

1. **这是混合语言项目**：Kotlin（`kotlin-tv/`，105 个 .kt）+ TypeScript（`src/`、`server/`）。
   CodeGraph 两种都索引了，跨语言调用链也能追。

2. **只索引代码文件**：.md（122 个）、.txt（111 个）、.json、.xml 这些文档**不在图里**，
   查文档/配置仍然得用 Read。`docs/` 目录 122 个 md 全部不可通过 codegraph 查到。

3. **`MainActivity.kt` 有 153KB**（约 4400 行）。这是最大痛点：
   任何 grep 到它的探索都会把整个文件读进上下文（~45k token）。
   CodeGraph 只返回相关段落（~6.5k token），省 89%。**遇到 MainActivity 相关问题优先用 explore。**

4. **官方承认的副作用**：CodeGraph 返回大块原文且长期驻留上下文，
   会话结束时残留上下文比 grep-Read 型**多约 80%**。
   → 短任务随便用；长时间连续干活时，适时开新会话。

5. **别全信也别全不信**：它明说结果来自完整 AST 解析，不要再用 grep 复核一遍。
   但如果它报的符号名找不到（会带 did-you-mean 提示），就用提示的名字重查。

## 五、不适用的情况

- `D:\document\project\honoiot`：老 ASP 项目（322 个 .asp），CodeGraph **不支持 ASP**，
  只索引到 115 个 js，已建的索引建议删掉。
- `break` / `libretv-*` / `time_task` 等几十文件的小项目：官方数据显示 110 文件的项目
  成本节省基本持平，小项目不值得建图。
- **值得考虑的其它项目**：`plam`（1367 文件 / 30 万行，最大）、`hongguoTV_IOS`（Swift 77 文件）。
