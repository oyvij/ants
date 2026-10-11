package ants

import org.operaton.bpm.engine.ProcessEngineException
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.http.HttpStatus
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
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.StringWriter
import javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@Controller
class Processes(private val repository: RepositoryService, private val runtime: RuntimeService) {
    @GetMapping("/processes")
    fun list(model: Model): String {
        model.addAttribute("processes", repository.createProcessDefinitionQuery().latestVersion().orderByProcessDefinitionName().asc().list())
        return "processes"
    }

    /** Ants' own deploy path: everything reaches Operaton through here, written with `operaton:` only. */
    @PostMapping("/processes")
    fun import(@RequestParam file: MultipartFile, model: Model): String {
        val name = file.originalFilename?.takeIf { it.endsWith(".bpmn") } ?: "process.bpmn"
        try {
            repository.createDeployment().name(name).enableDuplicateFiltering(true).addString(name, operatonOnly(file.bytes)).deploy()
        } catch (e: Exception) {
            if (e !is ProcessEngineException && e !is SAXException) throw e
            model.addAttribute("error", "$name: ${e.message}")
            return list(model)
        }
        return "redirect:/processes"
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

/** Moves every element and attribute in Camunda's namespace to Operaton's. */
private fun operatonOnly(bpmn: ByteArray): String {
    val document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) // no external entities from an upload
    }
        .newDocumentBuilder().parse(bpmn.inputStream())
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
    return StringWriter().also { TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it)) }.toString()
}
