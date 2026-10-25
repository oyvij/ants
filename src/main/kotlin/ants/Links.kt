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

    fun agentName(catalogue: Catalogue): String? = agentValue?.let { runCatching { agent(catalogue).name }.getOrNull() ?: kept(it) ?: it }

    fun startingSkillName(catalogue: Catalogue): String? = Job(property).skill?.let { runCatching { catalogue.linkedSkill(it, kept(it)).name }.getOrNull() ?: kept(it) ?: it }

    private fun kept(value: String) = property(nameBeside(value))
}

fun Catalogue.link(document: Document): Boolean {
    var relinked = false
    for (task in agentTaskElements(document)) {
        val properties = propertyElements(task)
        fun value(name: String) = properties[name]?.getAttribute("value")
        fun link(value: String, find: (String, String?) -> Pair<String?, String>?): Pair<String, String?> {
            val kept = value(nameBeside(value))
            val found = runCatching { find(value, kept) }.getOrNull() ?: return value to kept
            return (found.first ?: found.second) to found.second.takeIf { found.first != null }
        }
        val agent = properties[AGENT]
        val agentLink = agent?.getAttribute("value")?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            link(value) { v, kept -> linkedAgent(v, kept).let { it.id to it.name } }
        }
        val skills = properties[SKILLS]
        val listed = words(skills?.getAttribute("value"))
        val skillLinks = listed.map { value -> link(value) { v, kept -> linkedSkill(v, kept).let { it.id to it.name } } }
        properties.filterKeys { it.startsWith(LINKED_NAME) }.values.forEach { it.parentNode.removeChild(it) }
        if (agent != null && agentLink != null) {
            if (agentLink.first != agent.getAttribute("value")) relinked = true
            agent.setAttribute("value", agentLink.first)
            agentLink.second?.let { besides(agent, agentLink.first, it) }
        }
        if (skills != null) {
            if (skillLinks.map { it.first } != listed) {
                relinked = true
                skills.setAttribute("value", skillLinks.joinToString(", ") { it.first })
            }
            skillLinks.distinctBy { it.first }.reversed().forEach { (id, name) -> name?.let { besides(skills, id, it) } }
        }
    }
    return relinked
}

private fun besides(linking: Element, id: String, name: String) {
    val named = linking.cloneNode(false) as Element
    named.setAttribute("name", nameBeside(id))
    named.setAttribute("value", name)
    linking.parentNode.insertBefore(named, linking.nextSibling)
}

internal fun agentTaskElements(document: Document) = document.getElementsByTagNameNS(BPMN, "serviceTask").elements()
    .filter { task -> List(task.attributes.length) { task.attributes.item(it) }.any { it.localName == "topic" && it.nodeValue == AgentLauncher.TOPIC } }

internal fun propertyElements(task: Element) = task.getElementsByTagNameNS("*", "property").elements()
    .filter { it.parentNode.localName == "properties" && it.parentNode.parentNode.localName == "extensionElements" }.associateBy { it.getAttribute("name") }
