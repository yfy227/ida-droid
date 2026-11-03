# V7 重写·全量问题清单(验收基准)

> 2026-11-03 老板令: "存在大量问题…不止描述的这些,问题一个接一个,全部从底层重构,不要补丁似的"
> 本清单 = 已知问题 + 审计发现的深层问题。**每项必须在新架构里结构性消失(不是修补)**

## A. 会话层(已爆发)
- A1 会话串数据: engine 内存当真相源, 切会话不 reset → V6.8 补丁修, 但 load/restore 仍绕三层
- A2 统计丢失: activeStats 全程未赋值(V6.7)+ 重启归零(V6.8)+ 当前轮延迟显示(V6.8)——三次补丁才对
- A3 持久化双轨: persistMessages(jsonl/DTO) 与 store(jsonl/StoreLine) 并存, 任何一条不写就丢数据
- A4 【深层】消息恢复只回灌 user/assistant 文本——tool 对话恢复后上下文断裂(模型看不到之前的工具结论)

## B. 发送链(老板实测"传文件后报错静默退出")
- B1 附件链三段(launcher→store→expand)各自 runCatching, 错误吞进日志用户看不到
- B2 sendingInProgress 布尔+mutex 双保险, 但 turn 卡死时 UI 只显示"仍在发送中", 无 abort 引导
- B3 【深层】sendPromptInternal 300+ 行巨函数: 附件/统计/保活/事件路由/持久化全部内联——单点修改必炸别处(V6.7→V6.8 连环 regression 的直接原因)

## C. 引擎层
- C1 ContextWindow 生命周期横跨 turn(reset/restore/compact 三处操控)——状态机不闭合
- C2 【深层】工具轮循环(多轮 function calling)在 engine.send 单协程里, abort 只能协作式取消
- C3 SSE 解析与重试耦合在 ChatHttpClient(读流+解析+退避+事件发射四职责)

## D. UI 层
- D1 AgentUiState 30+ 字段大杂烩(会话/流式/统计/队列/工作区), 任何字段不同步=UI 僵尸
- D2 消息流渲染(groupMessagesForRender)与 store 无关, 纯 UI 侧拼装——真相源再次分裂
- D3 【深层】配置弹窗/ModelsEditor/AiConfigEditor 三入口残留历史(V6.2 修一半)

## E. MCP 层
- E1 桥接四态(未接线/已接线/容器未起/ida-mcp未连)用户全部无感, 只在工具报错里出现
- E2 ExternalMcpServer 独立于 agent 会话——外部推文件后无会话感知
- E3 【深层】mcp_call 超时/重试分散在 Tool/McpCallTool/Registry 三层, 语义不一致

## 重写验收基准(结构性消失的判定)
每项问题在新架构中由**架构保证**而非代码修补:
- A 类: L1 单真相源(已建 SessionStore), 消息恢复含 tool 对(round-trip)
- B 类: L3 TurnOrchestrator 状态机——每状态有出口(错误/超时/abort 全部路由到 store+UI)
- C 类: L2 无状态 Runner: run(messages, config) → Flow<EngineEvent>, 挂起即可弃
- D 类: UI 只订阅 store.flow, 删 AgentUiState 冗余字段
- E 类: MCP 会话级状态机(桥状态可视化+统一超时语义)
