package ants

import org.operaton.bpm.engine.ProcessEngineException
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
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
import javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@Controller
class Processes(private val repository: RepositoryService, private val runtime: RuntimeService, private val checks: Checks) {
    @GetMapping("/processes")
    fun list(model: Model): String {
        model.addAttribute("processes", repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list())
        return "processes"
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
        return "modeler"
    }

    /** The diagram **New Process** opens: blank, or a copy of [from], renamed to [name] under a free key. */
    @GetMapping("/processes/new.bpmn", produces = ["application/xml"])
    @ResponseBody
    fun newDiagram(@RequestParam name: String, @RequestParam(required = false) from: String?): String {
        val document = parse(from?.let { repository.getProcessModel(latest(it).id).readAllBytes() } ?: javaClass.getResource("/blank.bpmn")!!.readBytes())
        val process = process(document)
        // `new` is the route to this page, so no Process may take it as its key.
        val key = freeName(name) { it == "new" || repository.createProcessDefinitionQuery().processDefinitionKey(it).count() > 0 }
        for (plane in document.getElementsByTagNameNS(BPMNDI, "BPMNPlane").elements()) {
            if (plane.getAttribute("bpmnElement") == process.getAttribute("id")) plane.setAttribute("bpmnElement", key)
        }
        process.setAttribute("id", key)
        process.setAttribute("name", name)
        return serialize(document)
    }

    /** The modeler on the latest version of a deployed Process; **Save and deploy** makes the next version. */
    @GetMapping("/processes/{key}/edit")
    fun edit(@PathVariable key: String, model: Model): String {
        val process = latest(key)
        model.addAttribute("name", process.name ?: process.key)
        model.addAttribute("src", "/processes/$key/diagram.bpmn")
        return "modeler"
    }

    @GetMapping("/processes/{key}")
    fun process(@PathVariable key: String, model: Model): String {
        model.addAttribute("process", latest(key))
        return "process"
    }

    @PostMapping("/processes/{key}/runs")
    fun start(@PathVariable key: String, @RequestParam prompt: String): String {
        if (prompt.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A Run needs a prompt")
        return "redirect:/runs/" + runtime.startProcessInstanceById(latest(key).id, mapOf("prompt" to prompt)).id
    }

    @GetMapping("/processes/{key}/diagram.bpmn", produces = ["application/xml"])
    @ResponseBody
    fun diagram(@PathVariable key: String): ByteArray = repository.getProcessModel(latest(key).id).readAllBytes()

    private fun latest(key: String) = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult()
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Process $key")
}

private const val CAMUNDA = "http://camunda.org/schema/1.0/bpmn"
private const val OPERATON = "http://operaton.org/schema/1.0/bpmn"
private const val BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL"
private const val BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI"

/** Why a Process can't be deployed, in the XML parser's or the engine's words. */
class Refused(message: String?) : Exception(message)

private fun parse(bpmn: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) // no external entities from an upload
}.newDocumentBuilder().parse(bpmn.inputStream())

private fun serialize(document: Document) =
    StringWriter().also { TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it)) }.toString()

private fun NodeList.elements() = List(length) { item(it) as Element }

/** The Process a diagram defines; Ants draws one per file. */
private fun process(document: Document) =
    document.getElementsByTagNameNS(BPMN, "process").elements().firstOrNull() ?: throw Refused("The file defines no Process.")

/** Moves every element and attribute in Camunda's namespace to Operaton's. */
private fun operatonOnly(document: Document) {
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
