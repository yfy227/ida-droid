package dev.idadroid.agent

/**
 * V5: MCP 文件桥 — agent 层与 host 文件系统 / ida-mcp 会话的解耦接口。
 *
 * 设计动机(用户需求: "传入文件不再手动而是可以直接通过 mcp 调用主动拉文件进去"):
 * 旧流程: 用户在 UI 用 SAF 选择器手动上传 → manifest → agent 用 run_shell 调
 *         idadroid-file 脚本 → HTTP 桥 → 传输 → mcpc open_file(shell 文本链路,脆弱且慢)
 * 新流程: AI 直接 function calling 调 [PullFileTool] → 本接口 → host 原生执行(结构化,
 *         去重, 可选直开 IDA), 全程无需用户手动操作。
 *
 * V5.1 扩展(用户需求: "增加更多辅助 mcp 能力扩展"):
 * - [callMcpTool]  — host 侧直调 ida-mcp 工具(结构化, 替代 run_shell 里的 mcpc 文本链路)
 * - [listMcpTools] — 列出 ida-mcp 会话当前暴露的工具
 * - [mcpStatus]    — 会话健康状态(endpoint/pid/最近健康检查)
 *
 * 实现侧(mcp 包)由 UI 层注入, agent 层零 mcp 依赖。
 */
interface McpFileBridge {

    /** 宿主文件候选(搜索结果) */
    data class HostCandidate(
        val name: String,
        val hostPath: String,
        val size: Long,
        val lastModified: Long
    )

    /** ida-mcp 工具描述 */
    data class McpToolInfo(
        val name: String,
        val description: String
    )

    /** 按名字搜索宿主文件(Download/Documents/DCIM/workspace 等), 新的在前 */
    fun searchHost(name: String): List<HostCandidate>

    /** 传输宿主文件进容器, 返回容器内路径(内容指纹去重, 重复拉取复用) */
    suspend fun transfer(hostPath: String): Result<String>

    /** 经 ida-mcp 在容器内 IDA 中打开文件(可选能力) */
    suspend fun openInIda(prootPath: String): Result<Unit>

    /** ida-mcp 会话端点(未启动返回 null — 工具输出里给用户提示) */
    fun mcpEndpoint(): String?

    /** host 侧直调 ida-mcp 工具(替代 run_shell → mcpc 的文本链路) */
    suspend fun callMcpTool(toolName: String, argsJson: String): Result<String>

    /** 列出 ida-mcp 会话暴露的工具(未连接返回 null) */
    suspend fun listMcpTools(): List<McpToolInfo>?

    /** 会话状态摘要(状态/端点/pid/健康), 已 JSON 化便于直接进工具输出 */
    fun mcpStatusJson(): String
}
