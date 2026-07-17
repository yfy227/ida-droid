# IDAdroid — Android 端 IDA Pro 逆向工程 + AI Agent 平台

IDAdroid 将完整的 IDA Pro 逆向工程环境、ida-mcp 工具链和 AI Agent 集成到 Android 设备上，通过 proot 运行 Linux rootfs，实现无需 root 的移动端逆向分析体验。

## 核心功能

### 1. IDA 图形界面
通过 proot 内启动 X/VNC server + IDA Pro，内置 VNC 客户端直接连接，在手机上操作 IDA 图形界面。

### 2. proot 终端
在同一 rootfs 中提供交互式 shell，支持完整的 Linux 命令行工具链。

### 3. AI Agent 聊天
自研三层对话引擎（ChatHttpClient + ToolEventBus + ConversationManager），支持：
- **流式对话** — SSE 实时流式输出，30fps flush 节拍
- **思考过程展示** — 支持 DeepSeek-R1、Claude、o1 等推理模型
- **多 Provider 支持** — OpenAI、Anthropic、DeepSeek、Google Gemini、通义千问、豆包等 16+ 平台
- **智能模型推荐** — 每个 Provider 附带常用模型列表，一键添加
- **API 连接测试** — 配置后可一键验证 API Key + 端点可用性
- **模型列表拉取** — 从 Provider API 动态获取可用模型列表
- **配置导入导出** — 完整 AI 配置可导出为 JSON 备份，支持导入恢复
- **API Key 格式校验** — 输入时即时检查 Key 前缀和长度
- **端点自动补全** — 裸域名自动追加 `/v1/chat/completions`，Anthropic 自动追加 `/v1/messages`
- **网络重试** — 指数退避重试（429/5xx，最多 3 次），适合移动网络环境
- **上下文管理** — Token 估算 + 自动截断 + 手动压缩
- **并行工具调用** — 多工具调用时并行执行，提升效率
- **工具调用超时保护** — 单工具 120s 超时，防止对话阻塞
- **附件上传** — 图片/文件自动传输到容器
- **Session 管理** — 创建/切换/删除/重命名，持久化存储
- **深度索引模式** — CodeGraph + ECC + codebase-memory 联动分析
- **文件传输桥** — Android 主机 ⇄ 容器文件传输

## 技术架构

```
Android App (Kotlin / Jetpack Compose / Material 3)
├── AI 对话引擎 (三层分层)
│   ├── Layer 1: ChatHttpClient — HTTPS SSE 流式对话
│   ├── Layer 2: ToolEventBus — 工具执行总线
│   └── Layer 3: ConversationManager — 对话编排器
├── proot rootfs (完整 Linux 环境)
│   ├── IDA Pro 9.3
│   ├── ida-mcp (MCP server for IDA)
│   ├── jadx / python / npm
│   └── .idadroid/ (配置、脚本、日志)
├── terminal-emulator (Termux 终端模拟器)
├── terminal-view (终端 UI 组件)
└── VNC 客户端 (推荐 AVNC)
```

### 项目结构

| 模块 | 说明 |
|------|------|
| `app/` | 主应用：UI、Agent 管理、proot 运行时、配置 |
| `terminal-emulator/` | Termux 终端模拟器库 |
| `terminal-view/` | 终端 View 组件 |
| `plan/` | 设计文档和实现计划 |
| `docs/` | 技术文档 |

### 关键源文件

