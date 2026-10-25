package ants

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

data class OwnSecretRow(val name: String, val value: String = "", val was: String = "") {
    val set get() = was.isNotEmpty()
}

data class ScriptForm(
    val name: String,
    val description: String,
    val code: String,
    val inputs: List<SchemaRow>,
    val vars: String,
    val own: List<OwnSecretRow>,
    val linked: List<String>,
    val output: List<SchemaRow> = emptyList(),
) {
    val shownOwn get() = own + OwnSecretRow("")

    val varsMap: Map<String, String>
        get() = vars.lines().filter(String::isNotBlank).associate { it.substringBefore('=').trim() to it.substringAfter('=', "") }

    /** The Script this form saves, each schema keeping what [kept], the saved Script's, holds beyond the rows. No output rows is no output schema. */
    fun script(id: String, kept: Script? = null) = Script(
        id, name, description, ScriptSchema.schema(inputs, kept?.inputs.orEmpty()),
        output.takeIf { it.isNotEmpty() }?.let { ScriptSchema.schema(it, kept?.output.orEmpty()) },
        varsMap, (own.map { it.name } + linked).distinct(),
    )

    fun error(others: List<Script>, was: String?): String? = nameError("A Script", name)
        ?: "The Scripts page keeps the name $name for itself.".takeIf { name == TEST_RUN }
        ?: "A Script named $name already exists.".takeIf { name != was && others.any { it.name == name } }
        ?: "A Script needs a description.".takeIf { description.isBlank() }
        ?: ScriptSchema.names(output).firstOrNull { !Regex("[A-Za-z_][A-Za-z0-9_-]*").matches(it) }?.let { "A payload field's name is letters, digits, hyphens and underscores, not $it." }
        ?: ScriptSchema.names(inputs).firstOrNull { !Regex("[A-Za-z_][A-Za-z0-9_-]*").matches(it) }?.let { "An input's name is letters, digits, hyphens and underscores, not $it." }
        ?: (varsMap.keys + own.map { it.name }).firstOrNull { !Regex("[A-Z_][A-Z0-9_]*").matches(it) }
            ?.let { "A var or Secret's name is upper case letters, digits and underscores, such as SLACK_TOKEN, not $it." }

    companion object {
        fun of(params: Map<String, List<String>>): ScriptForm {
            fun param(name: String) = params[name]?.firstOrNull().orEmpty()
            val own = generateSequence(0, Int::inc).takeWhile { "secrets[$it].name" in params }
                .map { OwnSecretRow(param("secrets[$it].name").trim(), param("secrets[$it].value"), param("secrets[$it].was")) }
                .filter { it.name.isNotEmpty() }.toList()
            return ScriptForm(param("name").trim(), param("description").trim(), param("code"), ScriptSchema.rows(params, "inputs"), param("vars"), own, params["linked"].orEmpty(), ScriptSchema.rows(params, "output"))
        }

        fun of(script: Script, code: String, own: Set<String>) = ScriptForm(
            script.name, script.description, code, ScriptSchema.rows(script.inputs),
            script.vars.entries.joinToString("\n") { (name, value) -> "$name=$value" },
            script.secrets.filter { it in own }.map { OwnSecretRow(it, was = it) },
            script.secrets.filter { it !in own },
            script.output?.let(ScriptSchema::rows).orEmpty(),
        )

        val PLACEHOLDER = """
            |const text = ants.inputs.text
            |const channel = process.env.CHANNEL
            |const token = process.env.SLACK_TOKEN
            |
            |console.log(`Posting to ${'$'}{channel}: ${'$'}{text}`)
            |
            |ants.output({posted: true})
        """.trimMargin()
    }
}

@Controller
class ScriptsPage(private val scripts: Scripts, private val runner: ScriptRunner, private val settings: Settings, private val usedBy: UsedBy) {
    private val json = JsonMapper.shared()

    @GetMapping("/scripts")
    fun list(model: Model): String {
        model.addAttribute("scripts", scripts.all())
        return "scripts"
    }

