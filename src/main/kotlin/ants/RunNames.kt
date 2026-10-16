package ants

import org.operaton.bpm.engine.HistoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.impl.ServiceImpl
import org.operaton.bpm.engine.impl.persistence.entity.HistoricVariableInstanceEntity
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Run variable holding the Run's name, which the start form's Name field posts too. */
const val NAME = "ants.name"

/** The Run variable holding its Run workspace's folder name, given once at start ([folder]). */
const val FOLDER = "ants.folder"

/** The name of a Run left unnamed: its [process] and start time, as in `Build and ship, 12 Oct 21:40`. */
fun Clock.defaultName(process: String, start: Instant = instant()): String =
    "$process, " + DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).format(start.atZone(zone))

/**
 * The Run workspace folder of the Run [run] named [name]: `<slug>-<run>`, the slug the name in lowercase letters,
 * digits and hyphens, at most 40 characters; just `<run>` when nothing of the name is left.
 */
fun folder(name: String, run: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
    return if (slug.isEmpty()) run else "$slug-$run"
}

/** The Run workspace in this ANTS_HOME of the Run [run] whose [FOLDER] is [folder]: `runs/<folder>/`, else `runs/<run>/`. */
fun Path.runWorkspace(run: String, folder: String?): Path = resolve("runs").resolve(folder ?: run)

/**
 * What the user reads a Run by, instead of its id: its name, given on the start form, else its Process and start
 * time; and where its Run workspace is, the folder named after it at start. The id stays the engine's.
 */
@Component
class RunNames(
    private val history: HistoryService,
    private val runtime: RuntimeService,
    private val clock: Clock,
    @Value("\${ants.home}") private val home: Path,
) {
    /** The name of [run]; a Run started before Runs had names reads as an unnamed one. */
    fun name(run: String): String = variable(run, NAME) ?: history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult()
        ?.let { clock.defaultName(it.processDefinitionName ?: it.processDefinitionKey, it.startTime.toInstant()) } ?: run

    /** The Run workspace of [run], wherever it started: `runs/<slug>-<run>/`, or `runs/<run>/` for a Run from before. */
    fun runWorkspace(run: String): Path = home.runWorkspace(run, variable(run, FOLDER))

    /** The work branch [run] defaults to: `ants/<slug>`, or `ants/<run>` for a Run without a slug. */
    fun defaultBranch(run: String) = "ants/" + runWorkspace(run).fileName.toString().removeSuffix("-$run")

    /** Renames [run], live or ended; its folder and branch keep the name they started with. Blank keeps the name. */
    fun rename(run: String, name: String) {
        val given = runName(name) ?: return
        if (runtime.createProcessInstanceQuery().processInstanceId(run).count() > 0) return runtime.setVariable(run, NAME, given)
        // An ended Run's variables are history only, which the engine's API never changes, so its row is set directly.
        (history as ServiceImpl).commandExecutor.execute { context ->
            val id = history.createHistoricVariableInstanceQuery().processInstanceId(run).variableName(NAME).singleResult()?.id
            id?.let { context.dbEntityManager.selectById(HistoricVariableInstanceEntity::class.java, it).textValue = given }
        }
    }

    private fun variable(run: String, name: String) = history.variable(run, name) as? String
}

/** The value of the variable [name] of the process instance [run], live or ended; null when it has none. */
fun HistoryService.variable(run: String, name: String): Any? = createHistoricVariableInstanceQuery().processInstanceId(run).variableName(name).singleResult()?.value

/** A Run's name as given, trimmed and cut to [NAME_LENGTH] characters; null when blank. */
fun runName(given: String?): String? = given?.trim()?.take(NAME_LENGTH)?.ifEmpty { null }

/** The most characters a Run's name keeps. */
private const val NAME_LENGTH = 200
