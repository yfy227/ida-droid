# V7 重写蓝图 — 全量重构 UI / Agent / MCP

> 2026-11-03 老板指令: "全量重构优化整体UI界面功能, 所有的agent mcp功能全部重写, 确定正确可用"
> 纪律: 文档驱动(100+轮接力), 每层重建后字节审计+真机验证清单

## 病根诊断(V6.8 审计结论)

**真相源分裂(Session State Trifold)**: 消息/统计存在于三处且互不同步 —
1. ConversationEngine 内存(运行态) 2. session 文件(jsonl) 3. UI AgentUiState
→ 所有 P0 的共同根源: 会话串数据(engine 不 reset)、累积丢失(stats 不持久)、重启丢历史。

## 四层架构(重建目标)

```
┌─ L4 UI Layer(Compose)          — 纯展示, 只读 store 快照, 零业务逻辑
├─ L3 Orchestrator(PiAgentManager 瘦身) — turn 编排/事件路由, 无状态
├─ L2 Engine(ConversationEngine)  — 无状态执行器: 输入(消息+config)→输出(事件流)
│    └ ChatHttpClient / ToolRegistry / McpFileBridge
└─ L1 SessionStore(唯一真相源)     — 会话/消息/统计/配置, 原子持久化
```

**核心契约**:
- engine 不再持跨 turn 状态: send(messages, config) → Flow<event>; turn 末尾 Orchestrator 回写 store
- SessionStore: appendMessage(sessionId, msg) / messages(sessionId) / stats(sessionId) / patchStats(sessionId, delta)
- 统计随消息持久化(每 turn 末追加 usage 快照行) → 重启后累积保留
- UI 只订阅 store.flow(sessionId) → 单向数据流

## 分轮计划

- **V6.9(本轮) L1 落地**: SessionStore 新建 + PiAgentManager 最小侵入接线(load/persist/stats 走 store)+ 统计持久化(修复"重启累积归零")
- V7.0: L2 engine 无状态化(send 接收全量消息, 删内部 ContextWindow 生命周期)
- V7.1: L3 瘦身(turn 状态机, 事件→store 直写)
- V7.2: L4 UI 重排(全部订阅 store, 删 AgentUiState 冗余字段)
- V7.3: MCP 层重写(ExternalMcpServer 走 store + 会话级权限)


## 参照系对照表(2026-11-03 老板指令: "全盘重建看DSHA和codex")

| 重建层 | DSHA 取什么 | Codex 取什么 |
|---|---|---|
| L1 SessionStore | 会话持久化+备份不丢(单真相源即 DSH 会话存储插件化思想内核) | - |
| L2 Engine | everything is a plugin: 模型 provider/协议适配器全插件化(dsh.bundle 声明式) | 工具调用紧凑语义(单行摘要+状态点) |
| L3 Orchestrator | 插件生命周期编排(启用即注册/禁用即摘除, 幂等) | turn 状态机(phase 语义) |
| L4 UI | 浮窗外壳生态(共享容器+可挂载面板+最小化到球) | 单列呼吸排版/斜杠命令/计划步骤卡/底部状态行/inline approval |
