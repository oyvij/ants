package ants

import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.w3c.dom.Document
import org.w3c.dom.Element

const val LINKED_NAME = "ants.name."

fun nameBeside(id: String) = LINKED_NAME + id

fun Catalogue.linkedAgent(value: String, kept: String?): Agent = linked(value, kept, ::agentWithId, ::agent)

fun Catalogue.linkedSkill(value: String, kept: String?): Skill = linked(value, kept, ::skillWithId, ::skill)

private fun <T> linked(value: String, kept: String?, withId: (String) -> T, named: (String) -> T): T =
    runCatching { withId(value) }.getOrNull() ?: named(kept ?: value)

class Links(private val property: (String) -> String?) {
    constructor(task: BaseElement) : this(task::property)

    private val agentValue = property(AGENT)?.trim()?.takeIf(String::isNotBlank)

    val hasAgent get() = agentValue != null

    fun agent(catalogue: Catalogue): Agent = catalogue.linkedAgent(agentValue ?: error("The Task has no Agent"), kept(agentValue))

    fun skills(catalogue: Catalogue): List<Result<Skill>>? = Job(property).listed?.map { runCatching { catalogue.linkedSkill(it, kept(it)) } }

    fun scripts(scripts: Scripts): List<Result<Script>>? = property(SCRIPTS)?.let(::words)?.map { runCatching { scripts.linked(it, kept(it)) } }

    fun agentName(catalogue: Catalogue): String? = agentValue?.let { shown(it) { agent(catalogue).name } }

    fun startingSkillName(catalogue: Catalogue): String? = Job(property).skill?.let { shown(it) { catalogue.linkedSkill(it, kept(it)).name } }

    private fun shown(value: String, current: () -> String) = runCatching(current).getOrNull() ?: kept(value) ?: value

    private fun kept(value: String) = property(nameBeside(value))
}

private data class Link(val value: String, val name: String?)

fun Catalogue.linkById(document: Document, scripts: Scripts): Boolean {
    var relinked = false
    for (task in agentTaskElements(document)) {
        val properties = propertyElements(task)
        fun link(value: String, find: (String, String?) -> Pair<String?, String>): Link {
            val kept = properties[nameBeside(value)]?.getAttribute("value")
            val (id, name) = runCatching { find(value, kept) }.getOrNull() ?: return Link(value, kept)
            return if (id == null) Link(name, null) else Link(id, name)
        }
        val agent = properties[AGENT]
        val agentLink = agent?.getAttribute("value")?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            link(value) { v, kept -> linkedAgent(v, kept).let { it.id to it.name } }
        }
        val skills = properties[SKILLS]
        val listed = words(skills?.getAttribute("value"))
        val skillLinks = listed.map { value -> link(value) { v, kept -> linkedSkill(v, kept).let { it.id to it.name } } }
        val scriptsProperty = properties[SCRIPTS]
        val listedScripts = words(scriptsProperty?.getAttribute("value"))
        val scriptLinks = listedScripts.map { value -> link(value) { v, kept -> scripts.linked(v, kept).let { it.id to it.name } } }
        properties.filterKeys { it.startsWith(LINKED_NAME) }.values.forEach { it.parentNode.removeChild(it) }
        if (agent != null && agentLink != null) {
            if (agentLink.value != agent.getAttribute("value")) relinked = true
            agent.setAttribute("value", agentLink.value)
            addNameBeside(agent, agentLink)
        }
        if (skills != null) {
            if (skillLinks.map { it.value } != listed) {
                relinked = true
                skills.setAttribute("value", skillLinks.joinToString(", ") { it.value })
            }
            skillLinks.distinctBy { it.value }.reversed().forEach { addNameBeside(skills, it) }
        }
        if (scriptsProperty != null) {
            if (scriptLinks.map { it.value } != listedScripts) {
                relinked = true
                scriptsProperty.setAttribute("value", scriptLinks.joinToString(", ") { it.value })
            }
            scriptLinks.distinctBy { it.value }.reversed().forEach { addNameBeside(scriptsProperty, it) }
        }
    }
    return relinked
}

private fun addNameBeside(linking: Element, link: Link) {
    val name = link.name ?: return
    val named = linking.cloneNode(false) as Element
    named.setAttribute("name", nameBeside(link.value))
    named.setAttribute("value", name)
    linking.parentNode.insertBefore(named, linking.nextSibling)
}

internal fun agentTaskElements(document: Document) = serviceTaskElements(document, AgentLauncher.TOPIC)

internal fun serviceTaskElements(document: Document, topic: String) = document.getElementsByTagNameNS(BPMN, "serviceTask").elements()
    .filter { task -> List(task.attributes.length) { task.attributes.item(it) }.any { it.localName == "topic" && it.nodeValue == topic } }

internal fun propertyElements(task: Element) = task.getElementsByTagNameNS("*", "property").elements()
    .filter { it.parentNode.localName == "properties" && it.parentNode.parentNode.localName == "extensionElements" }.associateBy { it.getAttribute("name") }

fun Scripts.linkById(document: Document): Boolean {
    var relinked = false
    for (task in serviceTaskElements(document, ScriptTask.TOPIC)) {
        val properties = propertyElements(task)
        val linking = properties[SCRIPT] ?: continue
        val value = linking.getAttribute("value").trim()
        val kept = properties[nameBeside(value)]?.getAttribute("value")
        val link = runCatching { linked(value, kept) }.getOrNull()?.let { Link(it.id, it.name) } ?: Link(value, kept)
        properties.filterKeys { it.startsWith(LINKED_NAME) }.values.forEach { it.parentNode.removeChild(it) }
        if (link.value != linking.getAttribute("value")) relinked = true
        linking.setAttribute("value", link.value)
        addNameBeside(linking, link)
    }
    return relinked
}
