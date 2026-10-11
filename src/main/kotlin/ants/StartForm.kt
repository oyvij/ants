package ants

import org.operaton.bpm.engine.FormService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.springframework.stereotype.Component

/** What a start-form field asks for: Text, Markdown, or Repos (Source map names). */
enum class FieldType { TEXT, MARKDOWN, REPOS }

/**
 * One start-form field, read from the start event's `operaton:formData`. The engine knows only its own form types, so
 * Ants' [type], [help] and [stored] value live in the field's properties `ants.type`, `ants.help` and `ants.stored`.
 */
data class Field(val name: String, val label: String, val type: FieldType, val required: Boolean, val help: String?, val stored: String?) {
    /** The stored value of a Repos field: Source map names, separated by commas or spaces. */
    val storedRepos: List<String> get() = stored.orEmpty().split(Regex("[,\\s]+")).filter(String::isNotEmpty)
}

/** A start form the user filled in wrong, such as a required field left empty. */
class Unfilled(message: String) : Exception(message)

/** A Process's start form, and the one way a Run starts: with the form's values as its variables. */
@Component
class StartForm(private val forms: FormService, private val runtime: RuntimeService, private val preRun: PreRun) {
    /** The start-form fields of the Process version [definition], in order. */
    fun fields(definition: ProcessDefinition): List<Field> = forms.getStartFormData(definition.id).formFields.map { field ->
        Field(
            name = field.id,
            label = field.label ?: field.id,
            type = field.properties["ants.type"]?.let { type -> FieldType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) } } ?: FieldType.TEXT,
            required = field.validationConstraints.orEmpty().any { it.name == "required" },
            help = field.properties["ants.help"]?.takeIf(String::isNotBlank),
            stored = field.properties["ants.stored"],
        )
    }

    /**
     * Starts a Run of [definition] with [values] from its start form, each field under its variable name: Text and
     * Markdown as a string, Repos as a list of Source map names. Values for anything else are ignored. Refused with
     * [CantStart] while the pre-Run check gives reasons.
     */
    fun start(definition: ProcessDefinition, values: Map<String, List<String>>): ProcessInstance {
        val variables = fields(definition).associate { field ->
            val given = values[field.name].orEmpty().filter(String::isNotBlank)
            if (field.required && given.isEmpty()) throw Unfilled("${field.label} is required")
            field.name to if (field.type == FieldType.REPOS) given else given.firstOrNull().orEmpty()
        }
        val reasons = preRun.reasons(definition, variables.values.filterIsInstance<List<*>>().flatten().map(Any?::toString))
        if (reasons.isNotEmpty()) throw CantStart(reasons)
        return runtime.startProcessInstanceById(definition.id, variables)
    }
}
