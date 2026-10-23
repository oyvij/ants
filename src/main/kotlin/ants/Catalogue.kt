package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Who does a Task, read from its agent file: the CLI it runs, its tools, its model and its standing instructions. A
 * file without frontmatter is an Agent with defaults, named after the file. [meta] is the whole frontmatter.
 */
class Agent(val file: Path, text: String = file.readText()) {
    val meta: Map<*, *>
    val instructions: String

    init {
        val (meta, body) = frontmatter(text)
        this.meta = meta
        instructions = body.trim()
    }

    val name = meta["name"]?.toString() ?: file.nameWithoutExtension
    val description = meta["description"]?.toString().orEmpty()
    val cli = meta["cli"]?.toString() ?: "claude"
    val skills = list(meta["skills"]).orEmpty()

    /** The tools as permission rules, such as `Bash(git log *)`; null for the default tools. */
    val tools = list(meta["tools"])
    val model = meta["model"]?.toString()

    /** What a codex Agent may do instead of tools: its `--sandbox`, one of [SANDBOXES]. */
    val sandbox = meta["sandbox"]?.toString() ?: "workspace-write"

    /** Offered as a Subagent (`ants-subagent: true`). */
    val subagent = meta["ants-subagent"] == true

    /** A YAML list, or a comma-separated string whose commas inside `( )` stay, as in `Agent(a, b)`. */
    private fun list(value: Any?) = when (value) {
        null -> null
        is List<*> -> value.map(Any?::toString)
        else -> rules(value.toString())
    }

    companion object {
        /** codex's `--sandbox` values. */
        val SANDBOXES = listOf("read-only", "workspace-write", "danger-full-access")

        /** Why an Agent on [cli] can't run with [sandbox]; null when it can. */
        fun sandboxError(cli: String, sandbox: String) =
            if (cli == "codex" && sandbox !in SANDBOXES) "codex takes the sandbox read-only, workspace-write or danger-full-access, not $sandbox." else null
    }
}

/**
 * A `SKILL.md` and the folder it lives in, with the Skills it names in `metadata.ants-uses` and whether it opts in to
 * the Vault (`metadata.varde-vault: true`).
 */
class Skill(
    val name: String, val description: String, val file: Path, val uses: List<String>, val vault: Boolean,
    /** The inputs it declares (`metadata.ants-inputs`), one per name, which a Task fills. */
    val inputs: List<String> = emptyList(),
    /** Whether it opts in to the Answer History (`metadata.ants-answer-history: true`). */
    val answerHistory: Boolean = false,
    /** Whether it opts in to the Run's Verbosity (`metadata.ants-verbosity: true`). */
    val verbosity: Boolean = false,
) {
    val folder: Path get() = file.parent

    /** The schema of the `output` the Skill promises, from `result.schema.json` beside it; without one, any output. */
    val outputSchema: Map<*, *>
        get() = folder.resolve("result.schema.json").takeIf { it.exists() }?.let { JsonMapper.shared().readValue(it.toFile(), Map::class.java) }
            ?: emptyMap<String, Any?>()
}

/**
 * Agents and Skills by name, read from `agents/` and `skills/` in ANTS_HOME, then the extra folders in settings, then
 * `shipped/`, each time they're asked for, so an edit reaches the next launch. `shipped/` is Ants' own, replaced whole on start when any file differs.
 */
@Component
class Catalogue(@Value("\${ants.home}") private val home: Path, private val settings: Settings) {
    private val shipped = home.resolve("shipped")

    /** The shipped file that defines the Verbosity levels. */
    val verbosityFile: Path = shipped.resolve("verbosity/verbosity.md")
    // A Path is an Iterable of its names, so `list + path` would add the names; each folder goes in as a list.
    private fun agentFolders(withHidden: Boolean) = listOf(home.resolve("agents")) + settings.agentFolders() + shippedFolder("agents", withHidden)
    private fun skillFolders(withHidden: Boolean) = listOf(home.resolve("skills")) + settings.skillFolders() + shippedFolder("skills", withHidden)
    private fun shippedFolder(kind: String, withHidden: Boolean) = if (withHidden || !settings.hideShipped()) listOf(shipped.resolve(kind)) else emptyList()

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

    fun agents(withHidden: Boolean = false): List<Agent> = agentFolders(withHidden).filter { it.exists() }.flatMap { it.listDirectoryEntries("*.md").sorted() }.map(::Agent)

    /** The Agent [name]. */
    fun agent(name: String): Agent = named("Agent", name, agents(withHidden = true).filter { it.name == name }, ::shipped)

    /** The user's own Skills, the one Skill folder the Skills page writes to. */
    val yourSkills: Path = home.resolve("skills")

    /** The user's own Agents, the one Agent folder Ants writes to. */
    val yourAgents: Path = home.resolve("agents")

