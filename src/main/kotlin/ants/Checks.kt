package ants

import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.impl.BpmnModelConstants.BPMN20_NS
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent
import org.operaton.bpm.model.bpmn.instance.CallActivity
import org.operaton.bpm.model.bpmn.instance.EventDefinition
import org.operaton.bpm.model.bpmn.instance.ExclusiveGateway
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics
import org.operaton.bpm.model.bpmn.instance.Participant
import org.operaton.bpm.model.bpmn.instance.Process
import org.operaton.bpm.model.bpmn.instance.SequenceFlow
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.operaton.bpm.model.bpmn.instance.StartEvent
import org.operaton.bpm.model.bpmn.instance.SubProcess
import org.operaton.bpm.model.bpmn.instance.TimerEventDefinition
import org.operaton.bpm.model.bpmn.instance.UserTask
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonFormData
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputOutput
import org.operaton.bpm.model.xml.ModelException
import org.operaton.bpm.model.xml.instance.DomElement
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import org.operaton.bpm.engine.RepositoryService

/** One thing wrong with a Process, on the element it names (a BPMN id, or null for the whole file). A warning doesn't stop Save and deploy. */
data class Check(val element: String?, val message: String, val warning: Boolean = false)

/**
 * The Checks: what **Save and deploy** refuses, shown live in the modeler and on import. Each rule reads the Process,
 * written with `operaton:` only, and gives its Checks; a new Check is one more rule in [rules].
 */
@Component
class Checks(private val catalogue: Catalogue, private val repository: RepositoryService) {
    private val rules: List<(BpmnModelInstance) -> List<Check>> = listOf(::refusedElements, ::refusedUses, ::oneStartEvent, ::startFormNames, ::emptyInputs, ::defaultPaths, ::timerTimes, ::schedules,
        { agentTasks(catalogue, it) }, { references(catalogue, it) }, // these two in AgentTaskChecks.kt
        { calls(repository, it) }) // in CallActivityChecks.kt

    /** What the input pickers of the Task [element] in [bpmn] offer, as `${…}` references; see [offered]. */
    fun inputs(bpmn: String, element: String) = offered(catalogue, Bpmn.readModelFromStream(bpmn.byteInputStream()), element) { key ->
        repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult()?.let { repository.getBpmnModelInstance(it.id) }
    }

    fun of(bpmn: String): List<Check> = try {
        Bpmn.readModelFromStream(bpmn.byteInputStream()).let { model -> rules.flatMap { it(model) } }
    } catch (e: ModelException) {
        // The schema's own words are in the cause, such as which element isn't allowed where.
        listOf(Check(null, generateSequence<Throwable>(e) { it.cause }.last().message ?: "The file isn't BPMN."))
    }
}

/** The Built-in Tasks, by the topic Ants carries each out on (as in `static/modeler.js`). */
val builtIns = setOf("clone-repos", "accept-recommendations", "push")

/** The BPMN elements Ants runs, by their XML name; [refusedUses] refuses some uses of them. Anything else is refused. */
private val allowed = setOf(
    "startEvent", "endEvent", "intermediateCatchEvent", "boundaryEvent", "timerEventDefinition", "timeDate", "timeCycle", "timeDuration",
    "exclusiveGateway", "parallelGateway", "sequenceFlow", "conditionExpression",
    "serviceTask", "userTask", "subProcess", "callActivity",
    "multiInstanceLoopCharacteristics", "loopCardinality", "completionCondition",
    "textAnnotation", "text", "group", "association",
    "documentation", "extensionElements", "incoming", "outgoing",
    "laneSet", // its lanes are refused
)

private fun label(name: String?, id: String?) = name?.let { "$it ($id)" } ?: id

private val BaseElement.label get() = label(getAttributeValue("name"), id)

/** `adHocSubProcess` as "ad hoc sub process". */
private fun words(name: String) = name.replace(Regex("[A-Z]")) { " " + it.value.lowercase() }

private fun article(what: String) = if (what[0] in "aeiou") "an $what" else "a $what"