| 文件 | 职责 |
|------|------|
| `ChatHttpClient.kt` | Layer 1: HTTPS SSE 流式对话客户端 |
| `ToolEventBus.kt` | Layer 2: 工具执行总线 |
| `ConversationManager.kt` | Layer 3: 对话编排器（状态管理 + 工具循环） |
| `PiAgentManager.kt` | UI 状态管理 + 事件分发 + Session 管理 |
| `PiConfigManager.kt` | Pi 配置读写、系统提示词 |
| `AiConfigEditor.kt` | 可视化 AI Provider/Model 配置 UI |
| `AiConfigTools.kt` | 配置导入导出、API 连接测试、模型列表拉取 |
| `EndpointCompleter.kt` | API 端点 URL 自动补全 |
| `AgentModelCatalog.kt` | models.json 解析与模型目录 |
| `BoxedAgentLikeScreen.kt` | Agent 聊天主界面 |
| `IdaProotRuntime.kt` | proot 进程启动与参数管理 |

## 构建

### 环境要求

- Android Studio (AGP 9.0+)
- JDK 17
- Kotlin 2.3.21
- Android SDK 36 (compileSdk)
- NDK 29.0.14206865
- minSdk 26 (Android 8.0)
- targetSdk 28

### 构建步骤

```bash
# Debug build
./gradlew assembleDebug

# Release build (需要配置签名)
# 在 local.properties 中设置:
# IDADROID_RELEASE_STORE_FILE=...
# IDADROID_RELEASE_STORE_PASSWORD=...
# IDADROID_RELEASE_KEY_ALIAS=...
# IDADROID_RELEASE_KEY_PASSWORD=...
./gradlew assembleRelease
```

## 使用

1. **导入 rootfs**：首次启动需要导入预构建的 IDA rootfs 镜像
2. **配置 AI Provider**：在设置页选择 Provider，填入 API Key，添加模型
3. **启动 Agent Session**：创建新会话，选择模型，开始对话
4. **逆向分析**：Agent 可通过 mcpc 调用 ida-mcp 操作 IDA，也可使用 jadx 等工具
5. **文件传输**：通过 idadroid-file 桥在 Android 主机和容器之间传输文件

## AI Provider 配置

支持的开箱即用 Provider：

| Provider | 环境变量 | 常用模型 |
|----------|----------|----------|
| OpenAI | `OPENAI_API_KEY` | gpt-4o, o1, o3 |
| Anthropic Claude | `ANTHROPIC_API_KEY` | claude-sonnet-4, claude-opus-4 |
| DeepSeek | `DEEPSEEK_API_KEY` | deepseek-chat, deepseek-reasoner |
| Google Gemini | `GOOGLE_API_KEY` | gemini-2.5-flash, gemini-2.5-pro |
| OpenRouter | `OPENROUTER_API_KEY` | 300+ 聚合模型 |
| Moonshot KIMI | `MOONSHOT_API_KEY` | moonshot-v1-128k, kimi-thinking |
| 阿里通义千问 | `DASHSCOPE_API_KEY` | qwen-max, qwen-plus |
| 火山引擎豆包 | `ARK_API_KEY` | doubao-1-5-pro |
| 百度千帆 | `BAIDU_API_KEY` | ernie-4.0 |
| 腾讯混元 | `HUNYUAN_API_KEY` | hunyuan-turbos |
| SiliconFlow | `SILICONFLOW_API_KEY` | DeepSeek-V3, Qwen2.5-72B |
| Mistral AI | `MISTRAL_API_KEY` | mistral-large, codestral |
| Groq | `GROQ_API_KEY` | llama-3.3-70b (超低延迟) |
| xAI Grok | `XAI_API_KEY` | grok-3 |
| Together AI | `TOGETHER_API_KEY` | Llama-3.3-70B, Qwen2.5-72B |
| 自定义 | — | 手动填写 Base URL |

## 文档

- [架构文档](docs/architecture.md) — 系统架构、模块设计、数据流
- [开发指南](docs/development.md) — 环境配置、开发流程、常见问题
- [项目审计](docs/project-audit.md) — 问题分析与优化记录
- [计划文档](plan/README.md) — 架构设计、实现路线、风险评估
- [深度索引模式](docs/deep-index-mode.md) — CodeGraph + ECC + Memory 工具链

## 许可证

本项目仅供合法的逆向工程研究和学习使用。