    fun skills(withHidden: Boolean = false): List<Skill> = skillFolders(withHidden).filter { it.exists() }.flatMap { folder ->
        folder.walk().filter { it.fileName.toString() == "SKILL.md" }.sorted().map { file ->
            val meta = frontmatter(file.readText()).first
            val metadata = meta["metadata"] as? Map<*, *> ?: emptyMap<String, Any?>()
            Skill(
                name = meta["name"]?.toString() ?: "",
                description = meta["description"]?.toString() ?: "",
                file = file,
                uses = Settings.strings(metadata["ants-uses"]?.toString()).orEmpty(),
                vault = metadata["varde-vault"] == true,
                inputs = Settings.strings(metadata["ants-inputs"]?.toString()).orEmpty(),
                answerHistory = metadata["ants-answer-history"] == true,
                verbosity = metadata["ants-verbosity"] == true,
            )
        }
    }

    fun shipped(skill: Skill) = skill.file.startsWith(shipped)

    fun shipped(agent: Agent) = agent.file.startsWith(shipped)

    fun hides(file: Path) = settings.hideShipped() && file.startsWith(shipped)

    /** Where the Agents and Skills pages say [folder] is: "shipped with Ants", or the folder, with `~` for the home folder. */
    fun where(folder: Path): String {
        if (folder.startsWith(shipped)) return "shipped with Ants"
        val user = Path.of(System.getProperty("user.home"))
        return if (folder.startsWith(user)) "~/${user.relativize(folder)}" else folder.toString()
    }

    fun skill(name: String): Skill = named("Skill", name, skills(withHidden = true).filter { it.name == name }, ::shipped)

    private fun <T> named(kind: String, name: String, items: List<T>, shipped: (T) -> Boolean): T =
        items.singleOrNull() ?: items.filter { shipped(it) != settings.hideShipped() }.singleOrNull()
            ?: error(if (items.isEmpty()) "No $kind named $name" else "More than one $kind is named $name")

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

    /** Whether a Task running [skill] gets the Vault by default: when it or any Skill it uses opts in. */
    fun vault(skill: Skill) = skill.vault || uses(skill).any { it.vault }

    /** Whether a Task running [skill] writes at the Run's Verbosity: when it or any Skill it uses opts in. */
    fun verbosity(skill: Skill) = skill.verbosity || uses(skill).any { it.verbosity }
}

/**
 * Why [name] can't name an Agent or a Skill ([kind], "An Agent" or "A Skill"), or null: a name is up to 64 lowercase letters, digits and hyphens,
 * and none the pages keep for their own routes, such as `/agents/new`.
 */
fun nameError(kind: String, name: String) = when {
    !Regex("[a-z0-9][a-z0-9-]{0,63}").matches(name) -> "$kind's name is up to 64 lowercase letters, digits and hyphens."
    name in setOf("new", "import", "schema") -> "The ${kind.substringAfter(' ')}s page keeps the name $name for itself."
    else -> null
}

/** The first free name of `<name>-copy`, `<name>-copy-2`, …, for a copy of [name], made a valid name first. */
fun copyName(name: String, taken: (String) -> Boolean) = freeName("$name-copy", taken)

/** The first free name of `<name>`, `<name>-2`, …, made of lowercase letters, digits and hyphens first. */
fun freeName(name: String, taken: (String) -> Boolean): String {
    val base = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
    return generateSequence(1, Int::inc).map { if (it == 1) base else "$base-$it" }.first { !taken(it) }
}

/**
 * Writes [text] beside this file and moves it over, so whoever reads it meanwhile, such as a launch reading a
 * `SKILL.md` or the settings, never sees half a file.
 */
fun Path.writeAtomically(text: String) {
    val next = Files.createTempFile(parent.createDirectories(), ".$fileName", ".tmp")
    next.writeText(text)
    Files.move(next, this, ATOMIC_MOVE, REPLACE_EXISTING)
}

/** The YAML Ants writes: block style, a long value kept on one line. A new one on each use, since a [Yaml] isn't thread-safe. */
val yaml get() = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; splitLines = false })

/** A list as a form's field holds it: words separated by commas or white space, such as `code-review, tdd`. */
fun words(text: String?) = text.orEmpty().split(Regex("[\\s,]+")).filter(String::isNotEmpty)

/** A comma-separated list of tool rules, whose commas inside `( )` stay, as in `Bash(git log *), Agent(a, b)`. */
fun rules(text: String) = text.split(Regex(""",(?![^(]*\))""")).map(String::trim).filter(String::isNotEmpty)

/** A markdown file's YAML frontmatter and its body; broken YAML throws. */
internal fun frontmatter(text: String): Pair<Map<*, *>, String> {
    val match = Regex("""\A---\R(.*?)\R---\R?(.*)""", RegexOption.DOT_MATCHES_ALL).find(text) ?: return emptyMap<String, Any?>() to text
    return (Yaml().load<Any?>(match.groupValues[1]) as? Map<*, *> ?: emptyMap<String, Any?>()) to match.groupValues[2]
}