/** Every BPMN element written inside a Process, plus pools, read from the XML: Operaton's model skips some, such as an ad hoc sub-process. */
private fun BpmnModelInstance.elements(): List<DomElement> {
    fun below(element: DomElement): List<DomElement> = element.childElements.filter { it.namespaceURI == BPMN20_NS }.flatMap { listOf(it) + below(it) }
    return getModelElementsByType(Process::class.java).flatMap { below(it.domElement) } + getModelElementsByType(Participant::class.java).map { it.domElement }
}

/** Each element Ants doesn't run at all, such as a terminate end event, a DMN Task or a pool. */
private fun refusedElements(model: BpmnModelInstance) = model.elements().filter { it.localName !in allowed }.map { element ->
    val name = element.localName
    // An event definition and a loop are written inside the element drawn, so the Check names that one.
    val shown = if (name.endsWith("EventDefinition") || name.endsWith("LoopCharacteristics")) element.parentElement else element
    val what = when {
        name.endsWith("EventDefinition") -> "is " + article(words(name.removeSuffix("EventDefinition")) + " " + words(shown.localName)) + ", which Ants doesn't run."
        name == "task" -> "is a plain Task, which Ants doesn't run: make it an Agent, User or Built-in Task."
        name == "businessRuleTask" -> "is a business rule task (DMN), which Ants doesn't run."
        name == "standardLoopCharacteristics" -> "is a standard loop, which Ants doesn't run: draw the loop with an exclusive gateway."
        else -> "is " + article(words(name)) + ", which Ants doesn't run."
    }
    Check(shown.getAttribute("id"), label(shown.getAttribute("name"), shown.getAttribute("id")) + " " + what)
}

/** The elements Ants runs only in some uses, such as a timer boundary, which must interrupt a User Task. */
private fun refusedUses(model: BpmnModelInstance) = model.getModelElementsByType(BaseElement::class.java).mapNotNull { element ->
    val definitions = element.getChildElementsByType(EventDefinition::class.java)
    val loop = element.getChildElementsByType(MultiInstanceLoopCharacteristics::class.java).firstOrNull()
    val what = when {
        element is BoundaryEvent && definitions.isEmpty() -> "is a boundary event without a timer"
        element is BoundaryEvent && !element.cancelActivity() -> "is a non-interrupting timer boundary event"
        element is BoundaryEvent && element.attachedTo !is UserTask -> "is a timer boundary event on something other than a User Task"
        element is IntermediateCatchEvent && definitions.isEmpty() -> "is an intermediate catch event without a timer"
        element is SubProcess && element.domElement.localName == "subProcess" && element.triggeredByEvent() -> "is an event sub-process"
        element is ServiceTask && element.operatonType != "external" -> "is a service task that isn't an Agent or Built-in Task"
        element is ServiceTask && element.operatonTopic != AgentLauncher.TOPIC && element.operatonTopic !in builtIns ->
            return@mapNotNull Check(element.id, "${element.label} is on topic ${element.operatonTopic}, which no Built-in Task has.")
        loop == null -> null
        loop.completionCondition != null -> "has a multi-instance completion condition"
        element is ServiceTask && element.operatonTopic != AgentLauncher.TOPIC -> "is a multi-instance Built-in Task"
        element is ServiceTask || element is SubProcess || element is CallActivity -> null
        else -> "is a multi-instance " + words(element.domElement.localName)
    }
    what?.let { Check(element.id, "${element.label} $it, which Ants doesn't run.") }
}

/** A Process, and each sub-process, starts at exactly one start event. */
private fun oneStartEvent(model: BpmnModelInstance) = (
    model.getModelElementsByType(Process::class.java) +
        model.getModelElementsByType(SubProcess::class.java).filter { it.domElement.localName == "subProcess" && !it.triggeredByEvent() }
    ).flatMap { scope ->
        val starts = scope.getChildElementsByType(StartEvent::class.java)
        if (starts.isEmpty()) listOf(Check(scope.id, "${scope.label} has no start event."))
        else starts.drop(1).map { Check(it.id, "${it.label} is a second start event; a Process starts at exactly one.") }
    }

