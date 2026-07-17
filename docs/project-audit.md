# IDAdroid 项目审计报告

> 审计日期: 2026-07-14
> 审计范围: 全项目代码、架构设计、AI 对话系统、安全性、性能、可维护性

---

## 一、严重问题 (Critical)

### 1.1 ConversationManager — 工具调用 ID 不匹配

**位置**: `ConversationManager.kt:101-102`

```kotlin
val tools = ToolEventBus(context, proot, paths).toolDefinitions()
val toolBus = ToolEventBus(context, proot, paths)
```

创建了两个 `ToolEventBus` 实例，一个仅用于获取定义，另一个用于执行。浪费资源且可能导致状态不一致。

**修复**: 合并为单一实例。

### 1.2 ConversationManager — 线程安全问题

**位置**: `ConversationManager.kt:71`

```kotlin
private var current: Conversation? = null
```

`current` 是一个普通 `var`，被多个协程并发访问（`send`、`abort`、`reset`、`restoreFromMessages`）。没有同步保护，存在竞态条件。

`Conversation` 内部的 `toolRound`、`aborted` 也是非原子字段。

**修复**: 使用 `Mutex` 保护所有对 `current` 的访问，或将字段改为 `AtomicBoolean`/`AtomicInteger`。

### 1.3 ChatHttpClient — 无重试机制

**位置**: `ChatHttpClient.kt:180-198`

网络请求没有重试逻辑。移动设备上网络波动频繁，一次 5xx 或连接重置就导致对话中断。

**修复**: 对可重试错误（429、5xx、IOException）实现指数退避重试，最多 3 次。

### 1.4 ChatHttpClient — API 错误解析缺失

**位置**: `ChatHttpClient.kt:193-197`

```kotlin
val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
emit(StreamEvent.Error(errorBody, responseCode))
```

直接将原始 HTTP body 作为错误消息返回给用户。不同 Provider 的错误格式不同（OpenAI `error.message`、Anthropic `error.message`、Google `error.message`），需要统一解析。

**修复**: 添加 `parseApiError()` 方法，提取各 Provider 的错误消息字段。

### 1.5 ChatHttpClient — 不支持 Anthropic API 格式

**位置**: `ChatHttpClient.kt:179`

```kotlin
val url = "${baseUrl.trimEnd('/')}/chat/completions"
```

硬编码 OpenAI 兼容的 `/chat/completions` 端点。Anthropic 使用 `/v1/messages`，请求体和响应体格式完全不同。当用户配置 Anthropic provider 时，所有请求都会失败。

**修复**: 根据 provider 类型选择不同的请求构建器和响应解析器。

### 1.6 ConversationManager — 无上下文窗口管理

**位置**: `ConversationManager.kt:84-208`

`conv.messages` 无限增长，没有 token 计数、没有截断策略、没有上下文压缩。`maxToolRounds=50` 只限制工具调用轮次，不限制上下文大小。长对话必然导致 token 溢出错误。

`PiAgentManager.compact()` 只是 `conversationManager.reset()`，清空所有上下文，用户体验极差。

**修复**: 实现 token 估算 + 滑动窗口截断（保留 system + 最近 N 条消息）+ 可选的摘要压缩。

### 1.7 ToolEventBus — 工具结果成功检测脆弱

**位置**: `ConversationManager.kt:186`

```kotlin
val success = !result.startsWith("错误:")
```

用字符串前缀 `"错误:"` 判断工具是否成功，极其脆弱。如果工具返回的正常内容以"错误:"开头（比如分析一个错误处理函数），会被误判为失败。

**修复**: 返回结构化结果 `ToolResult(success: Boolean, output: String, error: String?)`。

### 1.8 ToolEventBus — 无工具调用超时

**位置**: `ToolEventBus.kt:156-168`

`run_shell` 虽然有 timeout 参数，但其他工具（`read_file`、`write_file`、`list_dir`）没有超时保护。如果文件系统操作挂起（如 proot bind mount 问题），整个对话会永久阻塞。

**修复**: 为所有工具添加统一的超时包装。

---

## 二、重要问题 (Major)

### 2.1 PiAgentManager — 大量死代码

**位置**: `PiAgentManager.kt:640-681, 741-855`

`startSessionInternal()` 仍然引用 `PiRpcRuntime`，`handleRuntimeEvent()` 和 `handleAgentEvent()` 处理大量旧 RPC 事件。这些代码在新架构中完全不被使用，增加维护负担和二进制大小。

**修复**: 移除 `PiRpcRuntime` 相关代码路径，或标记为 `@Deprecated` 并隔离到单独文件。

### 2.2 ChatHttpClient — SSE 解析不健壮

**位置**: `ChatHttpClient.kt:200-273`

- 不处理多行 `data:` 字段（SSE 规范允许）
- 不处理 `event:` 类型前缀
- 不处理 `retry:` 字段
- `reasoning_content` 仅检查 `delta` 对象，部分 API（如 DeepSeek）将其放在不同位置
- 不处理空 `choices` 数组（某些 Provider 在 keep-alive 注释帧中发送）

