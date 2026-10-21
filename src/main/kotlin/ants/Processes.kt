package ants

import org.operaton.bpm.engine.ProcessEngineException
import jakarta.servlet.http.HttpServletResponse
import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.ExtendedModelMap
import org.springframework.ui.Model
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.SAXException
import java.io.StringWriter
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@Controller
class Processes(
    private val repository: RepositoryService, private val startForm: StartForm, private val settings: Settings, private val checks: Checks, private val preRun: PreRun,
    private val schedules: Schedules, private val history: HistoryService, private val names: RunNames, private val clock: Clock, @Value("\${ants.home}") private val home: Path,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Deploys the shipped Processes, such as Set up Vault, through the Checks like any other. Each is its own
     * deployment, `shipped/<file>`, so a start deploys it again only when Ants ships a change, and never over a user's
     * Save of it: once the latest version under its key is the user's, a shipped change is not deployed.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun deployShipped() {
        val folder = home.resolve("shipped/processes").takeIf { it.exists() } ?: return
        for (file in folder.listDirectoryEntries("*.bpmn")) try {
            val key = process(parse(file.readBytes())).getAttribute("id")
            val saved = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult()
            if (saved != null && !saved.resourceName.startsWith("shipped/")) {
                log.info("The shipped Process $file is not deployed over your Save of $key")
                continue
            }
            deploy(file.readBytes(), "shipped/${file.fileName}")
        } catch (e: SAXException) {
            log.warn("The shipped Process $file can't be read: ${e.message}")
        } catch (e: Refused) {
            log.warn("The shipped Process $file can't be deployed: ${e.message}")
        }
    }

    /** The Processes list: a row each, its pre-Run checks run side by side, since each may ask claude, gh and git. */
    @GetMapping("/processes")
    fun list(model: Model): String {
        val processes = repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list()
        model.addAttribute("processes", processes)
        val login = preRun.oneLogin() // asked once for them all
        model.addAttribute("rows", Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            processes.map { process -> threads.submit<ProcessRow> { row(process, login) } }.map { it.get() }
        })
        return "processes"
    }

    private fun row(process: ProcessDefinition, login: () -> Map<*, *>): ProcessRow {
        val profile = startForm.profile(process, null)
        return ProcessRow(
            process, process.resourceName.startsWith("shipped/"), settings.profile(profile), schedules.of(process),
            reasons(process, startForm.fields(process), profile, login).isEmpty(),
            history.createHistoricProcessInstanceQuery().processDefinitionKey(process.key).rootProcessInstances().active().count(),
        )
    }

    /** The pre-Run check of [process] as its start form opens, with its Repos fields' stored values. */
    private fun reasons(process: ProcessDefinition, fields: List<Field>, profile: String?, login: () -> Map<*, *> = preRun.oneLogin()) =
        preRun.reasons(process, fields.filter { it.type == FieldType.REPOS }.flatMap { it.storedRepos }, profile, login)

    /** The deployed Processes as the modeler's call activity panel picks them: each one's start-form fields are the call's inputs. */
    @GetMapping("/processes.json")
    @ResponseBody
    fun processes() = repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list().map { process ->
        mapOf("key" to process.key, "name" to (process.name ?: process.key),
            "fields" to startForm.fields(process).map { mapOf("name" to it.name, "label" to it.label, "required" to it.required) })
    }

    @PostMapping("/processes")
    fun import(@RequestParam file: MultipartFile, model: Model): String {
        val name = file.originalFilename?.takeIf { it.endsWith(".bpmn") } ?: "process.bpmn"
        try {
            deploy(file.bytes, name)
        } catch (e: Refused) {
            model.addAttribute("error", "$name: ${e.message}")
            return list(model)
        }
        return "redirect:/processes"
    }

    /** **Save and deploy** in the modeler: on to the Process page once deployed, else the reason as plain text. */
    @PostMapping("/processes/deploy")
    fun save(@RequestParam bpmn: String): ResponseEntity<String> = try {
        ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI("/processes/" + deploy(bpmn.toByteArray()))).build()
    } catch (e: Refused) {
        ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(e.message)
    }

    /** The live Checks: what Save and deploy would refuse in [bpmn], and the warnings. */
    @PostMapping("/processes/checks")
    @ResponseBody
    fun checks(@RequestParam bpmn: String): List<Check> = try {
        checks.of(operatonOnly(bpmn.toByteArray()))
    } catch (e: Refused) {
        listOf(Check(null, e.message ?: "The file can't be read."))
    }

    /** What an Agent Task's input pickers offer: start-form fields, Run variables and the Results of earlier Tasks. */
    @PostMapping("/processes/inputs")
    @ResponseBody
    fun inputs(@RequestParam bpmn: String, @RequestParam element: String): List<String> = checks.inputs(operatonOnly(bpmn.toByteArray()), element)

    /**
     * Ants' own deploy path: everything reaches Operaton through here, written with `operaton:` only, and only when no
     * Check fails. Gives the Process's key.
     */
    private fun deploy(bpmn: ByteArray, name: String? = null): String = try {
        val xml = operatonOnly(bpmn)
        val failed = checks.of(xml).filterNot { it.warning }
        if (failed.isNotEmpty()) throw Refused(failed.joinToString("\n") { it.message })
        val key = process(parse(xml.toByteArray())).getAttribute("id")
        val resource = name ?: "$key.bpmn"
        repository.createDeployment().name(resource).enableDuplicateFiltering(true).addString(resource, xml).deploy()
        key
    } catch (e: ProcessEngineException) {
        throw Refused(e.message)
    }

    /** [bpmn] as Ants reads and deploys it, with `operaton:` only. */
    private fun operatonOnly(bpmn: ByteArray): String = try {
        serialize(parse(bpmn).also(::operatonOnly))
    } catch (e: SAXException) {
        throw Refused(e.message)
    }

    /**
     * **New Process**: the modeler on a blank Process, or on a copy of the Process [from], under a key of its own made
     * from [name] (by default the copied Process's name and "copy").
     */
    @GetMapping("/processes/new")
    fun new(@RequestParam(required = false) name: String?, @RequestParam(required = false) from: String?, model: Model): String {
        val source = from?.takeIf(String::isNotBlank)?.let(::latest)
        val title = name?.takeIf(String::isNotBlank) ?: source?.let { "${it.name ?: it.key} copy" } ?: "New Process"
        val query = listOfNotNull("name" to title, source?.let { "from" to it.key })
        model.addAttribute("name", title)
        model.addAttribute("src", "/processes/new.bpmn?" + query.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) })
        model.addAttribute("note", "Draft")
        model.addAttribute("builder", builder())
        return "modeler"
    }

    /** The diagram **New Process** opens: blank, or a copy of [from], renamed to [name] under a free key. */
    @GetMapping("/processes/new.bpmn", produces = ["application/xml"])
    @ResponseBody
    fun newDiagram(@RequestParam name: String, @RequestParam(required = false) from: String?): String {
        val document = parse(from?.let { repository.getProcessModel(latest(it).id).readAllBytes() } ?: blank())
        // `new` is the route to this page, so no Process may take it as its key.
        val key = freeName(name) { it == "new" || repository.createProcessDefinitionQuery().processDefinitionKey(it).count() > 0 }
        rename(document, key, name)
        return serialize(document)
    }

    /** The modeler on the latest version of a deployed Process; **Save and deploy** makes the next version. */
    @GetMapping("/processes/{key}/edit")
    fun edit(@PathVariable key: String, model: Model): String {
        val process = latest(key)
        model.addAttribute("name", process.name ?: process.key)
        model.addAttribute("src", "/processes/$key/diagram.bpmn")
        model.addAttribute("page", "/processes/$key")
        if (process.resourceName.startsWith("shipped/")) model.addAttribute("note", "Shipped: saving makes it your own")
        model.addAttribute("builder", builder())
        return "modeler"
    }

    /** The modeler's Process Builder column: on, or why it is off. Only a Process has it, never a Template. */
    private fun builder() = if (settings.processBuilder().first == "claude") "" else ProcessBuilderTools.CLAUDE_ONLY

    /** The Process page, its start form for the Profile [profile] picked on it, else the Process's own, else the default. */
    @GetMapping("/processes/{key}")
    fun process(@PathVariable key: String, @RequestParam(PROFILE, required = false) profile: String?, model: Model): String {
        val process = latest(key)
        model.addAttribute("process", process)
        val filled = model.getAttribute("filled") as MultiValueMap<*, *>?
        val fields = startForm.fields(process).map { field ->
            filled?.let { field.copy(stored = it[field.name].orEmpty().joinToString(" ")) } ?: field
        }
        model.addAttribute("fields", fields)
        model.addAttribute("runName", filled?.get(NAME)?.firstOrNull()?.toString().orEmpty())
        val picked = startForm.profile(process, profile)
        model.addAttribute("profiles", settings.profiles().keys.toList())
        model.addAttribute("profile", picked)
        model.addAttribute("profileFrom", if (!profile.isNullOrBlank()) "picked here" else if (startForm.diagramProfile(process) != null) "set on the diagram" else "the default")
        model.addAttribute("source", settings.profile(picked))
        model.addAttribute("repos", RunSource(picked).repos(settings).map(Repo::name)) // the Repos field's choices
        model.addAttribute("schedule", schedules.of(process))
        model.addAttribute("schedules", schedules.of(key))
        val runs = history.createHistoricProcessInstanceQuery().processDefinitionKey(key).rootProcessInstances().orderByProcessInstanceStartTime().desc().list()
        model.addAttribute("runs", runs)
        model.addAttribute("names", runs.associate { it.id to names.name(it.id) })
        model.addAttribute("clock", clock)
        // A refused Start has already checked what was posted.
        if (!model.containsAttribute("reasons")) model.addAttribute("reasons", reasons(process, fields, picked))
        return "process"
    }

    /** **Start**: a Run with the start form's values, else the Process page again with what's missing or why it can't start. */
    @PostMapping("/processes/{key}/runs")
    fun start(@PathVariable key: String, @RequestParam values: MultiValueMap<String, String>, model: Model, response: HttpServletResponse): String =
        orRefused(key, values, model, response) { "redirect:/runs/" + startForm.start(latest(key), values).id }

    @PostMapping("/processes/{key}/schedule")
    fun schedule(@PathVariable key: String, @RequestParam values: MultiValueMap<String, String>, model: Model, response: HttpServletResponse): String {
        val cron = values.getFirst("cron").orEmpty()
        model.addAttribute("cron", cron)
        return orRefused(key, values, model, response) {
            schedules.add(latest(key).key, values, cron)
            "redirect:/processes/$key"
        }
    }

    private fun orRefused(key: String, values: MultiValueMap<String, String>, model: Model, response: HttpServletResponse, submit: () -> String): String = try {
        submit()
    } catch (e: Unfilled) {
        refused(key, values, model.addAttribute("error", e.message), response)
    } catch (e: CantStart) {
        refused(key, values, model.addAttribute("reasons", e.reasons), response)
    }

    private fun refused(key: String, values: MultiValueMap<String, String>, model: Model, response: HttpServletResponse): String {
        response.status = HttpStatus.BAD_REQUEST.value()
        model.addAttribute("filled", values)
        return process(key, values.getFirst(PROFILE), model)
    }

    /** Pauses the Process's Schedule: no Run starts on it, not even for the times missed, until it's resumed. */
    @PostMapping("/processes/{key}/schedule/pause")
    fun pause(@PathVariable key: String): String {
        schedules.pause(latest(key).key, true)
        return "redirect:/processes/$key"
    }

    @PostMapping("/processes/{key}/schedule/resume")
    fun resume(@PathVariable key: String): String {
        schedules.pause(latest(key).key, false)
        return "redirect:/processes/$key"
    }

    /** Removes the Schedule: the next version of the Process starts by hand only, its start event a none start. */
    @PostMapping("/processes/{key}/schedule/remove")
    fun remove(@PathVariable key: String, model: Model, response: HttpServletResponse): String {
        if (schedules.of(latest(key)) == null) return "redirect:/processes/$key"
        val document = parse(repository.getProcessModel(latest(key).id).readAllBytes())
        for (start in process(document).getElementsByTagNameNS(BPMN, "startEvent").elements().filter { it.parentNode.localName == "process" }) {
            start.getElementsByTagNameNS(BPMN, "timerEventDefinition").elements().forEach { start.removeChild(it) }
        }
        try {
            deploy(serialize(document).toByteArray())
        } catch (e: Refused) {
            response.status = HttpStatus.BAD_REQUEST.value()
            model.addAttribute("error", e.message)
            return process(key, null, model)
        }
        schedules.forget(key)
        return "redirect:/processes/$key"
    }

    /** **Start now** on a scheduled Run that couldn't start: the Run with the stored values, else the Process page and why. */
    @PostMapping("/processes/{key}/schedule/start")
    fun startNow(@PathVariable key: String): String = try {
        "redirect:/runs/" + schedules.startNow(latest(key)).id
    } catch (_: Exception) {
        "redirect:/processes/$key" // which shows the reason
    }

    /** **Dismiss** a scheduled Run that couldn't start, then back to the Notification list when dismissed from there ([back]). */
    @PostMapping("/processes/{key}/schedule/dismiss")
    fun dismiss(@PathVariable key: String, @RequestParam(required = false) back: String?): String {
        schedules.dismiss(latest(key).key)
        return "redirect:" + if (back == "notifications") "/notifications" else "/processes/$key"
    }

    @GetMapping("/processes/{key}/diagram.bpmn", produces = ["application/xml"])
    @ResponseBody
    fun diagram(@PathVariable key: String): ByteArray = repository.getProcessModel(latest(key).id).readAllBytes()

    private fun latest(key: String) = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult()
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Process $key")
}

