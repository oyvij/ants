package ants

import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.EndEvent
import org.operaton.bpm.model.bpmn.instance.EventDefinition
import org.operaton.bpm.model.bpmn.instance.ExclusiveGateway
import org.operaton.bpm.model.bpmn.instance.Group
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics
import org.operaton.bpm.model.bpmn.instance.ParallelGateway
import org.operaton.bpm.model.bpmn.instance.Process
import org.operaton.bpm.model.bpmn.instance.SequenceFlow
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.operaton.bpm.model.bpmn.instance.StartEvent
import org.operaton.bpm.model.bpmn.instance.SubProcess
import org.operaton.bpm.model.bpmn.instance.TextAnnotation
import org.operaton.bpm.model.bpmn.instance.TimerEventDefinition
import org.operaton.bpm.model.bpmn.instance.UserTask
import org.operaton.bpm.model.bpmn.instance.bpmndi.BpmnShape
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonFormData
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonIn
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputOutput
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import kotlin.math.roundToInt

/** One element of a diagram as the Process Builder reads it; [inside] is the sub-process it is in, null for the Process. */
data class Shape(
    val id: String, val kind: String, val name: String, val inside: String?, val x: Int, val y: Int, val width: Int, val height: Int,
    val incoming: List<String>, val outgoing: List<String>, val properties: Map<String, Any?>, val inputs: Map<String, String>, val outputs: List<String>?,
)

/** A sequence flow, and whether it is its decision's default. */
data class Flow(val id: String, val from: String, val to: String, val name: String, val condition: String?, val default: Boolean)

/** A diagram as the Process Builder reads it: its elements in document order, its sequence flows and its start form's fields. */
class Diagram(val elements: List<Shape>, val connections: List<Flow>, val startForm: List<Map<String, Any>>)

/**
 * The Process Builder's reading of a diagram, the open one's, a Template's or a deployed Process's, from its BPMN XML.
 * Every answer fits in one tool result however large the diagram is: [summary] shortens long texts and pages, and
 * [element] gives one element in full.
 */
@Component
class Diagrams(private val catalogue: Catalogue, private val scripts: Scripts, private val json: JsonMapper) {

    /** The diagram in [bpmn], `camunda:` (as the modeler saves it) or `operaton:`. */
    fun read(bpmn: ByteArray): Diagram {
        val model = Bpmn.readModelFromStream(serialize(parse(bpmn).also(::operatonOnly)).byteInputStream())
        val read = laterRead(model, scripts)
        val connections = model.getModelElementsByType(SequenceFlow::class.java).map { flow ->
            Flow(flow.id, flow.source.id, flow.target.id, flow.name.orEmpty(), flow.conditionExpression?.textContent,
                (flow.source as? ExclusiveGateway)?.default == flow)
        }
        val shapes = model.getModelElementsByType(BpmnShape::class.java).mapNotNull { shape ->
            val element = shape.bpmnElement as? BaseElement ?: return@mapNotNull null
            if (element is Process || element.elementType.typeName in setOf("participant", "lane")) return@mapNotNull null
            val kind = kindOf(element) ?: element.elementType.typeName.replaceFirstChar(Char::uppercase)
            val bounds = shape.bounds
            Shape(
                element.id, kind, element.getAttributeValue("name").orEmpty(), (element.parentElement as? SubProcess)?.id,
                bounds.x.roundToInt(), bounds.y.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt(),
                connections.filter { it.to == element.id }.map { it.id }, connections.filter { it.from == element.id }.map { it.id },
                properties(element, kind), inputs(element), read[element.id],
            )
        }
        val start = model.getModelElementsByType(StartEvent::class.java).firstOrNull { it.parentElement is Process }
        return Diagram(shapes, connections, start?.let(::form).orEmpty())
    }

