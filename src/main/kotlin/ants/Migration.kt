package ants

import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

/** The property an Agent Task named its one Skill by before ADR 0024; only the migration reads it. */
private const val OLD_SKILL = "ants.skill"

/** The metadata a Skill carried for Ants before ADR 0024; only the migration reads it. */
private val OLD_METADATA = listOf("ants-inputs", "ants-uses", "ants-verbosity", "ants-answer-history", "varde-vault")

/** The Task properties a shipped Task's job is made of, copied onto a user's Task that named its old Skill. */
private val JOB = listOf(ASSIGNMENT, SKILLS, RESULT_SCHEMA, VAULT, ANSWER_HISTORY)

/** The shipped Tasks, by element id, that named a shipped Skill before ADR 0024, and that Skill's name. */
private val SHIPPED_TASKS = linkedMapOf(
    "scout" to "scout", "grill" to "grill", "answerer" to "answer-round", "tospec" to "to-spec", "totickets" to "to-tickets",
    "implement" to "implement", "implementproposal" to "implement", "walkthrough" to "walkthrough", "fix" to "fix-review", "pr" to "pr",
    "prproposal" to "pr", "proposesolution" to "propose-solution", "updateknowledge" to "update-knowledge", "initvault" to "init-vault",
)

/** Shipped Skills an Agent may still list that are gone, and the name of the one that took over, if any. */
private val GONE_SKILLS = mapOf("grill" to "grilling", "scout" to null, "answer-round" to null, "propose-solution" to null, "fix-review" to null)

/**
 * Brings the user's own files to ADR 0024's shape once, as Ants starts, before anything deploys or launches: each Agent
 * Task of the latest user Processes and of the user's Templates that names one Skill (`ants.skill=X`) lists it in
 * [SKILLS] instead, taking over what X gave it unless the Task sets it itself: its result schema, its declared inputs,
 * its Vault and Answer History opt-ins; when X used other Skills, they are listed too, with an Assignment to follow X,
 * since only one listed Skill can be the job. A Task naming a shipped Skill whose job is now a shipped Task's gets that
 * Task's Assignment, Skills, schema and toggles. Then the user's Agents list what their Tasks now do, and the user's
 * Skills drop their Ants metadata and `result.schema.json`. Each changed file keeps its old version beside it as
 * `<file>.old`; a Process keeps its older version. What is already in the new shape is left as it is.
 */