private const val CAMUNDA = "http://camunda.org/schema/1.0/bpmn"
private const val OPERATON = "http://operaton.org/schema/1.0/bpmn"
internal const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
internal const val BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI"

/**
 * A row of the Processes list: whether the Process is [shipped] or the user's, the [profile] whose Vault and Source
 * map its Runs use, its [schedule], whether it is [ready] to start, and its [live] Runs.
 */
class ProcessRow(val process: ProcessDefinition, val shipped: Boolean, val profile: Profile?, val schedule: Schedule?, val ready: Boolean, val live: Long)

/** Why a Process can't be deployed, in the XML parser's or the engine's words. */
class Refused(message: String?) : Exception(message)

/**
 * A page's save run for the Process Builder (ADR 0019), with no page to show: the path [save] redirects to, else a
 * [Refused] with the error it put on the model as [error].
 */
fun redirected(error: String, save: (Model) -> String): String {
    val model = ExtendedModelMap()
    val view = save(model)
    return view.removePrefix("redirect:").takeIf { it != view } ?: throw Refused(model[error]?.toString())
}

internal fun parse(bpmn: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) // no external entities from an upload
}.newDocumentBuilder().parse(bpmn.inputStream())

internal fun serialize(document: Document) =
    StringWriter().also { TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it)) }.toString()

