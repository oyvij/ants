package ants

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

class PermissionRequest(val agent: String, val tool: String, val target: String?, val limit: String) {
    val summary: String get() {
        val shown = target?.let { "`${if (it.length > SHOWN) it.take(SHOWN) + "…" else it}`" }
        return "$agent wants to " + when {
            shown == null -> "use $tool"
            tool == "Bash" -> "run $shown"
            tool in setOf("Write", "NotebookEdit") -> "write $shown"
            tool == "Edit" -> "edit $shown"
            tool == "Read" -> "read $shown"
            tool == "WebFetch" -> "fetch $shown"
            tool in AgentLauncher.SUBAGENT_TOOLS -> "start the Subagent $shown"
            else -> "use $tool on $shown"
        }
    }

    val what get() = target ?: tool

    fun fits(rule: String) = RULE.matchEntire(rule.trim())?.groupValues?.get(1) == tool

    fun json(): String = json.writeValueAsString(mapOf("agent" to agent, "tool" to tool, "target" to target, "limit" to limit))

    companion object {
        private const val SHOWN = 120
        private val ASSIGNMENT = Regex("""[A-Za-z_]\w*=.*""")
        private val RULE = Regex("""([\w-]+)(?:\((.*\S.*)\))?""")
        private val json = JsonMapper.shared()

        fun of(text: Any?): PermissionRequest? = (text as? String)?.let { runCatching { json.readValue(it, Map::class.java) }.getOrNull() }?.let {
            PermissionRequest(it["agent"].toString(), it["tool"].toString(), it["target"] as? String, it["limit"].toString())
        }

        fun granted(text: Any?): List<String> = (text as? String)
            ?.let { runCatching { json.readValue(it, List::class.java).map(Any?::toString) }.getOrNull() }.orEmpty()

        fun grantedJson(rules: List<String>): String = json.writeValueAsString(rules.distinct())

        fun commands(command: String): List<List<String>> = command.split(Regex("""&&|\|\||[;|&()\n]"""))
            .map { part -> part.trim().split(Regex("\\s+")).filter(String::isNotEmpty).dropWhile { it.substringAfterLast('/') == "env" || ASSIGNMENT.matches(it) } }
            .filter { it.isNotEmpty() }

        fun target(input: Map<*, *>?): String? = listOf("command", "file_path", "notebook_path", "url", "subagent_type", "pattern")
            .firstNotNullOfOrNull { input?.get(it) as? String }?.takeIf(String::isNotBlank)

        fun limit(tool: String, target: String?, cwd: Path): String = when {
            target == null -> tool
            tool == "Bash" -> commands(target).let { all -> all.firstOrNull { it.first() != "cd" } ?: all.firstOrNull() }?.let { "Bash(${it.first()} *)" } ?: tool
            tool == "Write" || tool == "Edit" -> {
                val folder = Path.of(target).let { if (it.isAbsolute) it else cwd.resolve(it) }.normalize().parent
                val inside = cwd.toAbsolutePath().normalize()
                when {
                    folder == null -> tool
                    folder == inside -> "$tool(**)"
                    folder.startsWith(inside) -> "$tool(${inside.relativize(folder)}/**)"
                    else -> "$tool(/$folder/**)"
                }
            }
            else -> tool
        }
    }
}
