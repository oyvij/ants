package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.math.roundToInt

/** A Template on the Templates page: its file's name, the name its sub-process gets, and whether it's Ants' own, read-only. */
data class TemplateRow(val name: String, val title: String, val shipped: Boolean)

/**
 * Templates: small `.bpmn` diagrams in `templates/` in ANTS_HOME (the user's) and `shipped/templates/` (Ants' own,
 * read-only). The page lists, creates, edits and copies them; the modeler's palette drops one into a Process.
 */
@Controller
class Templates(@Value("\${ants.home}") home: Path) {
    private val yours = home.resolve("templates")
    private val shipped = home.resolve("shipped/templates")

    private fun templates() = listOf(yours, shipped).filter { it.exists() }.flatMap { folder ->
        folder.listDirectoryEntries("*.bpmn").sorted().map { file ->
            val title = runCatching { process(parse(file.readBytes())).getAttribute("name") }.getOrNull()
            TemplateRow(file.nameWithoutExtension, title?.takeIf(String::isNotBlank) ?: file.nameWithoutExtension, folder == shipped)
        }
    }

    @GetMapping("/templates")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, message: String?): String {
        model.addAttribute("templates", templates())
        model.addAttribute("message", message)
        return "templates"
    }

    /** The palette's Templates section. */
    @GetMapping("/templates.json")
    @ResponseBody
    fun json() = templates()

    /** **New Template**: a blank diagram in the user's folder, opened in the modeler. */
    @PostMapping("/templates/new")
    fun create(@RequestParam name: String, model: Model): String {
        val error = nameError("A Template", name) ?: if (exists(name)) "A Template named $name already exists." else null
        if (error != null) return list(model, error)
        val document = parse(blank())
        // The id is the dropped sub-process's, which expressions read, so it is a JUEL name: no hyphens.
        rename(document, name.replace('-', '_').replace(Regex("^(?=[0-9])"), "_"), name)
        yours.resolve("$name.bpmn").writeAtomically(serialize(document))
        return "redirect:/templates/$name"
    }

    /** The modeler on the Template [name], with Save to its file; a shipped one is read-only, with Copy. */
    @GetMapping("/templates/{name}")
    fun edit(@PathVariable name: String, model: Model): String {
        val file = file(name)
        model.addAttribute("name", name)
        model.addAttribute("src", "/templates/$name/diagram.bpmn")
        model.addAttribute("action", "/templates/$name")
        model.addAttribute("saveLabel", "Save")
        model.addAttribute("copy", if (file.startsWith(shipped)) "/templates/$name/copy" else null)
        return "modeler"
    }

    @GetMapping("/templates/{name}/diagram.bpmn", produces = ["application/xml"])
    @ResponseBody
    fun diagram(@PathVariable name: String): ByteArray = file(name).readBytes()

    /** **Save**: writes the Template's file, with `operaton:` only, and opens it again. Nothing it was dropped into changes. */
    @PostMapping("/templates/{name}")
    fun save(@PathVariable name: String, @RequestParam bpmn: String): ResponseEntity<String> {
        val file = file(name).takeIf { it.startsWith(yours) }
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "The Template $name is read-only; Copy it to make your own.")
        file.writeAtomically(serialize(read(bpmn.toByteArray())))
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI("/templates/$name")).build()
    }

    /** **Copy**: the Template as `<name>-copy` in the user's folder, opened in the modeler. */
    @PostMapping("/templates/{name}/copy")
    fun copy(@PathVariable name: String): String {
        val copy = copyName(name, ::exists)
        yours.resolve("$copy.bpmn").writeAtomically(file(name).readBytes().decodeToString())
        return "redirect:/templates/$copy"
    }

    /** Drops the Template [name] into the diagram [bpmn] at ([x], [y]): the new diagram, and the sub-process's id to select. */
    @PostMapping("/templates/{name}/drop")
    @ResponseBody
    fun drop(@PathVariable name: String, @RequestParam bpmn: String, @RequestParam x: Double, @RequestParam y: Double): Map<String, String> {
        val target = read(bpmn.toByteArray())
        val element = try {
            drop(target, read(file(name).readBytes()), name, x.roundToInt(), y.roundToInt())
        } catch (e: Refused) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        return mapOf("bpmn" to serialize(target), "element" to element)
    }

    private fun exists(name: String) = listOf(yours, shipped).any { it.resolve("$name.bpmn").exists() }

    /** The file of the Template [name], the user's before Ants' own. */
    private fun file(name: String): Path = listOf(yours, shipped).map { it.resolve("$name.bpmn") }
        .firstOrNull { nameError("A Template", name) == null && it.exists() }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Template $name")

    private fun read(bpmn: ByteArray) = try {
        parse(bpmn).also(::operatonOnly)
    } catch (e: SAXException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
    }
}

private const val DC = "http://www.omg.org/spec/DD/20100524/DC"