    /**
     * The elements of [diagram] in [inside] (a sub-process's id) or with the [ids], all of them without either, each
     * with its connections, long texts shortened, as many as fit from [cursor] on: `next` is the cursor of the rest.
     */
    fun summary(diagram: Diagram, inside: String? = null, ids: List<String>? = null, cursor: Int = 0, budget: Int = PAGE): Map<String, Any?> {
        if (inside != null && diagram.elements.none { it.id == inside && it.kind == "subprocess" }) throw Refused("No sub-process $inside.")
        val unknown = ids.orEmpty() - diagram.elements.map { it.id }.toSet()
        if (unknown.isNotEmpty()) throw Refused("No element ${unknown.joinToString(", ")}.")
        val scoped = diagram.elements.filter { (inside == null || it.inside == inside) && (ids == null || it.id in ids) }
        if (cursor < 0 || cursor > scoped.size) throw Refused("No page at cursor $cursor: there are ${scoped.size} elements.")
        val inScope = scoped.map { it.id }.toSet()
        val flows = diagram.connections.associateBy { it.id }
        // Each connection comes once, with its source, or with its target when its source is out of scope.
        fun attached(shape: Shape) = (shape.outgoing + shape.incoming.filter { flows[it]?.from !in inScope }).mapNotNull(flows::get)
        val startForm = if (inside == null && ids == null && cursor == 0) diagram.startForm.map(::shortened) else null
        var size = json.writeValueAsString(startForm).length
        val elements = mutableListOf<Map<String, Any?>>()
        val connections = mutableListOf<Map<String, Any?>>()
        var next = cursor
        while (next < scoped.size) {
            val element = brief(scoped[next])
            val its = attached(scoped[next]).map(::flow)
            val more = json.writeValueAsString(element).length + json.writeValueAsString(its).length
            if (elements.isNotEmpty() && size + more > budget) break
            size += more
            elements += element
            connections += its
            next++
        }
        val truncated = json.writeValueAsString(elements).contains("\"truncated\":true") || json.writeValueAsString(startForm).contains("\"truncated\":true")
        return listOfNotNull(
            "elements" to elements, "connections" to connections, startForm?.let { "startForm" to it },
            ("next" to next).takeIf { next < scoped.size },
            ("note" to listOfNotNull(
                "More elements are left: call again with cursor $next.".takeIf { next < scoped.size },
                "A truncated text shows its start and length: element(id) gives it in full.".takeIf { truncated },
            ).joinToString(" ")).takeIf { next < scoped.size || truncated },
        ).toMap()
    }

    /** One element of [diagram] in full: every property, its connections, and for a sub-process what is in it, as [summary] gives it. */
    fun element(diagram: Diagram, id: String): Map<String, Any?> {
        val shape = diagram.elements.firstOrNull { it.id == id } ?: throw Refused("No element $id.")
        val flows = diagram.connections.associateBy { it.id }
        val full = position(shape) + mapOf(
            "properties" to shape.properties, "inputs" to shape.inputs, "outputs" to shape.outputs,
            "incoming" to shape.incoming.mapNotNull(flows::get).map(::flow), "outgoing" to shape.outgoing.mapNotNull(flows::get).map(::flow),
        ).filterValues { it != null }
        if (shape.kind != "subprocess") return full
        return full + ("children" to summary(diagram, inside = id, budget = PAGE - json.writeValueAsString(full).length))
    }

    private fun position(shape: Shape): Map<String, Any?> = mapOf(
        "id" to shape.id, "kind" to shape.kind, "name" to shape.name, "in" to shape.inside,
        "x" to shape.x, "y" to shape.y, "width" to shape.width, "height" to shape.height,
    ).filterValues { it != null }

    private fun brief(shape: Shape): Map<String, Any?> = position(shape) + mapOf(
        "incoming" to shape.incoming, "outgoing" to shape.outgoing,
        "properties" to shortened(shape.properties), "inputs" to shortened(shape.inputs), "outputs" to shape.outputs,
    ).filterValues { it != null }

    private fun flow(flow: Flow): Map<String, Any?> = mapOf(
        "id" to flow.id, "from" to flow.from, "to" to flow.to, "name" to flow.name, "condition" to flow.condition, "default" to flow.default,
    )

    /** [values] with each text longer than [SHORT] cut to its start, its length and `truncated`. */
    private fun shortened(values: Map<String, Any?>): Map<String, Any?> = values.mapValues { (_, value) ->
        when (value) {
            is String if value.length > SHORT -> mapOf("start" to value.take(SHORT), "length" to value.length, "truncated" to true)
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") shortened(value as Map<String, Any?>)
            else -> value
        }
    }