@Component
class Migration(
    private val repository: RepositoryService, private val processes: Processes, private val catalogue: Catalogue, @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE) // before the shipped Processes deploy and Agent Tasks launch
    fun migrate() {
        val shipped = shippedJobs()
        val needs = mutableMapOf<String, MutableSet<String>>()
        val templates = home.resolve("templates").takeIf { it.exists() }?.listDirectoryEntries("*.bpmn").orEmpty().mapNotNull { file ->
            runCatching { parse(file.readBytes()) }.onFailure { log.warn("The Template $file can't be read to migrate it: ${it.message}") }.getOrNull()?.let { file to it }
        }
        val saved = repository.createProcessDefinitionQuery().latestVersion().list().filter { !it.resourceName.startsWith("shipped/") }
            .map { it to parse(repository.getProcessModel(it.id).readAllBytes()) }
        // Every Task first, so the Agents list what the Tasks need before the Checks of each deploy read them.
        val changedTemplates = templates.filter { (_, document) -> migrate(document, shipped, needs).isNotEmpty() }
        val changedProcesses = saved.mapNotNull { (process, document) -> migrate(document, shipped, needs).takeIf { it.isNotEmpty() }?.let { Triple(process, document, it) } }
        agents(needs)
        for ((file, document) in changedTemplates) {
            file.resolveSibling("${file.fileName}.old").writeBytes(file.readBytes())
            file.writeAtomically(serialize(document))
        }
        val failed = mutableSetOf<String>()
        for ((process, document, named) in changedProcesses) try {
            processes.deploy(serialize(document).toByteArray(), process.resourceName)
        } catch (e: Refused) {
            log.warn("The Process ${process.key} can't be migrated, so it stays as it was: ${e.message}")
            failed += named
        }
        skills(failed)
    }

    /** Each shipped Task that named an old shipped Skill, by that Skill's name: its element id and its job's properties. */
    private fun shippedJobs(): Map<String, List<Pair<String, Map<String, String>>>> =
        listOf("processes", "templates").map { home.resolve("shipped/$it") }.filter { it.exists() }.flatMap { it.listDirectoryEntries("*.bpmn").sorted() }
            .flatMap { file -> agentTasks(parse(file.readBytes())).filter { it.getAttribute("id") in SHIPPED_TASKS } }
            .map { task -> SHIPPED_TASKS.getValue(task.getAttribute("id")) to (task.getAttribute("id") to properties(task).filterKeys { it in JOB }.mapValues { it.value.getAttribute("value") }) }
            .groupBy({ it.first }, { it.second })

    /** Migrates each old-shape Agent Task in [document], noting the Skills each Agent now needs; gives the Skills the changed Tasks named, empty when none changed. */
    private fun migrate(document: Document, shipped: Map<String, List<Pair<String, Map<String, String>>>>, needs: MutableMap<String, MutableSet<String>>): Set<String> {
        val named = mutableSetOf<String>()
        for (task in agentTasks(document)) {
            val properties = properties(task)
            val old = properties[OLD_SKILL] ?: continue
            val name = old.getAttribute("value").trim()
            named += name
            fun has(key: String) = properties[key] != null
            fun set(key: String, value: String) {
                if (has(key)) return
                val added = old.cloneNode(false) as Element
                added.setAttribute("name", key)
                added.setAttribute("value", value)
                old.parentNode.insertBefore(added, old)
            }
            // A user's own Skill of that name wins it, as the catalogue has it.
            val yours = runCatching { catalogue.skill(name) }.getOrNull()?.let { !catalogue.shipped(it) } ?: false
            val job = shipped[name]?.takeUnless { yours }?.let { tasks -> (tasks.firstOrNull { it.first == task.getAttribute("id") } ?: tasks.first()).second }
            when {
                has(SKILLS) || has(ASSIGNMENT) || name.isEmpty() -> {} // half-migrated by hand, or naming nothing
                job != null -> JOB.forEach { key -> job[key]?.let { set(key, it) } }
                else -> {
                    val skill = OldSkill.of(catalogue, name)
                    val used = skill?.used(catalogue).orEmpty()
                    set(SKILLS, (listOf(name) + used.map { it.name }).joinToString(", "))
                    if (used.isNotEmpty()) set(ASSIGNMENT, "Follow the Skill $name.")
                    skill?.schema?.let { set(RESULT_SCHEMA, it) }
                    if (skill != null && (skill.vault || used.any { it.vault })) set(VAULT, "true")
                    if (skill?.answerHistory == true) set(ANSWER_HISTORY, "true")
                    skill?.inputs?.forEach { input(task, it) }
                }
            }
            properties(task)[SKILLS]?.getAttribute("value")?.let(::words)?.let { listed ->
                properties[AGENT]?.getAttribute("value")?.let { needs.getOrPut(it) { mutableSetOf() } += listed }
            }
            old.parentNode.removeChild(old)
        }
        return named
    }

    /** Adds an empty input [name] to [task], as the modeler adds a declared one, unless the Task has it. */
    private fun input(task: Element, name: String) {
        val extensions = task.getElementsByTagNameNS(BPMN, "extensionElements").elements().first()
        val properties = extensions.getElementsByTagNameNS("*", "properties").elements().first()
        val io = extensions.getElementsByTagNameNS("*", "inputOutput").elements().firstOrNull()
            ?: extensions.appendChild(task.ownerDocument.createElementNS(properties.namespaceURI, "${properties.prefix}:inputOutput")) as Element
        if (io.getElementsByTagNameNS("*", "inputParameter").elements().any { it.getAttribute("name") == name }) return
        io.appendChild(task.ownerDocument.createElementNS(properties.namespaceURI, "${properties.prefix}:inputParameter")).let { (it as Element).setAttribute("name", name) }
    }

    /**
     * The user's own Agents list each Skill their migrated Tasks now name, and no gone shipped Skill: `grill` is
     * `grilling` now, and the Skills that became shipped Assignments drop out, unless the user has one by that name.
     */
    private fun agents(needs: Map<String, Set<String>>) {
        for (agent in catalogue.agents(withHidden = true).filter { it.file.parent == catalogue.yourAgents && !it.allSkills }) {
            val kept = agent.skills.mapNotNull { name ->
                if (name in GONE_SKILLS && runCatching { catalogue.skill(name) }.isFailure) GONE_SKILLS[name] else name
            }
            val skills = (kept + needs[agent.name].orEmpty()).distinct()
            if (skills == agent.skills) continue
            agent.file.resolveSibling("${agent.file.fileName}.old").writeBytes(agent.file.readBytes())
            agent.file.writeAtomically(AgentForm.of(agent).copy(skills = skills.joinToString(", ")).text())
        }
    }

    /** The user's own Skills drop their Ants metadata and `result.schema.json`, all but those a Process that couldn't be migrated still names. */
    private fun skills(failed: Set<String>) {
        for (skill in catalogue.skills(withHidden = true).filter { it.file.startsWith(catalogue.yourSkills) && it.name !in failed }) {
            val (meta, body) = runCatching { frontmatter(skill.file.readText()) }.getOrNull() ?: continue
            val metadata = meta["metadata"] as? Map<*, *>
            val schema = skill.folder.resolve("result.schema.json")
            if (metadata?.keys.orEmpty().none { it in OLD_METADATA } && !schema.exists()) continue
            if (metadata?.keys.orEmpty().any { it in OLD_METADATA }) {
                val plain = metadata!!.filterKeys { it !in OLD_METADATA }
                val rest = LinkedHashMap<Any?, Any?>(meta).apply { if (plain.isEmpty()) remove("metadata") else put("metadata", plain) }
                skill.file.resolveSibling("SKILL.md.old").writeBytes(skill.file.readBytes())
                skill.file.writeAtomically("---\n" + yaml.dump(rest) + "---\n" + body)
            }
            if (schema.exists()) schema.moveTo(schema.resolveSibling("result.schema.json.old"), overwrite = true)
        }
    }

    /** A Skill as it was before ADR 0024: its Ants metadata and `result.schema.json`. */
    private class OldSkill(val name: String, metadata: Map<*, *>, folder: Path) {
        val inputs = Settings.strings(metadata["ants-inputs"]).orEmpty()
        val uses = Settings.strings(metadata["ants-uses"]).orEmpty()
        val vault = metadata["varde-vault"] == true
        val answerHistory = metadata["ants-answer-history"] == true
        val schema = folder.resolve("result.schema.json").takeIf { it.exists() }?.readText()?.takeIf(String::isNotBlank)

        /** Every Skill this one used, at any depth, each once in the order first named; one that can't be found is left out. */
        fun used(catalogue: Catalogue): List<OldSkill> {
            val found = LinkedHashMap<String, OldSkill>()
            fun visit(skill: OldSkill) {
                for (name in skill.uses) if (name != this.name && name !in found) of(catalogue, name)?.let { found[name] = it; visit(it) }
            }
            visit(this)
            return found.values.toList()
        }

        companion object {
            fun of(catalogue: Catalogue, name: String): OldSkill? = runCatching { catalogue.skill(name) }.getOrNull()?.let { skill ->
                val metadata = runCatching { frontmatter(skill.file.readText()).first["metadata"] as? Map<*, *> }.getOrNull()
                OldSkill(skill.name, metadata ?: emptyMap<String, Any?>(), skill.folder)
            }
        }
    }
}

