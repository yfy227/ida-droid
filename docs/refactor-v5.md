# IDAdroid V5 重构方案 — 参考优秀手机 Agent 的全面升级

> 2026-10-29 | 基于 R4 (d4350ec) | 调研: DSHA / pi-mono / Claude Code / OpenAI prompt caching

## 一、R4 现状实锤的 4 个致命/重伤问题

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| P0-1 | `put("", toolCalls数组)` 字段名为空串 | ChatHttpClient.buildRequestBody L277 | assistant 历史的工具调用丢失/请求体畸形 |
| P0-2 | `put("_id", toolCallId)` | ChatHttpClient.buildRequestBody L291 | tool 消息缺 `tool_call_id` → 400 或历史丢失 |
| P0-3 | `delta[""]` 解析工具调用增量 | ChatHttpClient.parseSSEEvent | **流式工具调用永远收不到 → agent 工具链路全断** |
| P1-1 | `withTimeout(180s)` 包住整个 agent loop | PiAgentManager.sendPromptInternal | 多轮工具长任务 3 分钟必被腰斩(run_shell 上限 600s 自相矛盾) |

## 二、四个体验痛点 → 设计方案

### 痛点1: MCP 文件流要手动 (→ host 侧原生 pull_file 工具)
- 新增 `PullFileTool`(function calling): AI 直接调 `pull_file(name)` → host 侧搜索 /sdcard 常见目录 + workspace + .mcp-transfer → 内容寻址去重(SHA-256 前 256KB 快指纹) → 落到 /root/.mcp-transfer → 可选经 IdaMcpClient 直开 IDA → 返回结构化 JSON
- FileTransferHttpServer 新增 `GET /api/scan?name=` (容器侧脚本 `idadroid-file` 同步受益)
- 保留 DSHA 风格进程/文件契约: 去重 entry 幂等, manifest 上限 64

### 痛点2: OOM 防护
- SSE 事件级缓冲上限 2MB, 超限丢弃并报错(防恶意流)
- TokenUsage 每轮清零 → 事件流可控
- persistMessages: 全量重写 → 节流(TurnComplete/压缩后写) + 原子写(temp+rename)
- 图片老化: LLM 视图中非最近轮的 images 剔除(原文保留, 会话可回看)
- upsertTool/DeltaFlusher 已有 33ms 节拍 + LazyColumn(已确认), 保持

### 痛点3: 对话流复用
- 真流式: readSSEStream 改 callback 逐事件上报(原来整轮攒 List 再回放 = 伪流式)
- 连接复用: 成功结束不再无条件 disconnect(保留 JVM keep-alive 池), 错误路径才断
- 重试缓存一致性: 新增 `StreamEvent.RoundReset`, 重试时上层清 buffer(修文本重复 bug)
- 修复 P0-1/2/3 → 多轮工具对话恢复可用

### 痛点4: 缓存对齐(参考 OpenAI 前缀缓存规则)
- 前缀冻结: system + tools 一会话内字节不变(字段顺序固定、tools 注册序稳定)
- **压缩只在轮边界**(LLM 完成后、下次请求前), 压缩=显式缓存重置点(参考 pi-mono: 剪枝 bust cache 是预期行为)
- TokenUsage 新增 `cachedPromptTokens`: 解析 `usage.prompt_tokens_details.cached_tokens`(OpenAI/DeepSeek) 与 `cache_read_input_tokens`(Anthropic)
- ConversationEvent.TokenUsage 增加 cachedTokens, UI 可显示命中率

## 三、上下文压缩策略(参考 Claude Code auto-compact + pi-mono 非破坏剪枝)

```
阈值: SOFT=80% 硬性检查点 | HARD=95% 兜底截断 | 预留 RESERVE
分层(非破坏 — LLM 视图压缩, session 原文完整保留):
  L1 工具结果老化: >K 轮前的 tool 消息结果截断至 1/4(保头尾)
  L2 图片老化: >K 轮的 images 剔除
  L3 LLM 摘要: compactor 回调(已有接口) 对被移除区间生成摘要
  L4 硬截断: 保 system + 摘要 + 最近 N 轮原文
时机: 仅轮边界(runAgentLoop 每轮 LLM 调用前)
TrimResult 增加 Compacted/Truncated 细分, 发 CompactionReport 事件
```

## 四、参考映射

| 来源 | 借鉴 |
|------|------|
| DSHA | 工程纪律(util 零 Android 依赖+单测)、幂等文件契约、进程边界不误伤 |
| pi-mono | 非破坏上下文剪枝(原文在 session、剪的是 LLM 视图)、context 事件每次 LLM 调用前触发、剪枝=预期 cache bust |
| Claude Code | auto-compact 软/硬阈值、/context 可视化(用事件携带) |
| OpenAI caching | 前缀逐字节稳定: system→tools→history 顺序, 压缩只在轮边界 |
| Operit | Android tool-calling 插件化注册(沿用 ToolRegistry) |

## 五、改动文件清单

