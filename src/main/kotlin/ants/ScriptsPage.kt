package ants

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
import org.yaml.snakeyaml.Yaml
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

    /** The Script this form saves, each schema keeping what [old], the saved Script's, holds that the rows don't edit. No output rows is no output schema. */
    fun script(id: String, old: Script? = null) = Script(
        id, name, description, ScriptSchema.schema(inputs, old?.inputs.orEmpty()),
        output.takeIf { it.isNotEmpty() }?.let { ScriptSchema.schema(it, old?.output.orEmpty()) },
        varsMap, (own.map { it.name } + linked).distinct(),
    )

    fun error(others: List<Script>, was: String?): String? = nameError("A Script", name)
        ?: "The Scripts page keeps the name $name for itself.".takeIf { name == TEST_RUN }
        ?: "A Script named $name already exists.".takeIf { name != was && others.any { it.name == name } }
        ?: "A Script needs a description.".takeIf { description.isBlank() }
        ?: ScriptSchema.names(output).firstOrNull { !FIELD_NAME.matches(it) }?.let { "A payload field's name is letters, digits, hyphens and underscores, not $it." }
        ?: ScriptSchema.names(inputs).firstOrNull { !FIELD_NAME.matches(it) }?.let { "An input's name is letters, digits, hyphens and underscores, not $it." }
        ?: (varsMap.keys + own.map { it.name }).firstOrNull { !Regex("[A-Z_][A-Z0-9_]*").matches(it) }
            ?.let { "A var or Secret's name is upper case letters, digits and underscores, such as SLACK_TOKEN, not $it." }

    /** The form's fields as its page posts them, which [of] reads back. */
    fun fields(): Map<String, List<String>> {
        val fields = linkedMapOf("name" to listOf(name), "description" to listOf(description), "code" to listOf(code), "vars" to listOf(vars))
        fun rows(prefix: String, rows: List<SchemaRow>) {
            for ((i, row) in rows.withIndex()) {
                val at = "$prefix[$i]"
                fields["$at.name"] = listOf(row.name)
                fields["$at.type"] = listOf(row.type)
                fields["$at.description"] = listOf(row.description)
                if (row.required) fields["$at.required"] = listOf("on")
                rows("$at.rows", row.rows)
            }
        }
        rows("inputs", inputs)
        rows("output", output)
        for ((i, row) in own.withIndex()) {
            fields["secrets[$i].name"] = listOf(row.name)
            fields["secrets[$i].was"] = listOf(row.was)
        }
        if (linked.isNotEmpty()) fields["linked"] = linked
        return fields
    }

    companion object {
        private val FIELD_NAME = Regex("[A-Za-z_][A-Za-z0-9_-]*")

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

/**
 * The Scripts pages. Each page autosaves to its Script's Draft, the page's fields as posted (a Secret's value left
 * out), and Publish makes the Draft the Script in ANTS_HOME's `scripts/`, which is all a Script Task or a Script tool
 * runs. Test runs the page as it is, which is the Draft.
 */
@Controller
class ScriptsPage(
    private val scripts: Scripts, private val runner: ScriptRunner, private val settings: Settings, private val usedBy: UsedBy, private val validator: ScriptCheck,
    private val drafts: Drafts,
) {
    private val json = JsonMapper.shared()

    /** A Script as its page shows it: the [published] Script, its [draft], or both. While it is a Draft only, its page is at the Draft's key. */
    private inner class Item(val published: Script?, val draft: Draft?) {
        val id get() = draft?.id ?: published!!.id
        val state get() = DraftState.of(published != null, draft != null)
        val name get() = published?.name ?: draft!!.key
        val path get() = "/scripts/$name"

        /** The page's fields: the Draft's when there is one. */
        val fields: Map<String, List<String>>
            get() = draft?.let(::drafted) ?: ScriptForm.of(published!!, scripts.code(published), scripts.ownSecrets(published).keys).fields()
    }

    /** The Script whose page is /scripts/[name]: a published one by its name, else a Draft-only one by its key. */
    private fun item(name: String): Item {
        scripts.named(name)?.let { return Item(it, drafts.get(DraftKind.SCRIPT, it.id)) }
        return draftsOnly().firstOrNull { it.key == name }?.let { Item(null, it) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Script named $name")
    }

    /** The Drafts of Scripts never published. */
    private fun draftsOnly(): List<Draft> {
        val published = scripts.all().map { it.id }.toSet()
        return drafts.all(DraftKind.SCRIPT).filter { it.id !in published }
    }

    /** Whether another Script than the one with [id] has [name]: a published one, or a Draft-only one by its name or the page it is at. */
    private fun taken(name: String, id: String?) =
        scripts.all().any { it.name == name && it.id != id } || draftsOnly().any { it.id != id && (it.name == name || it.key == name) }

    /** Whether a Draft-only Script's page may move to [name]: a valid name no other Script has, and no page of the Scripts pages' own. */
    private fun free(name: String, id: String?) = nameError("A Script", name) == null && name !in OWN_PAGES && !taken(name, id)

    @GetMapping("/scripts")
    fun list(model: Model): String {
        val published = scripts.all().map { ScriptRow(it.name, it.description, it.name, DraftState.of(true, drafts.get(DraftKind.SCRIPT, it.id) != null)) }
        val draftOnly = draftsOnly().map { ScriptRow(it.name, ScriptForm.of(drafted(it)).description, it.key, DraftState.DRAFT_ONLY) }
        model.addAttribute("scripts", (published + draftOnly).sortedBy { it.name })
        return "scripts"
    }

    /** The create form, as the modeler's New Script modal shows it, whose Save publishes at once. */
    @GetMapping("/scripts/new")
    fun new(@RequestParam use: String?, model: Model) = show(model.addAttribute("use", use != null), ScriptForm("", "", "", emptyList(), "", emptyList(), emptyList()), null)

    @PostMapping("/scripts/new")
    fun create(@RequestParam params: MultiValueMap<String, String>, model: Model): String {
        return save(params, null, model)
    }

    /** **New Script**: a Draft-only Script at once, with a fixed id and the first free name of `new-script`, `new-script-2`, …, and its page. */
    @PostMapping("/scripts/new/draft")
    fun newDraft() = draftOnly(mapOf("name" to listOf(freeName("new-script") { !free(it, null) })))

    /** **Copy**: a Draft-only copy of the Script as its page shows it, under the first free `<name>-copy`, its own Secrets named but with no value. */
    @PostMapping("/scripts/{name}/copy")
    fun copy(@PathVariable name: String): String {
        val fields = item(name).fields
        val copied = copyName(ScriptForm.of(fields).name.ifEmpty { name }) { !free(it, null) }
        return draftOnly(fields.mapValues { (field, values) -> if (WAS.matches(field)) listOf("") else values } + ("name" to listOf(copied)))
    }

    private fun draftOnly(fields: Map<String, List<String>>): String {
        val name = fields.getValue("name").single()
        drafts.save(DraftKind.SCRIPT, scripts.newId(), name, name, yaml.dump(fields), 0)
        return "redirect:/scripts/$name"
    }

    /** The Script's page: its Draft when it has one, else the published Script. */
    @GetMapping("/scripts/{name}")
    fun edit(@PathVariable name: String, @RequestParam use: String?, model: Model): String {
        val item = item(name)
        return show(model.addAttribute("use", use != null), ScriptForm.of(item.fields), item)
    }

    /**
     * The page's autosave: the page as the Script's Draft, whatever state it is in, unless the Draft changed since the
     * page loaded [version]. A Draft-only Script's page follows its name, while no other Script has it.
     */
    @PostMapping("/scripts/{name}/draft")
    @ResponseBody
    fun autosave(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, @RequestParam version: Long): ResponseEntity<Map<String, Any>> {
        val item = item(name)
        val form = ScriptForm.of(params)
        val key = item.published?.name ?: form.name.takeIf { it == item.draft!!.key || free(it, item.id) } ?: item.draft!!.key
        val save = drafts.save(DraftKind.SCRIPT, item.id, key, form.name, drafting(params), version)
        return Drafts.answer(DraftKind.SCRIPT, save, DraftState.of(item.published != null, true), "/scripts/$key")
    }

    /** **Publish**: keeps the page as the Draft, then saves it as Save did; if that passes, the Draft is the Script and is removed. */
    @PostMapping("/scripts/{name}")
    fun publish(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val item = item(name)
        drafts.save(DraftKind.SCRIPT, item.id, item.draft?.key ?: item.name, ScriptForm.of(params).name, drafting(params), drafts.get(DraftKind.SCRIPT, item.id)?.version ?: 0)
        val view = save(params, item(name), model)
        if (view.startsWith("redirect:")) drafts.remove(DraftKind.SCRIPT, item.id)
        return view
    }

    /** **Discard Draft**: once confirmed, removes the Draft and reopens the published Script. */
    @PostMapping("/scripts/{name}/discard")
    fun discard(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        val item = item(name)
        val draft = item.draft ?: return "redirect:${item.path}"
        if (item.published == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name was never published: Delete it instead.")
        if (confirm == null) return confirm(model, "Discard the Draft of $name?", Dependents(emptyList()), "${item.path}/discard", emptyMap(), item.path)
        drafts.remove(DraftKind.SCRIPT, draft.id)
        return "redirect:${item.path}"
    }

    /** Deletes a Script, once the user has confirmed it, seeing what uses it, and its Draft with it; a Draft-only Script, which nothing can use, is its Draft. */
    @PostMapping("/scripts/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        val item = item(name)
        val script = item.published
        if (confirm == null) {
            return if (script == null) confirm(model, "Delete the Draft $name?", Dependents(emptyList()), "${item.path}/delete", emptyMap(), item.path)
            else confirm(model, "Delete the Script $name?", usedBy.script(script), "${item.path}/delete", emptyMap(), item.path)
        }
        script?.let(scripts::delete)
        drafts.remove(DraftKind.SCRIPT, item.id)
        return "redirect:/scripts"
    }

    /** The validator's findings for the Script page's unsaved form. */
    @PostMapping("/script-check", produces = [APPLICATION_JSON_VALUE])
    @ResponseBody
    fun check(@RequestParam params: MultiValueMap<String, String>): Map<String, Any?> = mapOf("findings" to validator.findings(params))

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
            return result(runner.run(script, scripts.secretEnvironment(script, own), code, workspace, TEST_RUN, inputs))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun result(run: ScriptRun) = mapOf("exitCode" to run.exitCode, "output" to run.output, "payload" to run.payload, "mismatches" to run.mismatches)

    private fun ownValues(form: ScriptForm, savedSecrets: Map<String, String>) =
        form.own.mapNotNull { row -> row.value.ifEmpty { savedSecrets[row.was.ifEmpty { row.name }] }?.let { row.name to it } }.toMap()

    /** Publishes the page's [params] as [item], or as a new Script for none, or shows the page with why not. */
    private fun save(params: MultiValueMap<String, String>, item: Item?, model: Model): String {
        val form = ScriptForm.of(params)
        val modal = "use" in params
        val edited = item?.published
        val own = ownValues(form, edited?.let(scripts::ownSecrets).orEmpty())
        val error = form.error(scripts.all(), edited?.name)
            ?: "A Script named ${form.name} already exists.".takeIf { taken(form.name, item?.id) }
            ?: form.own.firstOrNull { it.name !in own }?.let { "The Secret ${it.name} needs a value." }
        if (error != null) return show(model.addAttribute("use", modal), form, item, error)
        val errors = validator.findings(params).filter { it.error }
        if (errors.isNotEmpty()) {
            model.addAttribute("errors", errors.map { it.sentence })
            return show(model.addAttribute("use", modal), form, item, "The Script has ${if (errors.size == 1) "an error" else "${errors.size} errors"}, so it wasn't published:")
        }
        val script = form.script(item?.id ?: scripts.newId(), edited)
        scripts.save(script, form.code, own)
        return "redirect:/scripts/${script.name}" + if (modal) "?saved" else ""
    }

    /** The page's [params] as a Draft keeps them: the fields alone, and no Secret's value. */
    private fun drafting(params: MultiValueMap<String, String>): String =
        yaml.dump(params.filterKeys { it !in NOT_DRAFTED && !SECRET_VALUE.matches(it) }.mapValues { (_, values) -> values.toList() })

    private fun drafted(draft: Draft): Map<String, List<String>> =
        Yaml().load<Map<*, *>>(draft.content).orEmpty().entries.associate { (field, values) -> "$field" to (values as? List<*>).orEmpty().map { "$it" } }

    private fun show(model: Model, form: ScriptForm, item: Item?, message: String? = null): String {
        model.addAttribute("form", form)
        model.addAttribute("saved", item?.name)
        model.addAttribute("draft", item?.let { DraftHead(it.state, it.draft?.version ?: 0, it.draft?.savedLine, "${it.path}/draft") })
        model.addAttribute("labels", (settings.secrets().keys + form.linked).distinct())
        model.addAttribute("placeholder", ScriptForm.PLACEHOLDER)
        model.addAttribute("message", message)
        model.addAttribute("dependents", item?.published?.let { usedBy.script(it) })
        return "script"
    }

    companion object {
        /** The pages of the Scripts pages' own, which no Script's page may take. */
        private val OWN_PAGES = setOf(TEST_RUN, "new")

        /** What a page posts that isn't the Script: the CSRF token, the Draft's version, the modal's flag, the saved name and a Test run's sample. */
        private val NOT_DRAFTED = setOf("_csrf", "version", "use", "script", "sample")
        private val SECRET_VALUE = Regex("""secrets\[\d+]\.value""")
        private val WAS = Regex("""secrets\[\d+]\.was""")
    }
}

/** A Script on the list, the [page] it is at and its [state]. */
class ScriptRow(val name: String, val description: String, val page: String, val state: DraftState)

const val TEST_RUN = "test-run"
