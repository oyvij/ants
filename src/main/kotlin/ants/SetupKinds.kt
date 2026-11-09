package ants

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

/** The user's own Agents in a Setup: `agents/<name>.md`, each agent file as it is, its id in its frontmatter. */
@Component
@Order(1)
class AgentsInSetup(private val catalogue: Catalogue, private val drafted: DraftedItems) : SetupKind {
    override val noun = "Agent"
    override val folder = "agents"

    private fun own() = catalogue.agents(withHidden = true).filter { it.file.parent == catalogue.yourAgents }

    override fun export() = own().associate { agent ->
        val text = agent.file.readText().let { text -> if (agent.id != null) text else withId(text, catalogue.unusedId(AGENT_ID)).also(agent.file::writeAtomically) }
        "$folder/${agent.file.fileName}" to text.toByteArray()
    }

    override fun read(files: Map<String, ByteArray>) = files.filterKeys { it.count { c -> c == '/' } == 1 && it.endsWith(".md") }.map { (path, bytes) ->
        val file = Path.of(path)
        runCatching { Agent(file, bytes.decodeToString()) }.fold(
            { SetupItem(this, path, it.id.orEmpty(), it.name, mapOf(path to bytes)) },
            { SetupItem(this, path, "", file.nameWithoutExtension, mapOf(path to bytes), "its frontmatter is broken: ${it.message}") },
        )
    }

    override fun has(id: String) = own().any { it.id == id }

    override fun taken(name: String, id: String) = drafted.agents.taken(name, id)

    override fun land(item: SetupItem, name: String) {
        nameError("An Agent", name)?.let { throw IllegalArgumentException(it) }
        val text = item.files.values.single().decodeToString().let { if (name == item.name) it else withField(it, "name", name) }
        val agent = Agent(catalogue.yourAgents.resolve("$name.md"), text)
        require(agent.cli in AgentForm.CLIS) { "It runs cli ${agent.cli}, and Ants runs only ${AgentForm.CLIS.joinToString(" or ")}." }
        Agent.sandboxError(agent.cli, agent.sandbox)?.let { throw IllegalArgumentException(it) }
        own().filter { it.id == item.id }.forEach { it.file.deleteIfExists() }
        agent.file.also { it.parent.createDirectories() }.writeAtomically(text)
    }
}

/** The user's own Skills in a Setup: `skills/<name>/`, each `SKILL.md` with its References and `ants.yaml`, its id. */
@Component
@Order(2)
class SkillsInSetup(private val catalogue: Catalogue, private val drafted: DraftedItems) : SetupKind {
    override val noun = "Skill"
    override val folder = "skills"

    private fun own() = catalogue.skills(withHidden = true).filter { it.file.startsWith(catalogue.yourSkills) }

    override fun export() = own().flatMap { skill ->
        if (skill.id == null) catalogue.giveSkillId(skill.folder)
        val root = "$folder/${skill.folder.relativeTo(catalogue.yourSkills).invariantSeparatorsPathString}"
        besideFiles(skill.folder).filterNot { it.toString().endsWith(".old") }.map { "$root/${it.relativeTo(skill.folder).invariantSeparatorsPathString}" to it.readBytes() }
    }.toMap()

    override fun read(files: Map<String, ByteArray>): List<SetupItem> {
        val roots = files.keys.filter { it.endsWith("/SKILL.md") }.map { it.removeSuffix("/SKILL.md") }
        return roots.map { root ->
            // A Skill's files are those under its folder that no deeper Skill's folder holds.
            val own = files.filterKeys { path -> path.startsWith("$root/") && roots.none { it != root && it.startsWith("$root/") && path.startsWith("$it/") } }
            val id = runCatching { (yaml.load<Any?>(own["$root/$SKILL_ID_FILE"]?.decodeToString().orEmpty()) as? Map<*, *>)?.get("id")?.toString() }.getOrNull().orEmpty()
            runCatching { Skills.named(own.getValue("$root/SKILL.md").decodeToString()) }.fold(
                { SetupItem(this, root, id, it, own) },
                { SetupItem(this, root, id, root.substringAfterLast('/'), own, it.message) },
            )
        }
    }

