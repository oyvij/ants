package ants

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

@Component
class ScriptTools(private val scripts: Scripts, private val runner: ScriptRunner) {
    private val json = JsonMapper.shared()

    fun of(allowed: List<Script>, workspace: Path, run: String): McpTools {
        val named = toolNames(allowed)
        return object : McpTools {
            override val tools = named.map { (name, script) -> mapOf("name" to name, "description" to script.description, "inputSchema" to ScriptSchema.EMPTY + script.inputs.entries.associate { (key, value) -> "$key" to value }) }

            override fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean> {
                val script = named.getValue(name)
                val result = runner.run(scripts.file(script), workspace, run, arguments.entries.associate { (key, value) -> "$key" to value }, script.inputs, script.vars, scripts.secretEnvironment(script, scripts.ownSecrets(script)))
                return json.writeValueAsString(mapOf("exitCode" to result.exitCode, "output" to withoutSource(result.output, scripts.file(script)), "payload" to result.payload)) to (result.exitCode != 0)
            }
        }
    }

    private fun withoutSource(output: String, file: Path): String =
        output.replace(Regex("""(?m)^((?:file://)?${Regex.escape(file.toAbsolutePath().toString())}:\d+)\n.*\n[ \t]*\^+[ \t]*$"""), "\$1\n(Ants hides the Script's line.)")

    companion object {
        const val PREFIX = "script_"

        private const val LONGEST = 64 - Mcp.CLAUDE_PREFIX.length

        fun toolNames(scripts: List<Script>): Map<String, Script> {
            val named = linkedMapOf<String, Script>()
            for (script in scripts.distinctBy { it.id }.sortedWith(compareBy({ it.name }, { it.id }))) {
                val base = PREFIX + script.name.ifBlank { script.id }.replace(Regex("[^A-Za-z0-9_-]+"), "_")
                val name = generateSequence(1, Int::inc).map { if (it == 1) base.take(LONGEST) else base.take(LONGEST - "_$it".length) + "_$it" }.first { it !in named }
                named[name] = script
            }
            return named
        }
    }
}
