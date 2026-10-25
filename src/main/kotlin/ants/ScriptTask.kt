package ants

import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.externaltask.LockedExternalTask
import org.operaton.bpm.model.bpmn.instance.BaseElement
import org.springframework.stereotype.Component
import java.time.Duration
import kotlin.io.path.createDirectories

const val SCRIPT = "ants.script"

@Component
class ScriptTask(
    private val externalTasks: ExternalTaskService,
    private val runtime: RuntimeService,
    private val repository: RepositoryService,
    private val names: RunNames,
    private val scripts: Scripts,
    private val runner: ScriptRunner,
) : BuiltInWorker(externalTasks, "Script Task", TOPIC, Duration.ofMinutes(15), { it.includeExtensionProperties().enableCustomObjectDeserialization() }) {
    override fun handle(task: LockedExternalTask) {
        val element = repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<BaseElement>(task.activityId)
        val linked = task.extensionProperties[SCRIPT] ?: error("${task.activityId} names no Script")
        val kept = task.extensionProperties[nameBeside(linked)]
        val script = scripts.linked(linked, kept)
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult().rootProcessInstanceId
        val inputNames = element?.inputs().orEmpty().map { it.operatonName }.toSet()
        val inputs = task.variables.filterKeys { it in inputNames }.toSortedMap()
        val result = runner.run(scripts.file(script), names.runWorkspace(run).createDirectories(), run, inputs, script.inputs, script.vars, scripts.secretEnvironment(script, scripts.ownSecrets(script)), script.output)
        if (result.exitCode != 0) throw Failed("The Script ${script.name} failed" + if (result.exitCode > 0) " with exit code ${result.exitCode}" else "", result.told)
        if (result.failed) throw Failed("The payload of the Script ${script.name} breaks its output schema", result.told)
        externalTasks.complete(task.id, WORKER, mapOf(task.activityId to linkedMapOf("exitCode" to result.exitCode, "output" to result.output, "payload" to result.payload)))
    }

    companion object {
        const val TOPIC = "script"

        val RESULT = listOf("exitCode", "output", "payload")
    }
}