    override fun has(id: String) = own().any { it.id == id }

    override fun taken(name: String, id: String) = drafted.skills.taken(name, id)

    override fun land(item: SetupItem, name: String) {
        nameError("A Skill", name)?.let { throw IllegalArgumentException(it) }
        val folder = catalogue.yourSkills.resolve(name)
        val replaced = own().firstOrNull { it.id == item.id }
        require(!folder.exists() || folder == replaced?.folder) { "$folder already exists." }
        replaced?.let { old ->
            val beside = besideFiles(old.folder).toList()
            if (beside.size == old.folder.walk().count()) old.folder.toFile().deleteRecursively() else beside.forEach { it.deleteIfExists() }
        }
        val skill = item.files.getValue("${item.key}/SKILL.md").decodeToString().let { if (name == item.name) it else Skills.renamed(it, name) }
        for ((path, bytes) in item.files) {
            val relative = path.removePrefix("${item.key}/")
            if (relative != "SKILL.md" && relative != SKILL_ID_FILE) folder.resolve(relative).also { it.parent.createDirectories() }.writeBytes(bytes)
        }
        folder.createDirectories()
        catalogue.giveSkillId(folder, item.id)
        folder.resolve("SKILL.md").writeAtomically(skill) // last, so a launch finds the Skill only once it is whole
    }
}

/** The user's own Scripts in a Setup: `scripts/<id>/`, each `script.yaml` and `script.mjs`, never a Secret's value. */
@Component
@Order(3)
class ScriptsInSetup(private val scripts: Scripts, private val drafted: DraftedItems, private val settings: Settings) : SetupKind {
    override val noun = "Script"
    override val folder = "scripts"

    override fun export() = scripts.all().flatMap { script ->
        val dir = scripts.where(script)
        listOf("script.yaml", "script.mjs").filter { dir.resolve(it).exists() }.map { "$folder/${script.id}/$it" to dir.resolve(it).readBytes() }
    }.toMap()

    override fun read(files: Map<String, ByteArray>) = files.keys.filter { Regex("$folder/[^/]+/script\\.yaml").matches(it) }.map { definition ->
        val root = definition.removeSuffix("/script.yaml")
        val own = files.filterKeys { it.startsWith("$root/") }
        runCatching { yaml.load<Map<*, *>>(own.getValue(definition).decodeToString()) }.fold(
            { SetupItem(this, root, it["id"]?.toString().orEmpty(), Script.of(it).name, own) },
            { SetupItem(this, root, "", root.substringAfterLast('/'), own, "its script.yaml is broken: ${it.message}") },
        )
    }

    /** A Script lists its Secrets in `script.yaml`: those that are labels in this Ants' Settings are the user's. */
    override fun warning(item: SetupItem) = item.files["${item.key}/script.yaml"]
        ?.let { runCatching { Script.of(yaml.load<Map<*, *>>(it.decodeToString())) }.getOrNull() }
        ?.let { script -> Setup.reaches(script.secrets.filter { it in settings.secrets() }) }

    override fun has(id: String) = scripts.withId(id) != null

    override fun taken(name: String, id: String) = drafted.scripts.taken(name, id)

    override fun land(item: SetupItem, name: String) {
        nameError("A Script", name)?.let { throw IllegalArgumentException(it) }
        val script = Script.of(yaml.load<Map<*, *>>(item.files.getValue("${item.key}/script.yaml").decodeToString())).copy(name = name)
        scripts.withId(item.id)?.let(scripts::where)?.takeIf { it.fileName.toString() != item.id }?.toFile()?.deleteRecursively()
        scripts.save(script, item.files["${item.key}/script.mjs"]?.decodeToString().orEmpty(), null)
    }
}
