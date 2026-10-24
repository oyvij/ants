package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.Activity
import org.operaton.bpm.model.bpmn.instance.EndEvent
import org.operaton.bpm.model.bpmn.instance.ExclusiveGateway
import org.operaton.bpm.model.bpmn.instance.FlowElement
import org.operaton.bpm.model.bpmn.instance.FlowNode
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics
import org.operaton.bpm.model.bpmn.instance.Process
import org.operaton.bpm.model.bpmn.instance.SequenceFlow
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.operaton.bpm.model.bpmn.instance.StartEvent
import org.operaton.bpm.model.bpmn.instance.SubProcess
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperties
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals

/** A drift guard over the shipped `.bpmn` files: no dangling flow refs, and each Template a Process embeds still matches the Template. */
class ShippedBpmnTest {
    /** The shipped files themselves: the test classpath's `shipped/` holds BootTest's fixtures. */
    private fun files(folder: String) = Path.of("src/main/resources/shipped/$folder").listDirectoryEntries("*.bpmn").sorted()
    private fun read(file: Path): BpmnModelInstance = Bpmn.readModelFromFile(file.toFile()).also(Bpmn::validateModel)

    @Test
    fun `every shipped Process and Template is valid BPMN whose incoming and outgoing refs name the flows that really enter and leave`() {
        for (file in files("processes") + files("templates")) {
            val model = read(file)
            val flows = model.getModelElementsByType(SequenceFlow::class.java)
            for (node in model.getModelElementsByType(FlowNode::class.java)) {
                fun refs(tag: String) = node.domElement.childElements.filter { it.localName == tag }.map { it.textContent.trim() }.toSet()
                assertEquals(flows.filter { it.target == node }.map { it.id }.toSet(), refs("incoming"), "${file.name}: ${node.id}'s incoming")
                assertEquals(flows.filter { it.source == node }.map { it.id }.toSet(), refs("outgoing"), "${file.name}: ${node.id}'s outgoing")
            }
        }
    }

    @Test
    fun `each Template a shipped Process embeds has the Template's Tasks, inputs and conditions`() {
        val templates = files("templates").associate { file -> read(file).getModelElementsByType(Process::class.java).single().let { it.id to shape(it.flowElements) } }
        val embedded = files("processes").flatMap { file ->
            read(file).getModelElementsByType(SubProcess::class.java).filter { it.id in templates }.map { Triple(file.name, it.id, shape(it.flowElements)) }
        }
        assert(embedded.size >= 4) { "only ${embedded.size} embedded Templates found" }
        for ((file, id, shape) in embedded) assertEquals(templates[id], shape, "$file: $id drifted from templates/${id.replace('_', '-')}.bpmn")
    }

    @Test
    fun `each shipped Task that uses one of Matt Pocock's Skills carries its Assignment and Result schema, as his Skills hold neither`() {
        val his = setOf("grilling", "to-spec", "to-tickets", "implement", "tdd", "code-review", "pr", "codebase-design")
        var tasks = 0
        for (file in files("processes") + files("templates")) for (task in read(file).getModelElementsByType(ServiceTask::class.java)) {
            val properties = task.extensions<OperatonProperties>().flatMap { it.operatonProperties }.associate { it.operatonName to it.operatonValue }
            assert(properties["ants.skill"] !in his + "grill") { "${file.name}: ${task.id} still names ${properties["ants.skill"]} as its ants.skill" }
            if (words(properties[SKILLS].orEmpty()).none { it in his }) continue
            tasks++
            assert(!properties[ASSIGNMENT].isNullOrBlank()) { "${file.name}: ${task.id} has no Assignment" }
            assert(!properties[RESULT_SCHEMA].isNullOrBlank()) { "${file.name}: ${task.id} has no Result schema" }
        }
        assertEquals(17, tasks) // 15 of his own, and the two fix Tasks, which list tdd, code-review and codebase-design
    }

    /** The elements of a Template, less what a copy renames: its events' ids, its flows' ids, its inputs' values. */
    private fun shape(elements: Collection<FlowElement>): List<String> {
        fun ref(node: FlowNode) = when (node) {
            is StartEvent -> "start"
            is EndEvent -> "end"
            else -> node.id
        }
        return elements.mapNotNull { element ->
            when (element) {
                is SequenceFlow -> "${ref(element.source)} -> ${ref(element.target)} ${element.name} ${element.conditionExpression?.textContent}"
                is StartEvent, is EndEvent -> null
                is FlowNode -> listOf(
                    element.elementType.typeName, element.id, element.name,
                    (element as? ServiceTask)?.operatonTopic,
                    (element as? ExclusiveGateway)?.default?.let { ref(it.target) },
                    ((element as? Activity)?.loopCharacteristics as? MultiInstanceLoopCharacteristics)?.let { "${it.isSequential} ${it.operatonCollection} ${it.operatonElementVariable}" },
                    element.extensions<OperatonProperties>().flatMap { it.operatonProperties }.map { "${it.operatonName}=${it.operatonValue}" }.sorted(),
                    element.inputs().map { it.operatonName }.sorted(),
                ).joinToString(" ")
                else -> element.elementType.typeName
            }
        }.sorted()
    }
}
