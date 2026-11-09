package ants

import org.operaton.bpm.engine.RepositoryService
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes

// The kinds of a Setup that are diagrams: Templates and Processes, after the Agents, Skills and Scripts they link.

/**
 * The user's own Templates in a Setup: `templates/<name>.bpmn`, never their Build logs. A Template has no id, so one
 * named like a Template here, the user's or Ants' own, clashes and lands only renamed.
 */
@Component
@Order(20)
class TemplatesInSetup(private val templates: Templates) : SetupKind {
    override val noun = "Template"
    override val folder = "templates"

    override fun export() = templates.yours().associate { "$folder/${it.fileName}" to it.readBytes() }

    override fun read(files: Map<String, ByteArray>) = files.filterKeys { it.count { c -> c == '/' } == 1 && it.endsWith(".bpmn") }.map { (path, bytes) ->
        val name = Path.of(path).nameWithoutExtension
        SetupItem(this, path, name, name, mapOf(path to bytes))
    }

    /** Its name, which [land] checks. */
    override fun isId(id: String) = true

    override fun has(id: String) = false

    override fun taken(name: String, id: String) = templates.exists(name)

    override fun land(item: SetupItem, name: String) = templates.add(name, item.files.values.single())
}

/**
 * The user's published Processes in a Setup: `processes/<key>.bpmn`, the latest version of each, its key its id, and
 * a Process Ants ships only once the user published their own version of it. Each lands through the Checks as Publish
 * deploys it, one already here becoming its next version, and after the Processes in the Setup it calls.
 */
@Component
@Order(30)
class ProcessesInSetup(private val repository: RepositoryService, private val processes: Processes, private val drafts: Drafts) : SetupKind {
    override val noun = "Process"
    override val folder = "processes"

    private fun latest() = repository.createProcessDefinitionQuery().latestVersion().list()

    override fun export() = latest().filterNot { it.shipped }.associate { "$folder/${it.key}.bpmn" to repository.getProcessModel(it.id)!!.readAllBytes() }

    override fun read(files: Map<String, ByteArray>): List<SetupItem> {
        val items = files.filterKeys { it.count { c -> c == '/' } == 1 && it.endsWith(".bpmn") }.map { (path, bytes) ->
            runCatching { process(parse(bytes)) }.fold(
                { SetupItem(this, path, it.getAttribute("id"), it.getAttribute("name").ifBlank { it.getAttribute("id") }, mapOf(path to bytes)) },
                { SetupItem(this, path, "", Path.of(path).nameWithoutExtension, mapOf(path to bytes), "it can't be read: ${it.message}") },
            )
        }
        val byId = items.filter { it.problem == null }.associateBy { it.id }
        val ordered = linkedSetOf<SetupItem>()
        // Depth first, each Process after the ones it calls; a call leading back is left to the Checks to refuse.
        fun visit(item: SetupItem, path: Set<SetupItem>) {
            if (item in ordered || item in path) return
            if (item.problem == null) calls(item).mapNotNull(byId::get).forEach { visit(it, path + item) }
            ordered += item
        }
        items.forEach { visit(it, emptySet()) }
        return ordered.toList()
    }

    /** The keys of the Processes [item] calls. */
    private fun calls(item: SetupItem) = parse(item.files.values.single()).getElementsByTagNameNS(BPMN, "callActivity").elements().map { it.getAttribute("calledElement") }

    override fun isId(id: String) = SAFE_KEY.matches(id)

    override fun has(id: String) = repository.createProcessDefinitionQuery().processDefinitionKey(id).count() > 0

    override fun taken(name: String, id: String) =
        latest().any { it.key != id && (it.name ?: it.key) == name } || drafts.all(DraftKind.PROCESS).any { it.key != id && it.name == name }

    override fun land(item: SetupItem, name: String) {
        val bpmn = item.files.values.single().let { bytes ->
            if (name == item.name) bytes else serialize(parse(bytes).also { process(it).setAttribute("name", name) }).toByteArray()
        }
        processes.deploy(bpmn)
    }
}
