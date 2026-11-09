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
    private val store: SecretStore,
) : BuiltInWorker(externalTasks, "Script Task", TOPIC, Duration.ofMinutes(15), { it.includeExtensionProperties().enableCustomObjectDeserialization() }) {
    override fun handle(task: LockedExternalTask) {
        val element = repository.getBpmnModelInstance(task.processDefinitionId).getModelElementById<BaseElement>(task.activityId)
        val linked = task.extensionProperties[SCRIPT] ?: error("${task.activityId} names no Script")
        val kept = task.extensionProperties[nameBeside(linked)]
        val script = scripts.linked(linked, kept)
        if (script.secrets.isNotEmpty() && store.locked) {
            // Held as a failure with retries left, so no incident, and freed once the Safe is unlocked, keeping this
            // one worker free for the Script Tasks that need no Secret. ponytail: re-held each minute until then, as
            // a restart forgets who waits.
            externalTasks.handleFailure(task.id, WORKER, "Waiting on the locked Safe", null, 1, Duration.ofMinutes(1).toMillis())
            store.whenUnlocked { externalTasks.unlock(task.id) }
            return
        }
        val run = runtime.createProcessInstanceQuery().processInstanceId(task.processInstanceId).singleResult()!!.rootProcessInstanceId
        val inputNames = element?.inputs().orEmpty().map { it.operatonName }.toSet()
        val literals = element?.literalInputs().orEmpty().keys
        val properties = (script.inputs["properties"] as? Map<*, *>).orEmpty()
        val inputs = task.variables.filterKeys { it in inputNames }.mapValues { (name, value) ->
            if (name in literals && value is String) ScriptSchema.literal(value, properties[name] as? Map<*, *>).getOrDefault(value) else value
        }.toSortedMap()
        val workspace = names.runWorkspace(run).createDirectories()
        val secrets = try {
            scripts.secretEnvironment(script)
        } catch (failed: SecretProviders.Failed) {
            throw Failed(failed.message.substringBefore('\n'), failed.message)
        }
        val result = runner.run(script, secrets, scripts.file(script), workspace, run, inputs, Sender(script, run, task.activityId))
        if (result.exitCode != 0) throw Failed("The Script ${script.name} failed" + if (result.exitCode > 0) " with exit code ${result.exitCode}" else "", result.reported)
        if (result.failed) throw Failed("The payload of the Script ${script.name} breaks its output schema", result.reported)
        externalTasks.complete(task.id, WORKER, mapOf(task.activityId to linkedMapOf("exitCode" to result.exitCode, "output" to result.output, "payload" to result.payload)))
    }

    companion object {
        const val TOPIC = "script"

        val RESULT = listOf("exitCode", "output", "payload")
    }
}
