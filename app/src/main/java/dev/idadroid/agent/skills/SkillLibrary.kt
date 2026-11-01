package dev.idadroid.agent.skills

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * V6 技能库 — 单例。
 *
 * 职责:
 * 1. 启动加载 assets/skills/index.json(内置技能)
 * 2. 合并 learned 技能(ExperienceEngine 晋升, 持久化在 filesDir/skills/learned.json)
 * 3. 构建系统提示词注入段(索引级: 每技能 1-2 行 + 触发词)
 * 4. 技能正文供 cat 读取(proot 内 /root/.idadroid/skills 挂载点由宿主写)
 */
object SkillLibrary {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    @Volatile
    private var skills: List<Skill> = emptyList()

    @Volatile
    private var loaded = false

    /** learned 技能持久化路径(宿主 filesDir, 随 app 数据存续) */
    private fun learnedFile(context: Context): File =
        File(context.filesDir, "skills/learned.json")

    /** 初始化: 内置 + learned 合并。幂等。 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val builtin = runCatching {
            context.assets.open("skills/index.json").bufferedReader().use { it.readText() }
        }.map { raw -> json.decodeFromString(SkillIndex.serializer(), raw).skills }
            .getOrDefault(emptyList())
        val learned = runCatching {
            val f = learnedFile(context)
            if (f.isFile) json.decodeFromString(SkillIndex.serializer(), f.readText()).skills
            else emptyList()
        }.getOrDefault(emptyList())
        skills = builtin + learned
        loaded = true
    }

    fun all(context: Context): List<Skill> { ensureLoaded(context); return skills }

    fun enabled(context: Context): List<Skill> = all(context).filter { it.enabled }

    /** 提示词注入段 — 索引级摘要(每技能一行, 控制在 ~40 行内) */
    fun promptSection(context: Context, limit: Int = 40): String {
        val act = enabled(context)
        if (act.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("## 技能库(按需取用, 全文: cat /skill/<id>.md)")
            act.sortedBy { it.category }.take(limit).forEach { s ->
                val trig = if (s.triggers.isEmpty()) "" else " 触发: ${s.triggers.joinToString("/")}"
                appendLine("- [${s.id}] ${s.name} (${s.category}) — ${s.summary}$trig")
            }
        }
    }

    /** 提示词注入段 — 失败签名避坑(负强化) */
    fun failureSection(store: ExperienceStore, max: Int = 5): String {
        val hot = store.failures
            .filter { it.occurrences >= 2 || System.currentTimeMillis() - it.lastSeenMs < 24 * 3600_000L }
            .sortedByDescending { it.occurrences }
            .take(max)
        if (hot.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("## 已知坑(近期失败教训, 优先换路线)")
            hot.forEach { f ->
                appendLine("- ${f.toolName} [${f.errorKind}] ×${f.occurrences}: ${f.sampleMessage}")
            }
        }
    }

    /** learned 技能增删(晋升/删除) */
    @Synchronized
    fun upsertLearned(context: Context, skill: Skill) {
        ensureLoaded(context)
        val f = learnedFile(context)
        f.parentFile?.mkdirs()
        val learned = skills.filter { it.origin == "learned" }.filter { it.id != skill.id } + skill
        skills = skills.filter { it.origin != "learned" || it.id == skill.id }.let { cur ->
            cur.filter { it.id != skill.id } + skill
        }
        f.writeText(json.encodeToString(
            SkillIndex.serializer(), SkillIndex(learned)))
    }

    @Synchronized
    fun removeLearned(context: Context, id: String) {
        ensureLoaded(context)
        skills = skills.filter { !(it.origin == "learned" && it.id == id) }
        val f = learnedFile(context)
        val learned = skills.filter { it.origin == "learned" }
        f.writeText(json.encodeToString(SkillIndex.serializer(), SkillIndex(learned)))
    }

    /** 技能正文导出到指定目录(供 proot cat) */
    fun exportBodies(context: Context, targetDir: File) {
        ensureLoaded(context)
        targetDir.mkdirs()
        context.assets.list("skills")?.forEach { name ->
            if (name.endsWith(".md")) {
                runCatching {
                    val out = File(targetDir, name)
                    if (!out.isFile) {
                        context.assets.open("skills/$name").use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
        }
    }
}