/** Each start-form field is a Run variable, so it needs a name of its own. */
private fun startFormNames(model: BpmnModelInstance) = model.getModelElementsByType(StartEvent::class.java).flatMap { start ->
    val fields = start.extensionElements?.elementsQuery?.filterByType(OperatonFormData::class.java)?.list().orEmpty().flatMap { it.operatonFormFields }
    fields.groupBy { it.operatonId }.filter { (name, same) -> !name.isNullOrBlank() && same.size > 1 }.keys
        .map { Check(start.id, "${start.label} has more than one start-form field named $it.") } +
        fields.filter { it.operatonId.isNullOrBlank() }
            .map { Check(start.id, "${start.label} has a start-form field, ${it.operatonLabel}, without a variable name.") }
}

/** An Agent Task input left empty: allowed, but likely a slip. */
private fun emptyInputs(model: BpmnModelInstance) = model.getModelElementsByType(ServiceTask::class.java)
    .filter { it.operatonTopic == AgentLauncher.TOPIC }
    .flatMap { task ->
        task.extensionElements?.elementsQuery?.filterByType(OperatonInputOutput::class.java)?.list().orEmpty()
            .flatMap { it.operatonInputParameters }
            .filter { it.textContent.isBlank() && it.domElement.childElements.isEmpty() }
            .map { Check(task.id, "${task.label} has an empty input ${it.operatonName}.", warning = true) }
    }

/** An exclusive gateway that chooses between paths takes its default one when no condition holds; a merge needs none. */
private fun defaultPaths(model: BpmnModelInstance) = model.getModelElementsByType(ExclusiveGateway::class.java)
    // By sourceRef: a file need not list a gateway's `outgoing`.
    .filter { gateway -> gateway.default == null && model.getModelElementsByType(SequenceFlow::class.java).count { it.source == gateway } > 1 }
    .map { Check(it.id, "${it.label} has no default path.") }

/** A timer waits for a duration, until a date or for a cron time, so it needs one of them. */
private fun timerTimes(model: BpmnModelInstance) = model.getModelElementsByType(TimerEventDefinition::class.java)
    .filter { listOfNotNull(it.timeDuration, it.timeDate, it.timeCycle).all { time -> time.textContent.isBlank() } }
    .map { (it.parentElement as BaseElement).let { event -> Check(event.id, "${event.label} is a timer without a time.") } }

/**
 * A Schedule is a cron Ants can run (Operaton's six fields, seconds first) and starts Runs with no one there, so every
 * required start-form field needs a stored value.
 */
private fun schedules(model: BpmnModelInstance): List<Check> {
    val timer = model.scheduleTimer() ?: return emptyList()
    val start = timer.parentElement as StartEvent
    val cron = model.schedule()
    val time = when {
        cron == null && timer.timeDuration == null && timer.timeDate == null -> null // timerTimes says so
        cron == null -> "${start.label} has a Schedule that isn't a cron: give it one, such as 0 0 8 * * * for every day at 08:00."
        else -> runCatching { CronExpression.parse(cron) }.exceptionOrNull()
            ?.let { "${start.label} has a Schedule that isn't a cron Ants can read (${it.message})." }
    }
    val fields = start.extensionElements?.elementsQuery?.filterByType(OperatonFormData::class.java)?.list().orEmpty().flatMap { it.operatonFormFields }
    return listOfNotNull(time?.let { Check(start.id, it) }) + fields
        .filter { field -> field.operatonValidation?.operatonConstraints.orEmpty().any { it.operatonName == "required" } }
        .filter { field -> field.operatonProperties?.operatonProperties.orEmpty().none { it.operatonId == "ants.stored" && !it.operatonValue.isNullOrBlank() } }
        .map { Check(start.id, "${start.label} has a Schedule, so its required start-form field ${it.operatonLabel ?: it.operatonId} needs a stored value.") }
}
