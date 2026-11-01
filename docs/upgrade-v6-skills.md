# IDAdroid V6 — 技能系统 + 经验强化学习 + 分组 UI 完美化

> 2026-10-31 | 老板指令: "全面优化升级学习各种顶级的技能, 强化学习, 让他的分组UI更加完美"

## 一、需求解读

1. **学习顶级技能** → Agent 内置技能库(参考 Claude Code SKILL.md / Voyager skill library / DSHA 技能卡):
   - 声明式技能文档(触发词/流程/工具组合), 索引注入系统提示词省 token, 模型按需读全文
2. **强化学习** → 经验引擎(非学术 RL, 是 Voyager/Reflexion 式 experience-driven):
   - 正强化: 成功工具序列自动提炼模式 → 高频模式晋升为动态技能
   - 负强化: 失败签名(工具+错误类型)记入"已知坑", 下轮提示词附加避坑
   - 衰减: 30 天未用降权, 防技能库膨胀
3. **分组 UI 完美化** → 三处:
   - 消息流: 连续 tool 卡片折叠为"工具组"(参考 Claude Code 折叠组, 带耗时/状态汇总)
   - 会话列表: 按时间分组(今天/昨天/近7天/更早)
   - 插件市场: 按分类分组(逆向/动态分析/实用工具)

## 二、架构

### A. 技能系统 (agent/skills/)

```
assets/skills/index.json     # 技能索引: id/name/category/triggers/summary/file/enabled
assets/skills/*.md           # 技能正文(markdown: 何时用/流程/工具组合/注意事项)
agent/skills/SkillModels.kt  # Skill/SkillIndex DTO (kotlinx.serialization)
agent/skills/SkillLibrary.kt # 单例: 加载索引+过滤启用+构建提示词注入段+运行时增删
```

- 系统提示词注入 = **索引摘要**(每技能 1-2 行 + 触发词), 全文模型用 run_shell `cat /skill/xxx.md` 读
  (proot 内挂载 assets? 不 — 技能正文随 APK assets, 由 proot 启动时同步到 /root/.idadroid/skills/,
  兼用户自建技能同目录)
- 动态技能(经验引擎晋升的)与内置技能同结构, 存 filesDir, 合并呈现

### B. 经验引擎 (agent/skills/ExperienceEngine.kt)

```
ExperienceEntry  — (ts, turnId, tool, argsFingerprint, status: Ok/Error/Timeout/Empty, durationMs, retries)
FailureSignature — (tool, errorClass) → count, lastTs, sampleMsg
LearnedPattern   — (toolSequence[], argTemplate, successCount, lastUsedTs) → 升级阈值 SUCCESS_PROMOTE=3
```

数据流: ConversationEngine 每个 ToolResult → engine.record(entry); 轮 Finish → engine.endTurn()
- record: 崩溃安全内存缓冲; endTurn 时批量持久化(filesDir/experience.json, 原子写)
- endTurn: (1) 更新 FailureSignature 计数 (2) 提取本轮成功序列 → 匹配已有 pattern (successCount++)
  或新建 (count=1) (3) pattern.successCount>=3 且长度>=2 → promote 为动态技能
- 负记忆注入: PiConfigManager 构建提示词时调 engine.negativeHints() → "已知失败模式: run_shell+svc…
  (3 次失败), 优先换 pull_file 路线"  — 只取 top3 按频次
- 衰减: 加载时过滤 lastUsedTs > 30 天的 pattern(降级), failure 7 天自动清除

### C. 分组 UI

1. **ToolGroupCard** (ui/components/ToolGroupCard.kt):
   - BoxedAgentLikeScreen 渲染层: 连续 role="tool" 消息聚合为一个可折叠卡
   - 头: "▸ 工具调用 ×3 · 12.4s · ✓✓✗" (次数/总耗时/逐个状态点)
   - 展开: 原有单条工具卡纵向排列
2. **会话时间分组** (IdaDroidApp 侧栏 renderSessions):
   - groupSessionsByTime(lastModified): 今天/昨天/近7天/更早, 组标题 stickyHeader 样式
3. **市场分类分组** (PluginMarketScreen):
   - manifest 加 category 字段(逆向/动态分析/实用工具), LazyColumn 按组渲染 + 吸顶组标题

### D. 技能面板 (ui/SkillsScreen.kt)

- 列表: 内置(蓝标)+学习到(绿标 "RL")+用户自建(紫标), 开关启用, 查看详情(全文渲染)
- 学习到的 pattern 晋升的技能: 显示"来源: 3 次成功使用"
- 入口: HomeScreen 卡片 + IdaDroidScreen.Skills 路由

## 三、改动文件清单

新增 7: SkillModels.kt / SkillLibrary.kt / ExperienceEngine.kt / index.json / 8个技能md / ToolGroupCard.kt / SkillsScreen.kt
修改 6: ConversationEngine / PiConfigManager / BoxedAgentLikeScreen / IdaDroidApp / PluginMarketScreen / HomeScreen
assets plugins/market.json 加 category 字段

## 四、内置技能(8 个, 浓缩自本工作区沉淀)

1. skill-recon.md        — 样本初诊(指纹/熵/壳检测/入口定位) 三步法
2. skill-unpack.md       — 壳识别与脱壳路线选择(360/梆梆/T盾特征表)
3. skill-ida-auto.md     — ida-mcp 自动化循环(函数命名/交叉引用/结构体批量)
4. skill-frida-trace.md  — Frida 动态跟踪(hook 模板/参数打印/主动调用)
5. skill-protocol.md     — 协议还原(抓包分层/密钥矩阵/签名公式逆向)
6. skill-vm-restore.md   — VM/字节码还原(调度表定位/dispatcher 分析)
7. skill-report.md       — 交付级报告结构(证据链/复现步骤/四定律式定论)
8. skill-batch.md        — 批量文件处理管线(指纹去重/断点续跑/产物校验)

## 五、验证门禁

- 字节断言: SkillLibrary/ExperienceEngine 关键符号; index.json 8 技能
- 分组逻辑: groupSessionsByTime 单元逻辑(纯函数抽到 ui/Grouping.kt 可测)
- 编译: gradlew assembleDebug 全绿(云端已建好 SDK/NDK/JDK17/镜像)
- 交付: APK + push git(ghp_4K…TcL6)
