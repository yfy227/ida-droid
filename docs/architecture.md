# IDAdroid 架构文档

> 版本: 2.0 | 更新日期: 2026-07-14

## 系统概览

IDAdroid 是 Android 端 IDA Pro 逆向工程 + AI Agent 平台，通过 proot 运行 Linux rootfs，实现无需 root 的移动端逆向分析。

```
┌─────────────────────────────────────────────────────┐
│                    Android App                      │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ VNC GUI  │  │ Terminal │  │   AI Agent Chat   │  │
│  │ (IDA UI) │  │ (Shell)  │  │                   │  │
│  └────┬─────┘  └────┬─────┘  └────────┬──────────┘  │
│       │              │                  │            │
│  ┌────┴──────────────┴──────────────────┴─────────┐  │
│  │              proot rootfs (Linux)               │  │
│  │  ┌─────────┐ ┌────────┐ ┌──────────┐ ┌───────┐ │  │
│  │  │IDA Pro  │ │ida-mcp │ │ pi agent │ │ tools │ │  │
│  │  │  9.3    │ │        │ │ (RPC)    │ │       │ │  │
│  │  └─────────┘ └────────┘ └──────────┘ └───────┘ │  │
│  └────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

## 模块结构

```
idamcp-/
├── app/                          # 主应用模块
│   └── src/main/java/dev/idadroid/
│       ├── agent/                # AI 对话引擎 (核心)
│       │   ├── ChatHttpClient.kt     # Layer 1: HTTPS SSE 流式对话
│       │   ├── ToolEventBus.kt       # Layer 2: 工具执行总线
│       │   ├── ConversationManager.kt # Layer 3: 对话编排器
│       │   ├── PiAgentManager.kt     # UI 状态管理 + 事件分发
│       │   ├── PiMessageNormalizer.kt # 消息格式标准化
│       │   ├── PiConfigManager.kt    # Pi 配置管理
│       │   ├── AiConfigTools.kt      # API 测试/模型拉取
│       │   ├── EndpointCompleter.kt  # 端点 URL 自动补全
│       │   ├── AttachmentManager.kt  # 附件管理
│       │   ├── WorkspaceManager.kt   # 工作区文件管理
│       │   ├── AgentSessionRepository.kt # Session 持久化
│       │   ├── AgentModelCatalog.kt   # 模型目录解析
│       │   └── AgentModels.kt         # 数据模型
│       ├── proot/                # proot 运行时
│       ├── env/                  # 环境管理
│       ├── mcp/                  # MCP 客户端
│       ├── ui/                   # Compose UI
│       ├── settings/             # 应用设置
│       ├── files/                # 文件管理
│       └── util/                 # 工具函数
├── terminal-emulator/            # 终端模拟器 (Termux)
├── terminal-view/                # 终端视图组件
├── plan/                         # 设计文档
└── docs/                         # 技术文档
```

## AI 对话引擎架构 (三层分层)

### Layer 1: ChatHttpClient — 直接 HTTPS 对话客户端

**职责**: 直接调用 LLM API，处理 SSE 流式响应。

**支持的 Provider**:
- OpenAI 兼容 (`/v1/chat/completions`)
- Anthropic (`/v1/messages`)
- Google Gemini (转换格式)

**关键特性**:
- 流式 SSE 解析（text/thinking/tool_call 增量）
- 指数退避重试（429/5xx，最多 3 次）
- 多格式错误解析（OpenAI/Anthropic/Google/Generic）
- Token 使用量统计
- Thinking/Reasoning 支持（DeepSeek-R1、Claude、o1 等）

### Layer 2: ToolEventBus — 工具执行总线

**职责**: 将 IDA/MCP/Shell 工具暴露为 OpenAI function calling 格式，执行 AI 发起的工具调用。

**内置工具**:
- `run_shell`: 在 proot rootfs 内执行 shell 命令
- `read_file`: 读取工作区文件
- `write_file`: 写入工作区文件
- `list_directory`: 列出目录内容

**关键特性**:
- 统一的成功/失败判断（`isToolResultSuccess()`）
- 工具执行超时保护
- 安全路径检查（防止越界访问）

### Layer 3: ConversationManager — 对话编排器

**职责**: 编排 Layer 1 + Layer 2，管理对话状态和工具调用循环。

**核心循环**:
```
用户消息 → HTTPS chat → 收到 tool_calls → 执行工具 → 
追加 tool 结果 → 再次 HTTPS chat → ... → finish_reason=stop
```

**关键特性**:
- Mutex 保护的线程安全状态管理
- 上下文窗口管理（token 估算 + 自动截断）
- 并行工具执行（多工具调用时）
- 最多 50 轮工具调用（防止无限循环）
- 单工具超时保护（120s）
- Token 使用量累计统计

### PiAgentManager — UI 状态管理

**职责**: 连接 ConversationManager 和 Compose UI，管理 UI 状态和消息展示。

**关键特性**:
- 30fps 流式 flush（33ms 节拍）
- Session 持久化（创建/切换/删除/重命名）
- 上下文压缩（保留近期消息 + 摘要）
- 消息历史加载和恢复
- 工作区文件管理
- 附件处理（图片/文件）

## 数据流

### 发送消息流程

```
用户输入 → PiAgentManager.sendPrompt()
  → ConversationManager.send()
    → ChatHttpClient.chat() (SSE 流)
      → TextDelta → PiAgentManager.applyAssistantDeltas()
      → ThinkingDelta → PiAgentManager.applyAssistantDeltas()
      → Finish(tool_calls) → ConversationManager.executeToolCallsParallel()
        → ToolEventBus.execute() → ToolResult
        → 追加 tool 消息 → 回到 ChatHttpClient.chat()
      → Finish(stop) → TurnEnd
  → PiAgentManager 更新 UI 状态
```

### 上下文管理策略

1. **自动截断**: 当估算 token 数超过 `contextTokenLimit`（默认 32K）时，从中间移除早期消息
2. **手动压缩**: 用户触发 `compact()`，保留最近 1/3 消息 + 生成摘要
3. **保留配对**: 截断时不破坏 `tool_call` / `tool` 消息配对

## 配置系统

### Pi 配置 (`pi-config.json`)

```json
{
  "defaultProvider": "deepseek",
  "defaultModel": "deepseek-chat",
  "defaultThinkingLevel": "auto",
  "env": {
    "DEEPSEEK_API_KEY": "sk-..."
  },
  "appendSystem": "...",
  "modelsText": "..."
}
```

### 模型目录 (`models.json`)

定义各 Provider 的 baseURL、API Key 环境变量名、推荐模型列表。

### Session 存储 (`agent-sessions.json`)

记录所有 Agent Session 的元数据（ID、名称、状态、provider/model 配置等）。

## 安全设计

### 路径安全
- 工作区文件操作有路径越界检查（`require(file.path.startsWith(root.path))`)
- proot rootfs 隔离

### API Key 安全
- Key 存储在应用私有目录
- 不在日志中输出 Key
- 传输使用 HTTPS

### 工具安全
- `run_shell` 在 proot rootfs 内执行，无宿主文件系统访问
- 工具执行有超时保护
- 文件操作限制在工作区内

## 性能优化

### 流式输出
- 33ms flush 节拍（~30fps），平衡流畅度和性能
- StringBuilder 缓冲 pending deltas，避免高频 state 更新

### 上下文管理
- Token 估算（chars/3 粗略估算）
- 自动截断避免 context overflow
- 手动压缩保留关键信息

### 并行工具执行
- 多工具调用时使用 `async` + `awaitAll` 并行执行
- 单工具调用时直接执行，避免 async 开销
