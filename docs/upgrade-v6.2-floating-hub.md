# V6.2 悬浮窗生态扩展 — DSHA 调研结论

> 2026-11-01 | 老板指令: "悬浮窗支持更多的生态扩展性应该参考DSHA" + "不要轻易编译,先调研"

## 一、DSHA/DSH 悬浮窗生态的精髓(全网调研)

DSH 的核心哲学: **everything is a plugin** — 模型/工具/沙箱/UI 全可插拔(dsh.bundle 声明能力)。悬浮窗生态三个标杆:

1. **dsh-float-window(共享浮窗外壳)**: 任何 web 插件都能挂进浮窗 — float/dock/minimize 三态、拖动、边缘吸附、角缩放、多窗堆叠、boss keys
2. **dsh-bili-widget(B站小窗)**: 置顶、拖动缩放、**最小化到悬浮球(点击恢复,状态不丢)**、迷你/专注模式
3. **dsh-hud(状态悬浮)**: Git/MCP/技能/模型/Token 用量一览
4. **Device Mirror**: shell.overlay slot — 一个 slot 承载 N 窗,每窗独立,会话归属+持久化

## 二、IDAdroid 移植设计

### FloatingHub(悬浮中枢)
- **悬浮球**: 可拖动+边缘吸附+位置记忆(SharedPreferences),常驻
- **点击展开浮窗面板容器**(WindowManager + ComposeView):
  - 内置面板×3: ①状态 HUD(模型/阶段/Token/工具数) ②快捷会话(直接发消息给 agent) ③工具快捷栏(插件工具一键调)
  - **插件可挂载**: PluginManifest 加 `floatingPanel: Boolean` 字段 — 启用即出现在浮窗工具快捷栏
- **权限**: SYSTEM_ALERT_WINDOW(Settings.canDrawOverlays 检查, 未授权引导跳转)
- **最小化到球**: 面板关闭=收进球, 球点击再展开, 状态保留

### 技术要点
- Service: FloatingHubService(前台可宿主, WindowManager type APPLICATION_OVERLAY)
- ComposeView 需 LifecycleOwner+ViewModelStoreOwner+SavedStateRegistryOwner(attach baseContext)
- 拖动: setOnTouchListener 手势分离(点击 vs 拖动, 8px 阈值)

## 三、其他 V6.2 修复(本轮已落盘)
1. P0 崩溃: manifest 补 WAKE_LOCK + KeepAliveService acquire 兜底(配置完对话崩溃根因)
2. 配置自动保存: PiSettingsTab 800ms 防抖自动落盘 + 旧"保存"按钮删除 + 顶部已保存指示
3. 旧编辑器退役: PiSettingsTab 不再跳 AgentModelsEditorScreen(配置弹窗直进新 AiConfigEditor)
4. DSHA 式市场: addCustomSource(URL/JSON) + custom_market.json 持久化 + 市场"添加插件源"UI
5. SkillsScreen 独立技能专区 + Home 技能库入口卡
6. 配置弹窗文案更新(指向新配置器)
