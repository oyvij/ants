package ants

import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.model.bpmn.instance.BaseElement
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

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
 * A shipped Agent from before ADR 0027: the name of the one that took its jobs ([newName]), the Skills it gave a Task
 * that listed none (none for the architect's), and the level it started on.
 */
private class OldAgent(val newName: String, val skills: List<String>, val level: Int) {
    val newId get() = "a-$newName"
}

/** The shipped Agents from before ADR 0027, by name. */
private val OLD_AGENTS = mapOf(
    "scout" to OldAgent("lead", listOf("search-knowledge"), 2),
    "griller" to OldAgent("lead", listOf("grilling"), 3),
    "planner" to OldAgent("lead", listOf("to-spec", "to-tickets"), 3),
    "architect" to OldAgent("lead", emptyList(), 4),
    "storyteller" to OldAgent("lead", listOf("walkthrough"), 2),
    "implementer" to OldAgent("engineer", listOf("implement", "tdd", "code-review", "codebase-design"), 3),
    "pr" to OldAgent("engineer", listOf("pr"), 2),
    "answerer" to OldAgent("proxy", listOf("search-knowledge"), 2),
    "knowledge" to OldAgent("librarian", listOf("update-knowledge", "init-vault", "search-knowledge"), 2),
)

/**
 * What moving a Task off an old shipped Agent changes in its properties: [AGENT] names [agent]'s new id with its new
 * name kept beside it, the name kept beside the old one ([oldNameKey]) goes, and the properties in [added], in order,
 * follow.
 */
private class Move(val agent: OldAgent, val oldNameKey: String, val added: Map<String, String>) {
    val newNameKey get() = nameBeside(agent.newId)
}

/**
 * How a Task with [properties] moves off the old shipped Agent it names (ADR 0027), by its id or its name, when no
 * Agent has that id or name now: onto the old Agent's new one, listing the old Agent's Skills if it lists none, and on the old
 * Agent's level if it sets none and the new Agent starts on another. Null when it names no old shipped Agent, such as a
 * copy of one, or the user's own Agent of an old one's name.
 */
private fun Catalogue.move(properties: Map<String, String>): Move? {
    val value = properties[AGENT]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    fun gone(find: () -> Agent) = runCatching(find).isFailure
    val name = (if (value.startsWith("a-")) value.removePrefix("a-").takeIf { gone { agentWithId(value) } } else value.takeIf { gone { agent(it) } }) ?: return null
    val old = OLD_AGENTS[name] ?: return null
    val added = buildMap {
        if (SKILLS !in properties) {
            put(SKILLS, old.skills.joinToString(", ") { "k-$it" })
            old.skills.forEach { put(nameBeside("k-$it"), it) }
        }
        val level = runCatching { agentWithId(old.newId).startLevel }.getOrNull()
        if (LEVEL !in properties && level != old.level) put(LEVEL, old.level.toString())
    }
    return Move(old, nameBeside(value), added)
}

/** [properties], a Task's, as they are once it has moved off the old shipped Agent it names ([move]); null when it names none. */
internal fun Catalogue.onNewAgent(properties: Map<String, String>): Map<String, String>? = move(properties)?.let { move ->
    properties - move.oldNameKey + (AGENT to move.agent.newId) + (move.newNameKey to move.agent.newName) + move.added
}

/** Moves each Agent Task in [document] off the old shipped Agent it names ([move]); whether any moved. */
private fun Catalogue.onNewAgents(document: Document): Boolean {
    var moved = false
    for (task in agentTaskElements(document)) {
        val elements = propertyElements(task)
        val move = move(elements.mapValues { it.value.getAttribute("value") }) ?: continue
        moved = true
        val agent = elements.getValue(AGENT)
        fun property(name: String, value: String) = (agent.cloneNode(false) as Element).apply { setAttribute("name", name); setAttribute("value", value) }
        elements[move.oldNameKey]?.let { it.parentNode.removeChild(it) }
        agent.setAttribute("value", move.agent.newId)
        agent.parentNode.insertBefore(property(move.newNameKey, move.agent.newName), agent.nextSibling)
        var last = propertyElements(task).values.last()
        for ((name, value) in move.added) last = last.parentNode.insertBefore(property(name, value), last.nextSibling) as Element
    }
    return moved
}

/**
 * Brings the user's own files to ADR 0024's shape once, as Ants starts, before anything deploys or launches: each Agent
 * Task of the latest user Processes and of the user's Templates that names one Skill (`ants.skill=X`) lists it in
 * [SKILLS] instead, taking over what X gave it unless the Task sets it itself: its result schema, its declared inputs,
 * its Vault and Answer History opt-ins; when X used other Skills, they are listed too, with an Assignment to follow X,
 * since only one listed Skill can be the job. A Task naming a shipped Skill whose job is now a shipped Task's gets that
 * Task's Assignment, Skills, schema and toggles. The user's Agents list what the Tasks of the migrated Templates and of
 * the Processes that deploy now do, and the user's Skills drop their Ants metadata and `result.schema.json`. A Process
 * the Checks refuse stays as it was, and so do its Agents' lists and its Skills; its Runs fail saying why ([unmigrated]).
 * A Task naming an old shipped Agent runs on its new one ([move], ADR 0027). Each changed file keeps its old version
 * beside it as `<file>.old`; a Process keeps its older version. What is already in the new shape is left as it is.
 */
@Component
class Migration(
    private val repository: RepositoryService, private val processes: Processes, private val catalogue: Catalogue, private val scripts: Scripts, private val drafts: Drafts, @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE) // before the shipped Processes deploy and Agent Tasks launch
    fun migrate() {
        val shipped = shippedJobs()
        val templates = home.resolve("templates").takeIf { it.exists() }?.listDirectoryEntries("*.bpmn").orEmpty().mapNotNull { file ->
            runCatching { parse(file.readBytes()) }.onFailure { log.warn("The Template $file can't be read to migrate it: ${it.message}") }.getOrNull()?.let { file to it }
        }
        val saved = repository.createProcessDefinitionQuery().latestVersion().list().filter { !it.resourceName.startsWith("shipped/") }
            .map { it to parse(repository.getProcessModel(it.id)!!.readAllBytes()) }
        // The Agents list what the migrated Templates and the Processes that deploy need: a Process's needs go in before
        // the Checks of its deploy read the Agents, and out again if they refuse it, so it keeps its old shape coherently.
        val agents = Agents()
        var needs: Needs = emptyMap()
        val changedTemplates = templates.filter { (_, document) ->
            val (named, own) = migrate(document, shipped)
            needs = needs.and(own)
            catalogue.onNewAgents(document) || named.isNotEmpty()
        }
        val changedProcesses = saved.mapNotNull { (process, document) ->
            val (named, own) = migrate(document, shipped)
            if (catalogue.onNewAgents(document) || named.isNotEmpty()) Changed(process, document, named, own) else null
        }
        agents.list(needs)
        for ((file, document) in changedTemplates) {
            file.resolveSibling("${file.fileName}.old").writeBytes(file.readBytes())
            file.writeAtomically(serialize(document))
        }
        val failed = mutableSetOf<String>()
        refused.clear()
        for (changed in changedProcesses) try {
            agents.list(needs.and(changed.needs))
            processes.deploy(serialize(changed.document).toByteArray(), changed.process.resourceName)
            needs = needs.and(changed.needs)
        } catch (e: Refused) {
            log.warn("The Process ${changed.process.key} can't be migrated, so it stays as it was: ${e.message}")
            refused[changed.process.key] = e.message.orEmpty()
            failed += changed.named
        }
        agents.list(needs)
        val keptOld = agents.keepOld()
        skills(failed)
        linkSkillsById(keptOld + giveIds(keptOld))
        links(changedTemplates.map { it.first }.toSet())
        processDrafts()
    }

    /** Moves each Process Draft's Agent Tasks off the old shipped Agents they name ([move]), keeping the old Draft beside it. */
    private fun processDrafts() {
        for (draft in drafts.all(DraftKind.PROCESS)) {
            val document = runCatching { parse(draft.content.toByteArray()) }.getOrNull() ?: continue
            if (!catalogue.onNewAgents(document)) continue
            val file = drafts.file(DraftKind.PROCESS, draft.id)
            file.resolveSibling("${file.fileName}.old").writeBytes(file.readBytes())
            drafts.save(DraftKind.PROCESS, DraftPage(draft.id, draft.key, draft.name, serialize(document)), DraftFrom(draft.version))
        }
    }

    private class Changed(val process: ProcessDefinition, val document: Document, val named: Set<String>, val needs: Needs)

    /** Why the Checks refused each Process, by key, that the last [migrate] couldn't migrate. */
    private val refused = ConcurrentHashMap<String, String>()

    /**
     * Why [task] can't run, when its Process still has it the old way (`ants.skill`) since the Checks refused its
     * migration, saying what to fix; null when it can run.
     */
    fun unmigrated(task: LockedExternalTask): String? {
        if (OLD_SKILL !in task.extensionProperties) return null
        val why = refused[task.processDefinitionKey]?.let { "The Checks refused it:\n$it" } ?: "Why is in Ants' log."
        return "The Process ${task.processDefinitionKey} was not migrated to Assignments and Skills when Ants started, so its Task " +
            "${task.activityId} still names its Skill the old way. $why\nFix that in the Process, give the Task an Assignment or list " +
            "its Skill, have its Agent list that Skill, Save, and Retry."
    }

    /**
     * The user's own Agents (not `*`) as they were before the migration, which [list] rewrites from: each lists the
     * Skills the migrated Tasks need, and no gone shipped Skill: `grill` is `grilling` now, and the Skills that became
     * shipped Assignments drop out, unless the user has one by that name.
     */
    private inner class Agents {
        private val before = catalogue.agents(withHidden = true).filter { it.file.parent == catalogue.yourAgents && !it.allSkills }.associate { it.file to it.file.readText() }

        fun list(needs: Needs) {
            for ((file, text) in before) {
                val agent = Agent(file, text)
                val kept = agent.skills.mapNotNull { name -> if (name in GONE_SKILLS && runCatching { catalogue.skill(name) }.isFailure) GONE_SKILLS[name] else name }
                val listedNames = catalogue.skillNames(kept)
                val skills = (kept + needs[agent.name].orEmpty().filter { it !in listedNames }).distinct()
                val now = if (skills == agent.skills) text else AgentForm.of(agent).copy(skills = skills.joinToString(", ")).text()
                if (file.readText() != now) file.writeAtomically(now)
            }
        }

        /** Each Agent rewritten keeps its old version beside it. */
        fun keepOld(): Set<Path> = before.filter { (file, text) -> file.readText() != text }
            .onEach { (file, text) -> file.resolveSibling("${file.fileName}.old").writeText(text) }.keys
    }

    /** Each shipped Task that named an old shipped Skill, by that Skill's name: its element id and its job's properties. */
    private fun shippedJobs(): Map<String, List<Pair<String, Map<String, String>>>> =
        listOf("processes", "templates").map { home.resolve("shipped/$it") }.filter { it.exists() }.flatMap { it.listDirectoryEntries("*.bpmn").sorted() }
            .flatMap { file -> agentTaskElements(parse(file.readBytes())).filter { it.getAttribute("id") in SHIPPED_TASKS } }
            .map { task -> SHIPPED_TASKS.getValue(task.getAttribute("id")) to (task.getAttribute("id") to propertyElements(task).filterKeys { it in JOB }.mapValues { it.value.getAttribute("value") }) }
            .groupBy({ it.first }, { it.second })

    /** Migrates each old-shape Agent Task in [document]; gives the Skills the changed Tasks named, empty when none changed, and the Skills each Agent now needs for them. */
    private fun migrate(document: Document, shipped: Map<String, List<Pair<String, Map<String, String>>>>): Pair<Set<String>, Needs> {
        val named = mutableSetOf<String>()
        val needs = mutableMapOf<String, MutableSet<String>>()
        for (task in agentTaskElements(document)) {
            val properties = propertyElements(task)
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
            Job { propertyElements(task)[it]?.getAttribute("value") }.listed?.let { listed ->
                val names = listed.map { runCatching { catalogue.linkedSkill(it, null).name }.getOrDefault(it) }
                properties[AGENT]?.getAttribute("value")?.let { needs.getOrPut(it) { mutableSetOf() } += names }
            }
            old.parentNode.removeChild(old)
        }
        return named to needs
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

    private fun giveIds(keptOld: Set<Path>): Set<Path> {
        val given = catalogue.agents(withHidden = true).filter { it.file.parent == catalogue.yourAgents && it.id == null }.map { agent ->
            val text = agent.file.readText()
            if (agent.file !in keptOld) agent.file.resolveSibling("${agent.file.fileName}.old").writeText(text)
            agent.file.writeAtomically(withId(text, catalogue.unusedId(AGENT_ID)))
            agent.file
        }
        for (skill in catalogue.skills(withHidden = true).filter { it.file.startsWith(catalogue.yourSkills) && it.id == null }) {
            val file = skill.folder.resolve(SKILL_ID_FILE)
            if (file.exists()) file.resolveSibling("$SKILL_ID_FILE.old").writeBytes(file.readBytes())
            catalogue.giveSkillId(skill.folder)
        }
        return given.toSet()
    }

    private fun linkSkillsById(keptOld: Set<Path>) {
        for (agent in catalogue.agents(withHidden = true).filter { it.file.parent == catalogue.yourAgents && !it.allSkills }) {
            val ids = catalogue.skillIds(agent.skills)
            if (ids == agent.skills) continue
            val text = agent.file.readText()
            if (agent.file !in keptOld) agent.file.resolveSibling("${agent.file.fileName}.old").writeText(text)
            val (meta, body) = frontmatter(text)
            agent.file.writeAtomically("---\n" + yaml.dump(LinkedHashMap(meta).apply { put("skills", ids.joinToString(", ")) }) + "---\n" + body)
        }
    }

    private fun links(templatesKeptOld: Set<Path>) {
        for (file in home.resolve("templates").takeIf { it.exists() }?.listDirectoryEntries("*.bpmn").orEmpty()) {
            val document = runCatching { parse(file.readBytes()) }.getOrNull() ?: continue
            if (!catalogue.linkById(document, scripts)) continue
            if (file !in templatesKeptOld) file.resolveSibling("${file.fileName}.old").writeBytes(file.readBytes())
            file.writeAtomically(serialize(document))
        }
        for (process in repository.createProcessDefinitionQuery().latestVersion().list().filter { !it.resourceName.startsWith("shipped/") }) {
            val bpmn = repository.getProcessModel(process.id)!!.readAllBytes()
            if (!catalogue.linkById(parse(bpmn), scripts)) continue
            try {
                processes.deploy(bpmn, process.resourceName)
            } catch (e: Refused) {
                log.warn("The Process ${process.key} can't be linked by id, so it stays as it was: ${e.message}")
            }
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

/** The Skills each Agent needs, by the Agent's name. */
private typealias Needs = Map<String, Set<String>>

/** These needs and [more]. */
private fun Needs.and(more: Needs): Needs = (keys + more.keys).associateWith { this[it].orEmpty() + more[it].orEmpty() }

/**
 * [task] as the migrations have it, for a Run in flight on a version of its Process from before them: as the latest
 * version has it when its own names an old `ants.skill` (ADR 0024), and moved off the old shipped Agent it names
 * (ADR 0027). Else [task] itself.
 */
internal fun migrated(task: LockedExternalTask, repository: RepositoryService, catalogue: Catalogue): LockedExternalTask {
    val properties = latest(task, repository)
    val now = catalogue.onNewAgent(properties) ?: properties
    if (now === task.extensionProperties) return task
    return object : LockedExternalTask by task {
        override fun getExtensionProperties() = now
    }
}

/** [task]'s properties as the latest version of its Process has them when its own names an old `ants.skill`, else its own. */
private fun latest(task: LockedExternalTask, repository: RepositoryService): Map<String, String> {
    val properties = task.extensionProperties
    if (OLD_SKILL !in properties || SKILLS in properties || ASSIGNMENT in properties) return properties
    val latest = repository.createProcessDefinitionQuery().processDefinitionKey(task.processDefinitionKey).latestVersion().singleResult() ?: return properties
    val element = repository.getBpmnModelInstance(latest.id).getModelElementById<BaseElement>(task.activityId) ?: return properties
    return element.properties()
}