internal fun NodeList.elements() = List(length) { item(it) as Element }

/** A diagram with a start event alone, as **New Process** and **New Template** begin. */
internal fun blank() = Processes::class.java.getResource("/blank.bpmn")!!.readBytes()

/** Gives the Process in [document] the id [id] and the name [name], its diagram following. */
internal fun rename(document: Document, id: String, name: String) {
    val process = process(document)
    for (plane in document.getElementsByTagNameNS(BPMNDI, "BPMNPlane").elements()) {
        if (plane.getAttribute("bpmnElement") == process.getAttribute("id")) plane.setAttribute("bpmnElement", id)
    }
    process.setAttribute("id", id)
    process.setAttribute("name", name)
}

/** The Process a diagram defines; Ants draws one per file. */
internal fun process(document: Document) =
    document.getElementsByTagNameNS(BPMN, "process").elements().firstOrNull() ?: throw Refused("The file defines no Process.")

/** Moves every element and attribute in Camunda's namespace to Operaton's. */
internal fun operatonOnly(document: Document) {
    fun rewrite(element: Element) {
        for (attribute in List(element.attributes.length) { element.attributes.item(it) as Attr }) when {
            attribute.namespaceURI == XMLNS_ATTRIBUTE_NS_URI && attribute.value == CAMUNDA -> element.removeAttributeNode(attribute)
            attribute.namespaceURI == CAMUNDA -> document.renameNode(attribute, OPERATON, "operaton:${attribute.localName}")
        }
        val renamed = if (element.namespaceURI == CAMUNDA) document.renameNode(element, OPERATON, "operaton:${element.localName}") as Element else element
        var child = renamed.firstChild
        while (child != null) {
            if (child is Element) rewrite(child)
            child = child.nextSibling
        }
    }
    rewrite(document.documentElement)
    document.documentElement.setAttributeNS(XMLNS_ATTRIBUTE_NS_URI, "xmlns:operaton", OPERATON)
}