    /** An element's properties as the properties panel shows them: Agents, Skills and Scripts by name, the rest as written. */
    private fun properties(element: BaseElement, kind: String): Map<String, Any?> {
        val written = element.properties()
        val properties: MutableMap<String, Any?> = if (kind != "agent" && kind != "script") written.toMutableMap() else {
            val links = Links(written::get)
            fun named(value: String, current: () -> String) = runCatching(current).getOrNull() ?: links.kept(value) ?: value
            written.filterKeys { !it.startsWith(LINKED_NAME) }.toMutableMap<String, Any?>().apply {
                written[AGENT]?.let { put(AGENT, links.agentName(catalogue) ?: it) }
                written[SKILLS]?.let { put(SKILLS, Job(written::get).listed.orEmpty().joinToString(", ") { v -> named(v) { catalogue.linkedSkill(v, links.kept(v)).name } }) }
                written[SCRIPT]?.let { v -> put(SCRIPT, named(v) { scripts.linked(v, links.kept(v)).name }) }
            }
        }
        if (kind == "built-in") properties["topic"] = (element as ServiceTask).operatonTopic
        if (element is CallActivity) properties["calls"] = element.calledElement
        val timer = definitions(element).singleOrNull() as? TimerEventDefinition
        timer?.timeDuration?.let { properties["timeDuration"] = it.textContent }
        timer?.timeCycle?.let { properties["timeCycle"] = it.textContent }
        timer?.timeDate?.let { properties["timeDate"] = it.textContent }
        val loop = element.getChildElementsByType(MultiInstanceLoopCharacteristics::class.java).firstOrNull()
        if (loop != null) properties["multiInstance"] = mapOf("sequential" to loop.isSequential, "collection" to loop.operatonCollection, "elementVariable" to loop.operatonElementVariable)
        return properties
    }

    /** An Agent, Built-in or Script Task's inputs, or a call activity's, by name. */
    private fun inputs(element: BaseElement): Map<String, String> =
        if (element is CallActivity) element.extensions<OperatonIn>().associate { it.operatonTarget to it.operatonSourceExpression.orEmpty() }
        else element.extensions<OperatonInputOutput>().flatMap { it.operatonInputParameters }.associate { it.operatonName to it.textContent.orEmpty() }

    /** The start form's fields, as the modeler's panel reads them. */
    private fun form(start: StartEvent): List<Map<String, Any>> = start.extensions<OperatonFormData>().flatMap { it.operatonFormFields }.map { field ->
        val properties = field.operatonProperties?.operatonProperties.orEmpty().associate { it.operatonId to it.operatonValue.orEmpty() }
        mapOf(
            "id" to field.operatonId.orEmpty(), "label" to field.operatonLabel.orEmpty(), "type" to (properties["ants.type"] ?: "text"),
            "help" to properties["ants.help"].orEmpty(), "example" to properties["ants.example"].orEmpty(), "stored" to properties["ants.stored"].orEmpty(),
            "required" to field.operatonValidation?.operatonConstraints.orEmpty().any { it.operatonName == "required" },
        )
    }

    companion object {
        /** The most an answer holds, in characters of JSON: well under what a tool result may hold. */
        const val PAGE = 24_000

        /** The longest text [summary] gives whole. */
        const val SHORT = 200

        private fun definitions(element: BaseElement): List<EventDefinition> = element.getChildElementsByType(EventDefinition::class.java).toList()

        /** What the modeler calls an element (modeler.js `kindOf`); null for what it has no name for. */
        fun kindOf(element: BaseElement): String? {
            val timer = definitions(element).let { it.size == 1 && it[0] is TimerEventDefinition }
            return when (element) {
                is StartEvent -> "start".takeIf { definitions(element).isEmpty() }
                is EndEvent -> "end".takeIf { definitions(element).isEmpty() }
                is IntermediateCatchEvent -> "timer".takeIf { timer }
                is BoundaryEvent -> "timer-boundary".takeIf { timer }
                is ExclusiveGateway -> "exclusive"
                is ParallelGateway -> "parallel"
                is ServiceTask -> if (element.operatonType != "external") null else when (element.operatonTopic) {
                    AgentLauncher.TOPIC -> "agent"
                    ScriptTask.TOPIC -> "script"
                    else -> "built-in"
                }
                is UserTask -> "user"
                is SubProcess -> "subprocess".takeIf { !element.triggeredByEvent() }
                is CallActivity -> "call"
                is TextAnnotation -> "annotation"
                is Group -> "group"
                else -> "task".takeIf { element.elementType.typeName == "task" }
            }
        }
    }
}