/** The Agent Tasks in [document]. */
private fun agentTasks(document: Document) = document.getElementsByTagNameNS(BPMN, "serviceTask").elements()
    .filter { task -> List(task.attributes.length) { task.attributes.item(it) }.any { it.localName == "topic" && it.nodeValue == AgentLauncher.TOPIC } }

/** An Agent Task's extension properties, by name. */
private fun properties(task: Element) = task.getElementsByTagNameNS("*", "property").elements()
    .filter { it.parentNode.localName == "properties" && it.parentNode.parentNode.localName == "extensionElements" }.associateBy { it.getAttribute("name") }

/**
 * [task] as the latest version of its Process has it, when its own version is from before the migration (it names an
 * old `ants.skill`): a Run in flight on an old version runs its Task migrated. Else [task] itself.
 */
internal fun migrated(task: LockedExternalTask, repository: RepositoryService): LockedExternalTask {
    val properties = task.extensionProperties
    if (OLD_SKILL !in properties || SKILLS in properties || ASSIGNMENT in properties) return task
    val latest = repository.createProcessDefinitionQuery().processDefinitionKey(task.processDefinitionKey).latestVersion().singleResult() ?: return task
    val element = repository.getBpmnModelInstance(latest.id).getModelElementById<BaseElement>(task.activityId) ?: return task
    val now = element.extensions<OperatonProperties>().flatMap { it.operatonProperties }.associate { it.operatonName to it.operatonValue }
    return object : LockedExternalTask by task {
        override fun getExtensionProperties() = now
    }
}
