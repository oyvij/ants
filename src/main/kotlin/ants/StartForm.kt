package ants

import org.operaton.bpm.engine.FormService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.model.bpmn.instance.Process
import org.springframework.stereotype.Component

/** What a start-form field asks for: Text, Markdown, or Repos (Source map names, git URLs and new repos' `owner/name`). */
enum class FieldType { TEXT, MARKDOWN, REPOS }

/**
 * One start-form field, read from the start event's `operaton:formData`. The engine knows only its own form types, so
 * Ants' [type], [help] and [stored] value live in the field's properties [FIELD_TYPE], [HELP] and [STORED]. An engine
 * `enum` field is a dropdown of its [choices], each value's id to its name, in order.
 */
data class Field(
    val name: String, val label: String, val type: FieldType, val required: Boolean, val help: String?, val stored: String?,
    val choices: Map<String, String> = emptyMap(), val example: String? = null,
) {
    /** The stored value of a Repos field: its entries, separated by commas or spaces. */
    val storedRepos: List<String> get() = stored.orEmpty().split(Regex("[,\\s]+")).filter(String::isNotEmpty)
}

const val FIELD_TYPE = "ants.type"
const val HELP = "ants.help"
const val STORED = "ants.stored"
const val EXAMPLE = "ants.example"

/** A start form the user filled in wrong, such as a required field left empty. */
class Unfilled(message: String) : Exception(message)

/** A Process's start form, and the one way a Run starts: with the form's values as its variables. */
@Component
class StartForm(
    private val forms: FormService,
    private val runtime: RuntimeService,
    private val repository: RepositoryService,
    private val preRun: PreRun,
    private val settings: Settings,
) {
    /** The start-form fields of the Process version [definition], in order. */
    fun fields(definition: ProcessDefinition): List<Field> = forms.getStartFormData(definition.id).formFields.map { field ->
        Field(
            name = field.id,
            label = field.label ?: field.id,
            type = field.properties[FIELD_TYPE]?.let { type -> FieldType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) } } ?: FieldType.TEXT,
            required = field.validationConstraints.orEmpty().any { it.name == "required" },
            help = field.properties[HELP]?.takeIf(String::isNotBlank),
            stored = field.properties[STORED],
            choices = (field.type.getInformation("values") as? Map<*, *>).orEmpty().entries.associate { (id, name) -> "$id" to "$name" },
            example = field.properties[EXAMPLE]?.takeIf(String::isNotBlank),
        )
    }

    /**
     * Starts a Run of [definition] with [values] from its start form, each field under its variable name: Text and
     * Markdown as a string, Repos as a list of its entries, and the Run's name under [NAME]. Values for anything else are
     * ignored. The Run keeps its Profile ([profile]) as [PROFILE], and the repos picked, if any, as [SCOPE]. Refused
     * with [CantStart] while the pre-Run check gives reasons.
     */
    fun start(definition: ProcessDefinition, values: Map<String, List<String>>): ProcessInstance =
        runtime.startProcessInstanceById(definition.id, check(definition, values))

    fun check(definition: ProcessDefinition, values: Map<String, List<String>>): Map<String, Any> {
        val fields = fields(definition)
        val variables = fields.associate { field ->
            // A Repos field's typed-in entries come as one value, separated by commas or spaces.
            val given = values[field.name].orEmpty().let { if (field.type == FieldType.REPOS) it.flatMap(::entries) else it }.filter(String::isNotBlank)
            if (field.required && given.isEmpty()) throw Unfilled("${field.label} is required")
            if (field.choices.isNotEmpty() && given.any { it !in field.choices }) throw Unfilled("${field.label} is none of its choices")
            field.name to if (field.type == FieldType.REPOS) given.distinct() else given.firstOrNull().orEmpty()
        }
        val repos = fields.filter { it.type == FieldType.REPOS }.flatMap { variables[it.name] as List<*> }.map(Any?::toString).distinct()
        val profile = profile(definition, values[PROFILE]?.firstOrNull())
        val reasons = preRun.reasons(definition, repos, profile)
        if (reasons.isNotEmpty()) throw CantStart(reasons)
        // The picked repos are kept by remote, so Clone repos clones exactly them whatever the Source map says later.
        val known = RunSource(profile).repos(settings).associate { it.name to it.remote }
        val scope = repos.map { known[it] ?: it }.takeIf { it.isNotEmpty() }?.let { SCOPE to ArrayList(it) }
        // The Name field's, empty when not given: RunLifecycle names the Run then.
        val run = listOfNotNull(NAME to values[NAME]?.firstOrNull().orEmpty(), scope, profile?.let { PROFILE to it })
        return variables + run
    }

    /**
     * The name of the Profile a Run of [definition] uses: the one [picked] on its start form, else the Process's own
     * (its [PROFILE] property), else the default. Null with no Profiles configured, as every Run then uses the default
     * Vault and Source map.
     */
    fun profile(definition: ProcessDefinition, picked: String?): String? = if (settings.profiles().isEmpty()) null
    else picked?.takeIf(String::isNotBlank) ?: diagramProfile(definition) ?: settings.defaultProfile()

    /** The Profile [definition] names on its diagram (its [PROFILE] property), if it names one. */
    fun diagramProfile(definition: ProcessDefinition): String? =
        repository.getBpmnModelInstance(definition.id).getModelElementById<Process>(definition.key)?.property(PROFILE)?.takeIf(String::isNotBlank)

    private fun entries(value: String) = value.split(Regex("[,\\s]+"))
}
