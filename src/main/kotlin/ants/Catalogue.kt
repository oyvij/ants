package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
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
class Agent(val file: Path, text: String = file.readText(), outsideId: String? = null) {
    val meta: Map<*, *>
    val instructions: String

    init {
        val (meta, body) = frontmatter(text)
        this.meta = meta
        instructions = body.trim()
    }

    val id = outsideId ?: meta["id"]?.toString()?.takeIf(String::isNotBlank)

    val name = meta["name"]?.toString() ?: file.nameWithoutExtension
    val description = meta["description"]?.toString().orEmpty()
    val cli = meta["cli"]?.toString() ?: "claude"
    val skills = list(meta["skills"]).orEmpty()
    val scripts = list(meta["scripts"]).orEmpty()

    /** Whether it may use every Skill, `skills: "*"`. */
    val allSkills get() = "*" in skills

    fun allows(skill: Skill) = allSkills || skill.id in skills || skill.name in skills

    fun allows(script: Script) = script.id in scripts || script.name in scripts

    /** The tools as permission rules, such as `Bash(git log *)`; null for the default tools. */
    val tools = list(meta["tools"])
    val model = meta["model"]?.toString()
    val level: Int? = meta["level"]?.toString()?.toIntOrNull()?.takeIf { it in Level.RANGE }

    private val startLevel: Int get() = level ?: model?.let(Level::family) ?: Level.START

    val claudeLevel: Level
        get() {
            val ladder = Level.claude(startLevel)
            if (level != null || model == null) return ladder
            return ladder.copy(model = model)
        }

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

/** A `SKILL.md` and the folder it lives in, with the files beside it, as Claude Code knows a Skill (ADR 0024). */
class Skill(
    val name: String, val description: String, val file: Path,
    /** Whether only a user can invoke it (`disable-model-invocation: true`): claude doesn't show it to the model. */
    val userOnly: Boolean = false,
    val id: String? = null,
) {
    val folder: Path get() = file.parent
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

    private val agentIds = IdFile(home.resolve("agent-ids.yaml"), AGENT_ID)
    private val skillIds = IdFile(home.resolve("skill-ids.yaml"), SKILL_ID)

    fun agents(withHidden: Boolean = false): List<Agent> {
        val outside = settings.agentFolders().toSet()
        return agentFolders(withHidden).filter { it.exists() }.flatMap { folder ->
            folder.listDirectoryEntries("*.md").sorted().map { file -> if (folder in outside) Agent(file, outsideId = agentIds.of(file)) else Agent(file) }
        }
    }

    /** The Agent [name]. */
    fun agent(name: String): Agent = one(agents(withHidden = true).filter { it.name == name }, ::shipped, "No Agent named $name", "More than one Agent is named $name")

    fun agentWithId(id: String): Agent = one(agents(withHidden = true).filter { it.id == id }, ::shipped, "No Agent has the id $id", "More than one Agent has the id $id")

    /** The user's own Skills, the one Skill folder the Skills page writes to. */
    val yourSkills: Path = home.resolve("skills")

    /** The user's own Agents, the one Agent folder Ants writes to. */
    val yourAgents: Path = home.resolve("agents")

    fun skills(withHidden: Boolean = false): List<Skill> {
        val outside = settings.skillFolders().toSet()
        return skillFolders(withHidden).filter { it.exists() }.flatMap { folder ->
            folder.walk().filter { it.fileName.toString() == "SKILL.md" }.sorted().map { file ->
                val meta = frontmatter(file.readText()).first
                val id = if (folder in outside) skillIds.of(file.parent) else skillIdIn(file.parent)
                Skill(meta["name"]?.toString() ?: "", meta["description"]?.toString() ?: "", file, userOnly = meta["disable-model-invocation"] == true, id = id)
            }
        }
    }

    private fun skillIdIn(folder: Path) = folder.resolve(SKILL_ID_FILE).takeIf { it.exists() }
        ?.let { runCatching { (Yaml().load<Any?>(it.readText()) as? Map<*, *>)?.get("id")?.toString()?.takeIf(String::isNotBlank) }.getOrNull() }

    fun giveSkillId(folder: Path) = folder.resolve(SKILL_ID_FILE).writeAtomically(yaml.dump(mapOf("id" to unusedId(SKILL_ID))))

    fun unusedId(prefix: Char): String {
        val taken = identified().map { it.id }.toSet()
        return newId(prefix) { it in taken }
    }

    fun identified(): List<Identified> = agents(withHidden = true).mapNotNull { it.identified } + skills(withHidden = true).mapNotNull { it.identified }

    fun shipped(skill: Skill) = skill.file.startsWith(shipped)

    fun shipped(agent: Agent) = agent.file.startsWith(shipped)

    fun hides(file: Path) = settings.hideShipped() && file.startsWith(shipped)

    /** Where the Agents and Skills pages say [folder] is: "shipped with Ants", or the folder, with `~` for the home folder. */
    fun where(folder: Path): String {
        if (folder.startsWith(shipped)) return "shipped with Ants"
        val user = Path.of(System.getProperty("user.home"))
        return if (folder.startsWith(user)) "~/${user.relativize(folder)}" else folder.toString()
    }

    fun skill(name: String): Skill = one(skills(withHidden = true).filter { it.name == name }, ::shipped, "No Skill named $name", "More than one Skill is named $name")

    fun skillWithId(id: String): Skill = one(skills(withHidden = true).filter { it.id == id }, ::shipped, "No Skill has the id $id", "More than one Skill has the id $id")

    private fun <T> one(items: List<T>, shipped: (T) -> Boolean, none: String, many: String): T =
        items.singleOrNull() ?: items.filter { shipped(it) != settings.hideShipped() }.singleOrNull() ?: error(if (items.isEmpty()) none else many)

    /** The names of the Skills [agent] may use: every one in the catalogue with `skills: "*"`. */
    fun allowedNames(agent: Agent): List<String> = if (agent.allSkills) skills().map { it.name }.distinct() else skillNames(agent.skills)

    /** The Skills [agent] may use ([allowedNames]). */
    fun allowed(agent: Agent): List<Skill> = if (agent.allSkills) allowedNames(agent).map(::skill) else agent.skills.map { linkedSkill(it, null) }

    fun listedSkill(value: String): Skill? = runCatching { linkedSkill(value, null) }.getOrNull()

    fun skillNames(listed: List<String>): List<String> = listed.map { value -> eachListed(value) { it.name } }

    fun skillIds(listed: List<String>): List<String> = listed.map { value -> eachListed(value) { it.id } }.distinct()

    private fun eachListed(value: String, shown: (Skill) -> String?) = if (value == "*") value else listedSkill(value)?.let(shown) ?: value
}

/**
 * Why [name] can't name an Agent or a Skill ([kind], "An Agent" or "A Skill"), or null: a name is up to 64 lowercase letters, digits and hyphens,
 * and none the pages keep for their own routes, such as `/agents/new`.
 */
fun nameError(kind: String, name: String) = when {
    !Regex("[a-z0-9][a-z0-9-]{0,63}").matches(name) -> "$kind's name is up to 64 lowercase letters, digits and hyphens."
    name in setOf("new", "import") -> "The ${kind.substringAfter(' ')}s page keeps the name $name for itself."
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

fun Path.writePrivately(text: String) {
    val owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    val next = Files.createTempFile(parent.createDirectories(), ".$fileName", ".tmp", owner)
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
