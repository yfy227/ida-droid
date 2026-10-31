# IDAdroid 开发指南

> 更新日期: 2026-07-14

## 环境要求

- Android Studio (Ladybug+)
- JDK 17
- Android SDK 35+ (compileSdk)
- Kotlin 2.3.21
- Gradle 9.0.1+ (AGP)

## 构建配置

### Debug 构建

```bash
./gradlew assembleDebug
```

### Release 构建

需要在 `local.properties` 或环境变量中配置签名：

```properties
IDADROID_RELEASE_STORE_FILE=/path/to/keystore
IDADROID_RELEASE_STORE_PASSWORD=***
IDADROID_RELEASE_KEY_ALIAS=***
IDADROID_RELEASE_KEY_PASSWORD=***
```

## 项目结构

详见 [架构文档](architecture.md)。

## AI 对话引擎开发指南

### 添加新的 LLM Provider

1. 在 `models.json` 中添加 provider 配置：

```json
{
  "providers": {
    "my-provider": {
      "baseURL": "https://api.my-provider.com/v1",
      "envKey": "MY_PROVIDER_API_KEY",
      "label": "My Provider"
    }
  },
  "models": {
    "my-provider": [
      { "id": "my-model", "name": "My Model", "reasoning": false }
    ]
  }
}
```

2. 如果 Provider 使用标准 OpenAI 兼容 API，无需额外代码。
3. 如果 Provider 有特殊 API 格式，在 `ChatHttpClient` 中添加适配逻辑。

### 添加新的工具

1. 在 `ToolEventBus.kt` 的 `toolDefinitions()` 中添加工具定义：

```kotlin
add(JsonObject(mapOf(
    "type" to JsonPrimitive("function"),
    "function" to JsonObject(mapOf(
        "name" to JsonPrimitive("my_tool"),
        "description" to JsonPrimitive("My tool description"),
        "parameters" to JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "input" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("Input parameter")
                ))
            )),
            "required" to JsonArray(listOf(JsonPrimitive("input")))
        ))
    ))
)))
```

2. 在 `execute()` 方法中添加执行逻辑：

```kotlin
"my_tool" -> {
    val input = args["input"]?.jsonPrimitive?.contentOrNull ?: return "错误: 缺少 input 参数"
    // 执行工具逻辑
    "结果: $input"
}
```

3. 如需自定义成功/失败判断，在 `isToolResultSuccess()` 中添加规则。

### 修改 System Prompt

System prompt 定义在 `PiConfigManager.kt` 的 `defaultSystemAppendPrompt()` 函数中。

**设计原则**:
- 精简：只包含环境信息和核心准则，工具说明由 tool definitions 提供
- 结构化：使用 markdown 标题分段
- 可覆盖：用户可在 Pi 配置中自定义 `appendSystem`

### 修改上下文管理策略

上下文管理在 `ConversationManager.kt` 中：

- `contextTokenLimit`: 估算 token 上限（默认 32K）
- `trimContextIfNeeded()`: 自动截断逻辑
- `estimateTokens()`: token 估算（chars/3）
- `PiAgentManager.compact()`: 手动压缩逻辑

### 调试

#### 日志

```kotlin
android.util.Log.d("ChatHttpClient", "SSE line: $line")
android.util.Log.e("PiAgentManager", "Error", e)
```

#### 状态检查

```kotlin
// 查看 UI 状态
piAgentManager.state.value

// 查看消息历史
piAgentManager.state.value.messages

// 查看 token 使用量
piAgentManager.conversationManager.getTokenUsage()
```

## UI 开发指南

### 主要页面

| 页面 | 文件 | 说明 |
|------|------|------|
| Home | `HomeScreen.kt` | 主界面，包含 IDA/VNC/Terminal/Agent 标签 |
| Agent Chat | `BoxedAgentLikeScreen.kt` | AI 对话界面 |
| AI Config | `AiConfigEditor.kt` | AI Provider/Model 配置 |
| Settings | `SettingsScreen.kt` | 应用设置 |
| About | `AboutScreen.kt` | 关于页面 |

### 添加新的 UI 状态字段

1. 在 `AgentModels.kt` 的 `AgentUiState` 中添加字段
2. 在 `PiAgentManager` 中更新状态时包含该字段
3. 在 UI 中通过 `state.value.fieldName` 访问

## 测试

### 单元测试

```bash
./gradlew test
```

现有测试：
- `PiMessageNormalizerTest`: 消息标准化测试

### 添加测试

```kotlin
class MyTest {
    @Test
    fun testSomething() {
        // 测试代码
    }
}
```

测试文件放在 `app/src/test/java/dev/idadroid/` 下。

## 常见问题

### Q: 如何添加新的 API Key 环境变量？

在 `PiConfigManager.kt` 的 `resolveConvConfig()` 中的 `envKeyForProvider` 映射中添加。

### Q: 如何修改流式输出的刷新频率？

修改 `PiAgentManager.kt` 中的 `STREAM_FLUSH_INTERVAL_MS` 常量（默认 33ms）。

### Q: 如何修改工具调用超时？

修改 `ConversationManager.ConvConfig.toolTimeoutMs`（默认 120s）。

### Q: 如何修改最大工具调用轮次？

修改 `ConversationManager.ConvConfig.maxToolRounds`（默认 50）。

### Q: 如何修改 prompt 超时？

修改 `PiAgentManager.kt` 中的 `PROMPT_TIMEOUT_MS` 常量（默认 180s）。

## 代码规范

- Kotlin 代码遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 `runCatchingSuspending`（非 `runCatching`）处理 suspend 函数的异常
- 使用 `Mutex` 保护共享可变状态
- 使用 `@Volatile` 标注跨协程访问的可变字段
- 注释使用中文，代码使用英文