/** The attributes that name another element by its id. */
private val references = setOf("sourceRef", "targetRef", "default", "attachedToRef", "bpmnElement", "sourceElement", "targetElement", "categoryValueRef")

/** The extension properties whose value is a Task's id. */
private val namesATask = setOf(ASKER, SEND_BACK, WALKTHROUGH)

private fun Node.children() = List(childNodes.length) { childNodes.item(it) }.filterIsInstance<Element>()

private fun Element.prefixed(name: String) = prefix?.let { "$it:$name" } ?: name

/** Every id of a BPMN element or its diagram in [this], in document order. */
private fun Document.ids() = getElementsByTagName("*").elements()
    .filter { it.namespaceURI == BPMN || it.namespaceURI == BPMNDI }.map { it.getAttribute("id") }.filter(String::isNotEmpty)

/**
 * Drops [template] into [target]'s Process as a collapsed sub-process centred on ([x], [y]), named after the Template's
 * Process, else [name]. The Template's elements keep their ids; an id [target] already has is suffixed `_2`, `_3`, …
 * (a JUEL name, so `${approve_2.output}` still reads), and every reference inside the Template follows: flows,
 * defaults, boundaries, the diagram and ids in `${…}` expressions.
 * The sub-process's own diagram is a plane of its own, as bpmn-js draws a collapsed sub-process. Gives its id.
 */
internal fun drop(target: Document, template: Document, name: String, x: Int, y: Int): String {
    val existing = target.ids().toSet()
    val taken = (existing + template.ids()).toMutableSet()
    // The first of id, id_2, id_3, … nothing has yet; taking it.
    fun free(id: String) = generateSequence(1, Int::inc).map { if (it == 1) id else "${id}_$it" }.first(taken::add)
    val renamed = template.ids().filter { it in existing }.associateWith(::free)

    if (renamed.isNotEmpty()) {
        // An id as a name in an expression, such as `${approve.output}` or `${runs.approve}`, but not a field `${x.approve}`.
        val ids = renamed.keys.sortedByDescending(String::length).joinToString("|", transform = Regex::escape)
        val reference = Regex("""(?:(?<![\w.])|(?<=\bruns\.))($ids)(?!\w)""")
        fun expressions(text: String) = Regex("""[$#]\{[^}]*}""").replace(text) { reference.replace(it.value) { id -> renamed.getValue(id.value) } }
        for (element in template.getElementsByTagName("*").elements()) {
            val diagram = element.namespaceURI == BPMN || element.namespaceURI == BPMNDI
            for (attribute in List(element.attributes.length) { element.attributes.item(it) }) attribute.nodeValue = when {
                attribute.localName == "id" && diagram || attribute.localName in references -> renamed[attribute.nodeValue]
                // The properties that name a Task; `ants.agent` and `ants.skill` may equal a Task's id but never name one.
                attribute.localName == "value" && element.getAttribute("name") in namesATask -> renamed[attribute.nodeValue]
                else -> null
            } ?: expressions(attribute.nodeValue)
            if (element.children().isEmpty() && element.textContent.isNotEmpty()) element.textContent =
                if (element.localName == "incoming" || element.localName == "outgoing") renamed[element.textContent] ?: element.textContent
                else expressions(element.textContent)
        }
    }

    val process = process(target)
    val source = process(template)
    val id = source.getAttribute("id")
    val sub = target.createElementNS(BPMN, process.prefixed("subProcess"))
    sub.setAttribute("id", id)
    sub.setAttribute("name", source.getAttribute("name").ifBlank { name })
    // The Process's own extensions, such as its history time to live, stay behind.
    for (child in source.children().filterNot { it.localName == "extensionElements" }) sub.appendChild(target.importNode(child, true))
    process.appendChild(sub)

    val definitions = target.documentElement
    val diagrams = definitions.children().filter { it.namespaceURI == BPMNDI }
    // What the Template's elements name outside its Process, such as a group's category.
    for (root in template.documentElement.children().filter { it.namespaceURI == BPMN && it !== source }) {
        definitions.insertBefore(target.importNode(root, true), diagrams.firstOrNull())
    }
    val plane = diagrams.flatMap(Element::children).firstOrNull { it.getAttribute("bpmnElement") == process.getAttribute("id") }
        ?: throw Refused("The Process has no diagram to drop the Template into.")
    plane.appendChild(target.createElementNS(BPMNDI, plane.prefixed("BPMNShape")).apply {
        setAttribute("id", free("${id}_di"))
        setAttribute("bpmnElement", id)
        setAttribute("isExpanded", "false")
        appendChild(target.createElementNS(DC, "dc:Bounds").apply {
            setAttribute("x", "${x - 50}")
            setAttribute("y", "${y - 40}")
            setAttribute("width", "100")
            setAttribute("height", "80")
        })
    })
    template.documentElement.children().firstOrNull { it.namespaceURI == BPMNDI }?.let { definitions.appendChild(target.importNode(it, true)) }
    return id
}