1. `agent/ChatHttpClient.kt` — 重写(修 3 个 P0 + 真流式 + keep-alive + RoundReset + cachedTokens + SSE 上限 + 确定性编码)
2. `agent/ContextWindow.kt` — 分层压缩 + LLM 视图(fullMessages 原文 / llmMessages 视图)
3. `agent/ConversationEngine.kt` — 轮边界压缩 + RoundReset 处理 + 事件增强 + 注册 PullFileTool
4. `agent/ConversationTypes.kt` — TokenUsage 事件 +cachedTokens, 新增 CompactionReport 事件
5. `agent/PullFileTool.kt` — 新增(host 侧主动拉文件)
6. `agent/PiAgentManager.kt` — 去 180s 超时(单轮 LLM 300s readTimeout 已有)、persist 节流+原子写、compactor 实现
7. `mcp/FileTransferManager.kt` — 内容寻址去重 + 主机目录扫描
8. `mcp/FileTransferHttpServer.kt` — /api/scan 接口
9. `app/src/test/...` — 单测: 请求体字段名/确定性编码/压缩分层

## 六、保留的原有特色(不动)

- VNC IDA GUI / proot terminal / RootfsImporter
- mcpc + ida-mcp + deep-index 工具链 + idadroid-file 脚本桥(兼容)
- 三层引擎命名与事件语义(增量兼容)
- Provider 矩阵 / AiConfig / AgentModels UI

---

## V5.1 增量(2026-10-30 第二轮 — 追加需求)

### 1. 辅助 MCP 能力扩展(agent 层原生工具, 替代 run_shell→mcpc 文本链路)
- `pull_file` — 主动拉宿主文件(多候选/内容指纹去重/可选直开 IDA)
- `mcp_call` — host 侧直调 ida-mcp 任意工具(JSON-RPC, 结构化)
- `mcp_status` — 会话健康 + 工具清单(MCP tools/list 协议)
- `IdaMcpClient.listTools()` 新增; `FileTransferHttpServer` 新增 `GET /api/scan?name=`
- `IdaMcpFileBridge` — McpFileBridge 的 ida-mcp 实现(UI 层连线, agent 层零 mcp 依赖)

### 2. 插件市场(参考 DSHA 插件生态 + Operit 插件化)
- `PluginManager` — 声明式 JSON manifest(id/version/tools/params), 安装/启用/禁用/卸载
- `PluginTool` — manifest 工具包装为 function calling({{param}} 模板替换 + shell 单引号转义防注入, 经 ToolContext.proot 执行)
- 内置市场 `assets/plugins/market.json`: APK 体检工具箱 / ELF 深扫 / Frida 助手 / DSHA 实用件(6 工具)
- `PluginMarketScreen` 市场 UI(安装/卸载/启用开关/工具徽章)
- HomeScreen 插件市场入口卡 + IdaDroidScreen.Plugins 路由
- 启用插件幂等注册进 ToolRegistry(禁用/卸载即移除)

### 3. 保活增强(KeepAliveService 升级)
- 动态进度通知: 连接模型 / 执行工具 xxx / 接收输出(事件驱动实时更新)
- PARTIAL_WAKE_LOCK(turn 期间持有, 35min 上限防泄漏)
- beginTurn/updateProgress/endTurn 接进 PiAgentManager 事件流 + abort 路径
- 3 分钟腰斩 → 30 分钟硬上限(LLM/工具各有细粒度超时)

### 4. 对话消耗/缓存统计条(参考 Claude Code /context + ChatGPT usage)
- `TurnUsage` — 本轮累计 prompt/completion/total + cachedPromptTokens + 上下文水位
- `TurnUsageBar` — composer 上方: ↑↓token / 缓存命中%+节省量 / 上下文水位进度条(80% 橙 95% 红)/ 压缩标记
- 数据链: parseUsage(OpenAI `prompt_tokens_details.cached_tokens` + Anthropic `cache_read_input_tokens`) → Finish.usage → engine 累计 → AgentUiState.usage → UI

### 5. 工程清理
- 删除零引用死代码: ConversationManager.kt / ToolEventBus.kt
- persistMessages 原子写(temp + rename, 防崩溃留半文件)
- DeltaFlusher.resetStreaming(网络重试清 UI 流式内容防翻倍)

### 6. 踩坑记录(重要!)
- **上下文压缩系统会吞 Write/Edit 内容中的 "tool_calls" 字面量** — 第一轮的 P0 修复曾因此
  假性落盘(显示已修但字节未修)。必须用 python 字节断言 `b'put("tool_calls"' in raw` 复核,
  别信 grep/sed/Read 的显示(输出显示层同样会吞该字样)。
- AgentTool 契约: `val parameters: JsonObject`(属性, 非函数) + `execute(argsJson: String, ...)`(String 非 JsonObject)
- 工作区文件系统间歇性 Permission denied — Write/Edit 工具通道稳定, python open(w)/heredoc 偶发被拒, 重试或换通道即可

## 七、最终验证清单(全部通过)

- market.json: 4 插件 6 工具, 参数-命令一致性 ✓
- 20 文件括号平衡: 差值与 git 原版逐文件一致(新文件全 0)✓
- P0 三处字节断言: `put("tool_calls")`=1, `put("tool_call_id")`=1, `delta["tool_calls"]`=2(代码+注释), 空键/_id=0 ✓
- 关键符号落盘: engine(llmMessages/compactIfNeeded/RoundReset/三工具注册/cachedTokens)、
  ContextWindow(llmMessages/compactIfNeeded 定义, 旧 trim=0)、Manager(TURN_HARD_LIMIT/保活×3/
  TurnUsage/resetStreaming/原子写/桥)、工具契约(val parameters ×4, argsJson 签名 ×4)✓
- 接口对齐: McpFileBridge 7 方法 vs IdaMcpFileBridge 实现 ✓
- KeepAliveService: 3 静态方法 + WAKE_LOCK ✓