### 2.3 ChatHttpClient — 缺少关键请求参数

**位置**: `ChatHttpClient.kt:107-177`

缺少:
- `max_tokens` — Anthropic API 必填
- `top_p`
- `presence_penalty` / `frequency_penalty`
- `response_format` (JSON mode)
- `stop` sequences

### 2.4 ConversationManager — 无工具并行执行

**位置**: `ConversationManager.kt:173-196`

多个工具调用顺序执行。如果 AI 同时请求 3 个独立的文件读取，会串行等待，浪费时间。

**修复**: 使用 `async` + `awaitAll` 并行执行无依赖的工具调用。

### 2.5 PiAgentManager — 流式 delta 合并器线程安全问题

**位置**: `PiAgentManager.kt:95-98`

```kotlin
private val pendingTextDelta = StringBuilder()
private val pendingThinkingDelta = StringBuilder()
```

`StringBuilder` 不是线程安全的。虽然 `sendMutex` 保护了 `sendPromptInternal`，但 `handleConvEvent` 中的 delta 累积和 `deltaFlushJob` 中的 flush 可能并发访问。

### 2.6 无 token 使用量统计

整个项目没有 token 计数功能。用户无法知道每次对话消耗了多少 token，也无法预估成本。

**修复**: 解析 API 响应中的 `usage` 字段，累加并在 UI 展示。

### 2.7 AttachmentManager — 附件大小无限制

**位置**: `AttachmentManager.kt`

没有对附件大小做限制。用户上传大文件会导致内存溢出（`bytes: ByteArray` 全部加载到内存）。

**修复**: 添加最大附件大小限制（如 10MB），超过提示用户。

### 2.8 AgentSessionRepository — 会话文件无大小限制

**位置**: `AgentSessionRepository.kt:9-19`

`agent-sessions.json` 无限增长。如果会话数量很多，每次 `loadStore()` 都要反序列化整个文件。

**修复**: 限制保存的会话数量，或改用数据库。

---

## 三、改进建议 (Minor)

### 3.1 代码结构

- `PiAgentManager.kt` 1226 行，职责过多（会话管理 + UI 状态 + 对话编排 + 工作区 + 附件 + 深度索引）。建议拆分为多个 Manager。
- `BoxedAgentLikeScreen.kt` 过长，应拆分为多个 Composable。
- `AgentModels.kt` 混合了 UI 状态、数据模型、事件类型，应拆分。

### 3.2 测试覆盖

- 仅有 `PiMessageNormalizerTest.kt` 一个测试文件。
- 核心的 `ChatHttpClient`、`ConversationManager`、`ToolEventBus` 无测试。
- 建议添加: SSE 解析测试、工具执行测试、对话循环测试、并发测试。

### 3.3 配置管理

- API Key 以明文存储在 JSON 文件中，应使用 Android Keystore 加密。
- `models.json` 与 `pi-config.json` 职责重叠，应统一。

### 3.4 错误处理

- 大量 `runCatching` 吞掉异常，只保留 `message`，丢失堆栈信息。
- 建议使用 `runCatchingSuspending`（已实现但未被使用）替代 `runCatching`。

### 3.5 文档

- README 主要描述功能，缺少架构图和开发者指南。
- `plan/` 目录下的文档与实际代码已有偏差（新架构移除了 pi rpc 但文档未更新）。
- 无 CONTRIBUTING.md、CHANGELOG.md。

### 3.6 构建

- `build.gradle.kts` (root) 未配置 `dependencyResolutionManagement` 的 version catalog。
- 依赖版本硬编码在 `app/build.gradle.kts` 中，不利于统一管理。
- 无 CI/CD 配置（GitHub Actions）。

---

## 四、AI 对话系统专项分析

### 4.1 架构评估

当前三层架构设计合理:

```
Layer 1: ChatHttpClient — HTTPS SSE 直连
Layer 2: ToolEventBus — 工具定义与执行
Layer 3: ConversationManager — 对话编排（LLM ↔ Tools 循环）
```

但存在以下结构性问题:

1. **Layer 1 和 Layer 3 耦合过紧**: `ConversationManager` 直接 `collect` `ChatHttpClient` 的 Flow，无法在中间插入中间件（如日志、重试、缓存）。
2. **Layer 2 无状态管理**: 每次调用都创建新实例，工具无法维护跨调用的状态（如文件句柄缓存）。
3. **PiAgentManager 越权**: 作为 UI 状态管理器，它直接操作 `ConversationManager`，又处理旧 RPC 事件，还管理工作区文件。违反单一职责原则。

### 4.2 对话质量优化建议

1. **System Prompt 优化**: 当前 system prompt 过长（约 240 行），包含大量工具使用说明。应精简为核心指令，工具说明由 tool definitions 的 description 字段提供。
2. **上下文压缩**: 实现真正的上下文压缩（而非清空），保留关键信息摘要。
3. **多模型回退**: 当主模型失败时自动切换到备用模型。
4. **流式输出优化**: 当前 33ms flush 间隔在某些设备上仍会导致卡顿，建议根据设备性能动态调整。