    @GetMapping("/scripts/new")
    fun new(@RequestParam use: String?, model: Model) = show(model.addAttribute("use", use != null), ScriptForm("", "", "", emptyList(), "", emptyList(), emptyList()), null)

    @PostMapping("/scripts/new")
    fun create(@RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val form = ScriptForm.of(params)
        return save(form, null, "use" in params, model)
    }

    @GetMapping("/scripts/{name}")
    fun edit(@PathVariable name: String, @RequestParam use: String?, model: Model): String {
        val script = find(name)
        return show(model.addAttribute("use", use != null), ScriptForm.of(script, scripts.code(script), scripts.ownSecrets(script).keys), script)
    }

    @PostMapping("/scripts/{name}")
    fun update(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val script = find(name)
        return save(ScriptForm.of(params), script, "use" in params, model)
    }

    @PostMapping("/scripts/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        val script = find(name)
        if (confirm == null) return confirm(model, "Delete the Script $name?", usedBy.script(script), "/scripts/$name/delete", emptyMap(), "/scripts/$name")
        scripts.delete(script)
        return "redirect:/scripts"
    }

    @PostMapping("/scripts/$TEST_RUN", produces = [APPLICATION_JSON_VALUE])
    @ResponseBody
    fun testRun(@RequestParam params: MultiValueMap<String, String>): Map<String, Any?> {
        val form = ScriptForm.of(params)
        val saved = params["script"]?.firstOrNull()?.let(scripts::named)
        val savedSecrets = saved?.let(scripts::ownSecrets).orEmpty()
        val inputs = try {
            json.readValue(params["sample"]?.firstOrNull()?.ifBlank { null } ?: "{}", Map::class.java)
        } catch (e: JacksonException) {
            return result(ScriptRun(-1, "The sample inputs aren't a JSON object: ${e.originalMessage}\n", null))
        }
        val script = form.script(TEST_RUN, saved)
        val own = ownValues(form, savedSecrets)
        val scratch = Files.createTempDirectory("ants-test-run")
        try {
            val code = scratch.resolve("script.mjs").apply { writeText(form.code) }
            val workspace = scratch.resolve("workspace").createDirectories()
            return result(runner.run(code, workspace, TEST_RUN, inputs.entries.associate { (key, value) -> "$key" to value }, script.inputs, script.vars, scripts.secretEnvironment(script, own), script.output))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun result(run: ScriptRun) = mapOf("exitCode" to run.exitCode, "output" to run.output, "payload" to run.payload, "mismatches" to run.mismatches)

    private fun ownValues(form: ScriptForm, savedSecrets: Map<String, String>) =
        form.own.mapNotNull { row -> row.value.ifEmpty { savedSecrets[row.was.ifEmpty { row.name }] }?.let { row.name to it } }.toMap()

    private fun save(form: ScriptForm, edited: Script?, modal: Boolean, model: Model): String {
        val own = ownValues(form, edited?.let(scripts::ownSecrets).orEmpty())
        val error = form.error(scripts.all(), edited?.name) ?: form.own.firstOrNull { it.name !in own }?.let { "The Secret ${it.name} needs a value." }
        if (error != null) return show(model.addAttribute("use", modal), form, edited, error)
        val script = form.script(edited?.id ?: scripts.newId(), edited)
        scripts.save(script, form.code, own)
        return "redirect:/scripts/${script.name}" + if (modal) "?saved" else ""
    }

    private fun find(name: String) = scripts.named(name) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Script named $name")

    private fun show(model: Model, form: ScriptForm, script: Script?, message: String? = null): String {
        model.addAttribute("form", form)
        model.addAttribute("saved", script?.name)
        model.addAttribute("labels", (settings.secrets().keys + form.linked).distinct())
        model.addAttribute("placeholder", ScriptForm.PLACEHOLDER)
        model.addAttribute("message", message)
        model.addAttribute("dependents", script?.let { usedBy.script(it) })
        return "script"
    }
}

const val TEST_RUN = "test-run"
