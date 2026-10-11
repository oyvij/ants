package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

/** Who does a Task: the CLI it runs, its tools, its model and its standing instructions. */
class Agent(val name: String, val cli: String, val tools: List<String>?, val model: String?, val instructions: String)

/**
 * A `SKILL.md` and the folder it lives in, with the Skills it names in `metadata.ants-uses` and whether it opts in to
 * the Vault (`metadata.varde-vault: true`).
 */
class Skill(val name: String, val file: Path, val uses: List<String>, val vault: Boolean) {
    val folder: Path get() = file.parent

    /** The schema of the `output` the Skill promises, from `result.schema.json` beside it; without one, any output. */
    val outputSchema: Map<*, *>
        get() = folder.resolve("result.schema.json").takeIf { it.exists() }?.let { JsonMapper.shared().readValue(it.toFile(), Map::class.java) }
            ?: emptyMap<String, Any?>()
}

/**
 * Agents and Skills by name, read from `agents/`, `skills/` and `shipped/` in ANTS_HOME each time they're asked for,
 * so an edit reaches the next launch. `shipped/` is Ants' own, replaced whole on start when any file differs.
 */
@Component
class Catalogue(@Value("\${ants.home}") home: Path) {
    private val shipped = home.resolve("shipped")
    private val agentFolders = listOf(home.resolve("agents"), shipped.resolve("agents"))
    private val skillFolders = listOf(home.resolve("skills"), shipped.resolve("skills"))

    init {
        val resources = PathMatchingResourcePatternResolver()
        val roots = resources.getResources("classpath*:shipped/").map { it.url.toString() }
        val bundled = resources.getResources("classpath*:shipped/**").filter { it.isReadable }.associate { resource ->
            val url = resource.url.toString()
            url.removePrefix(roots.first(url::startsWith)) to resource.contentAsByteArray
        }
        val onDisk = if (shipped.exists()) shipped.walk().filter { it.isRegularFile() }.associate { it.relativeTo(shipped).toString() to it.readBytes() } else emptyMap()
        if (bundled.keys != onDisk.keys || bundled.any { (path, bytes) -> !bytes.contentEquals(onDisk[path]) }) {
            shipped.toFile().deleteRecursively()
            for ((path, bytes) in bundled) shipped.resolve(path).also { Files.createDirectories(it.parent) }.writeBytes(bytes)
        }
    }

    /** The Agent [name]; a file without frontmatter is an Agent with defaults, named after the file. */
    fun agent(name: String): Agent {
        val agents = agentFolders.filter { it.exists() }.flatMap { it.listDirectoryEntries("*.md") }.map { file ->
            val (meta, body) = frontmatter(file.readText())
            Agent(
                name = meta["name"]?.toString() ?: file.nameWithoutExtension,
                cli = meta["cli"]?.toString() ?: "claude",
                tools = when (val tools = meta["tools"]) {
                    null -> null
                    is List<*> -> tools.map(Any?::toString)
                    else -> tools.toString().split(',').map(String::trim).filter(String::isNotEmpty)
                },
                model = meta["model"]?.toString(),
                instructions = body.trim(),
            )
        }.filter { it.name == name }
        return agents.singleOrNull() ?: error(if (agents.isEmpty()) "No Agent named $name" else "More than one Agent is named $name")
    }

    /** The Skill [name]; a shipped Skill wins its name. */
    fun skill(name: String): Skill {
        val skills = skillFolders.filter { it.exists() }.flatMap { folder ->
            folder.walk().filter { it.fileName.toString() == "SKILL.md" }.map { file ->
                val meta = frontmatter(file.readText()).first
                val metadata = meta["metadata"] as? Map<*, *> ?: emptyMap<String, Any?>()
                Skill(
                    name = meta["name"]?.toString() ?: "",
                    file = file,
                    uses = metadata["ants-uses"]?.toString()?.split(Regex("\\s+"))?.filter(String::isNotEmpty) ?: emptyList(),
                    vault = metadata["varde-vault"] == true,
                )
            }
        }.filter { it.name == name }
        return skills.singleOrNull() ?: skills.firstOrNull { it.file.startsWith(shipped) }
            ?: error(if (skills.isEmpty()) "No Skill named $name" else "More than one Skill is named $name")
    }

    /** Every Skill [skill] uses through `ants-uses`, at any depth, each once in the order first named; a cycle is refused, naming it. */
    fun uses(skill: Skill): List<Skill> {
        val closure = LinkedHashMap<String, Skill>()
        fun visit(user: Skill, chain: List<String>) {
            for (name in user.uses) {
                check(name !in chain) { "The Skills ${(chain.dropWhile { it != name } + name).joinToString(" → ")} use each other in a cycle" }
                val used = closure[name] ?: skill(name).also { closure[name] = it }
                visit(used, chain + name)
            }
        }
        visit(skill, listOf(skill.name))
        return closure.values.toList()
    }
}

/** A markdown file's YAML frontmatter and its body. */
private fun frontmatter(text: String): Pair<Map<*, *>, String> {
    val match = Regex("""\A---\R(.*?)\R---\R?(.*)""", RegexOption.DOT_MATCHES_ALL).find(text) ?: return emptyMap<String, Any?>() to text
    return (Yaml().load<Any?>(match.groupValues[1]) as? Map<*, *> ?: emptyMap<String, Any?>()) to match.groupValues[2]
}