### 4.3 安全性

1. API Key 明文存储在 `pi-config.json` 中，任何有 root 权限的应用都能读取。
2. `run_shell` 工具无命令过滤，AI 可以执行任意 shell 命令（包括 `rm -rf /`）。应添加命令白名单或危险命令确认机制。
3. 无 SSL 证书验证自定义（某些企业内网需要），也无证书钉扎。

---

## 五、优先级排序

| 优先级 | 问题编号 | 描述 | 影响范围 |
|--------|----------|------|----------|
| P0 | 1.5 | Anthropic API 不支持 | 所有 Anthropic 用户 |
| P0 | 1.3 | 无重试机制 | 所有用户（移动网络） |
| P0 | 1.6 | 无上下文窗口管理 | 长对话场景 |
| P0 | 1.2 | 线程安全问题 | 偶发崩溃/数据错乱 |
| P1 | 1.1 | ToolEventBus 重复创建 | 资源浪费 |
| P1 | 1.7 | 工具结果检测脆弱 | 误判工具成功/失败 |
| P1 | 1.4 | API 错误解析缺失 | 用户体验 |
| P1 | 1.8 | 无工具调用超时 | 对话阻塞 |
| P1 | 2.1 | 死代码 | 可维护性 |
| P1 | 2.2 | SSE 解析不健壮 | 兼容性 |
| P2 | 2.3 | 缺少请求参数 | 功能完整性 |
| P2 | 2.4 | 无工具并行执行 | 性能 |
| P2 | 2.6 | 无 token 统计 | 用户体验 |
| P2 | 3.x | 代码结构/测试/文档 | 可维护性 |

---

## 六、第二轮修复 (2026-07-14 PM)

### 6.1 已修复

| 编号 | 问题 | 修复内容 |
|------|------|----------|
| 2.1 | 死代码清理 | 移除 `handleRuntimeEvent`、`handleAgentEvent`、`handleMessageUpdate`、`startSessionInternal`、`stopSessionInternal`、`ChatRuntime`、`chatRuntimes`、`runtimeFor`、`appendAssistantError`、`appendRaw`、`rawLines`，共约 **~400 行**死代码 |
| 2.5 | StringBuilder 线程安全 | 确认 `sendMutex` + `Dispatchers.Main.immediate` 保证 delta 合并器和 flush job 同线程，无实际竞态 |
| 2.6 | Token 统计 | `ChatHttpClient.readSSEStream` 添加 `lastUsage` 跟踪，确保流结束时携带 usage；空 choices usage 帧正确返回 `Finish` 事件 |
| — | `trimContextIfNeeded` 配对保护 | 原来注释说"确保不破坏配对"但代码未实现，现在正确回退截断点避免在 `assistant(tool_calls)` 和 `tool` 之间断开 |
| — | `suppressAbortError` 缺失 | `abort()` 中添加 `suppressAbortError = true`，确保主动中止后错误事件静默处理 |
| — | SSE 阻塞读取线程 | `ChatHttpClient.chat()` 的 Flow 添加 `flowOn(Dispatchers.IO)`，确保阻塞 IO 不占主线程 |
| — | 错误解析去重 | `parseApiError` 中 Anthropic/OpenAI/Google 的 3 段重复代码合并为 1 段 |
| — | 冗余异常检查 | `SocketTimeoutException` 是 `IOException` 子类，移除重复判断 |
| — | 未使用 import 清理 | 移除 `MutableSharedFlow`、`PiWorkspaceMaterializer`、`ProotBinaryInstaller`、`collect` 等 |
| 4.2.1 | System Prompt 优化 | 已在第一轮从 ~80 行精简到 ~40 行 |
| 4.2.2 | 上下文压缩 | 已在第一轮实现真正压缩（保留最近 1/3 + 生成摘要） |

### 6.2 遗留问题

| 编号 | 问题 | 状态 | 建议 |
|------|------|------|------|
| 3.1 | PiAgentManager 职责过多 | 遗留 | 拆分为 SessionManager + ChatManager + WorkspaceManager |
| 3.2 | 测试覆盖不足 | 遗留 | 添加 ChatHttpClient SSE 解析测试、ConversationManager 并发测试 |
| 3.3 | API Key 明文存储 | 遗留 | 使用 Android Keystore |
| 3.4 | runCatching 吞异常 | 遗留 | 替换为 runCatchingSuspending |
| 4.2.3 | 多模型回退 | 未实现 | 主模型失败时自动切换备用模型 |
| 4.2.4 | 动态 flush 间隔 | 未实现 | 根据设备性能调整 STREAM_FLUSH_INTERVAL_MS |
| 4.3.2 | run_shell 命令过滤 | 未实现 | 添加危险命令确认机制 |
| 2.7 | 附件大小限制 | 未实现 | 添加 10MB 上限 |
| 2.8 | 会话文件大小限制 | 未实现 | 限制会话数量或改用数据库 |
