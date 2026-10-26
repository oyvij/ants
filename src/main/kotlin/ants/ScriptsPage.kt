package ants

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
import org.yaml.snakeyaml.Yaml
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Instant
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
        ?: envError(varsMap.keys + own.map { it.name })

    /** The card's rows as the Script with the [kept] own Secrets has them: a row naming a kept one is set, and a kept one no row names gets a row. */
    fun keeping(kept: Set<String>) = copy(
        own = own.map { OwnSecretRow(it.name, was = it.name.takeIf(kept::contains).orEmpty()) } + kept.filter { name -> own.none { it.name == name } }.map { OwnSecretRow(it, was = it) },
    )

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
        fun envError(names: Collection<String>) = names.firstOrNull { !Regex("[A-Z_][A-Z0-9_]*").matches(it) }
            ?.let { "A var or Secret's name is upper case letters, digits and underscores, such as SLACK_TOKEN, not $it." }

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
    drafted: DraftedItems,
) {
    private val json = JsonMapper.shared()
    private val items = drafted.scripts

    /** The id a Script's Draft is kept under, which is its own. */
    private val ScriptItem.id get() = draft?.id ?: published!!.id

    /** A Script page's fields: the Draft's when there is one. */
    private val ScriptItem.fields: Map<String, List<String>>
        get() = draft?.let(::scriptFields) ?: published!!.let { ScriptForm.of(it, scripts.code(it), scripts.ownSecrets(it.id).keys).fields() }

    /** The Script whose page is /scripts/[name]: a published one by its name, else a Draft-only one by its key. */
    private fun item(name: String): ScriptItem = items.item(name)

    private fun taken(name: String, id: String?) = items.taken(name, id)

    /** Whether a Draft-only Script's page may move to [name]: a valid name no other Script has, and no page of the Scripts pages' own. */
    private fun free(name: String, id: String?) = nameError("A Script", name) == null && name !in OWN_PAGES && !taken(name, id)

    @GetMapping("/scripts")
    fun list(model: Model): String {
        val published = scripts.all().map { ScriptRow(it.name, it.description, it.name, DraftState.of(true, items.draftOf(it) != null)) }
        val draftOnly = items.draftOnlyItems().map { (draft, script) -> ScriptRow(draft.name, script.description, draft.key, DraftState.DRAFT_ONLY) }
        model.addAttribute("scripts", (published + draftOnly).sortedBy { it.name })
        return "scripts"
    }

    /** **New Script**: a Draft-only Script at once, with a fixed id and the first free name of `new-script`, `new-script-2`, …, and its page; with `use`, as the modeler's New Script modal opens it. */
    @PostMapping("/scripts/new/draft")
    fun newDraft(@RequestParam use: String?) = draftOnly(mapOf("name" to listOf(freeName("new-script") { !free(it, null) })), use)

    /** **Copy**: a Draft-only copy of the Script as its page shows it, under the first free `<name>-copy`, its own Secrets named but with no value. */
    @PostMapping("/scripts/{name}/copy")
    fun copy(@PathVariable name: String): String {
        val fields = item(name).fields
        val copied = copyName(ScriptForm.of(fields).name.ifEmpty { name }) { !free(it, null) }
        return draftOnly(fields.mapValues { (field, values) -> if (WAS.matches(field)) listOf("") else values } + ("name" to listOf(copied)))
    }

    private fun draftOnly(fields: Map<String, List<String>>, use: String? = null) = items.create(scripts.newId(), fields.getValue("name").single(), yaml.dump(fields), use)

    /** The Script's page: its Draft when it has one, else the published Script. */
    @GetMapping("/scripts/{name}")
    fun edit(@PathVariable name: String, @RequestParam use: String?, model: Model): String {
        val item = item(name)
        return show(model.addAttribute("use", use != null), ScriptForm.of(item.fields), item)
    }

    /**
     * The Secrets card's Save: the card's rows as the Script's own Secrets, at once, a row left blank keeping the value
     * saved under its old name. It neither publishes the Script nor touches its Draft. The page's script asks for JSON.
     */
    @PostMapping("/scripts/{name}/secrets", produces = [APPLICATION_JSON_VALUE])
    @ResponseBody
    fun saveSecrets(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>): ResponseEntity<Map<String, Any>> {
        val item = item(name)
        val error = keepSecrets(item, ScriptForm.of(params))
            ?: return ResponseEntity.ok(mapOf("secrets" to scripts.ownSecrets(item.id).keys.toList(), "saved" to "Secrets saved ${shortTime(Instant.now())}"))
        return ResponseEntity.badRequest().body(mapOf("error" to error))
    }

    /** The Secrets card's Save without the page's script: the page as it was posted, with the card's Secrets saved, or why not. */
    @PostMapping("/scripts/{name}/secrets")
    fun saveSecretsPage(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val item = item(name)
        val form = ScriptForm.of(params)
        val error = keepSecrets(item, form)
        return show(model.addAttribute("use", "use" in params), if (error == null) form.copy(own = emptyList()) else form, item, error ?: "Secrets saved.")
    }

    /** Saves [form]'s rows as [item]'s own Secrets, or says why not. */
    private fun keepSecrets(item: ScriptItem, form: ScriptForm): String? {
        val own = ownValues(form, scripts.ownSecrets(item.id))
        val error = ScriptForm.envError(form.own.map { it.name }) ?: form.own.firstOrNull { it.name !in own }?.let { "The Secret ${it.name} needs a value." }
        if (error == null) scripts.saveSecrets(item.id, own)
        return error
    }

    /**
     * The page's autosave: the page as the Script's Draft, whatever state it is in, unless the Draft changed since the
     * page loaded [version]. A Draft-only Script's page follows its name, while no other Script has it.
     */
    @PostMapping("/scripts/{name}/draft")
    @ResponseBody
    fun autosave(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, @RequestParam version: Long, @RequestParam tab: String?, @RequestParam(defaultValue = "0") seq: Long): ResponseEntity<Map<String, Any>> {
        val item = item(name)
        return items.autosave(item, item.id, ScriptForm.of(params).name, drafting(params), DraftFrom(version, tab, seq)) { free(it, item.id) }
    }

    /** **Publish**: keeps the page as the Draft, then saves it as Save did; if that passes, the Draft is the Script and is removed. */
    @PostMapping("/scripts/{name}")
    fun publish(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val item = item(name)
        return items.publish(item, item.id, ScriptForm.of(params).name, drafting(params)) { save(params, item(name), model) }.view
    }

    /** **Discard Draft**: once confirmed, removes the Draft and reopens the published Script. */
    @PostMapping("/scripts/{name}/discard")
    fun discard(@PathVariable name: String, @RequestParam confirm: String?, model: Model) = items.discard(item(name), confirm != null, model)

    /** Deletes a Script, once the user has confirmed it, seeing what uses it, and its Draft with it; a Draft-only Script, which nothing can use, is its Draft. */
    @PostMapping("/scripts/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        val item = item(name)
        val script = item.published
        if (confirm == null) {
            return if (script == null) confirm(model, "Delete the Draft $name?", Dependents(emptyList()), "${item.path}/delete", emptyMap(), item.path)
            else confirm(model, "Delete the Script $name?", usedBy.script(script), "${item.path}/delete", emptyMap(), item.path)
        }
        scripts.delete(item.id)
        items.removeDraft(item)
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
        val page = params["script"]?.firstOrNull()?.let { runCatching { item(it) }.getOrNull() }
        val saved = page?.published
        val savedSecrets = page?.let { scripts.ownSecrets(it.id) }.orEmpty()
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

    /**
     * Publishes the page's [params] as [item], with the own Secrets its Secrets card saved, or shows the page with why
     * not.
     */
    private fun save(params: MultiValueMap<String, String>, item: ScriptItem, model: Model): PublishOutcome {
        val form = ScriptForm.of(params)
        val modal = "use" in params
        val edited = item.published
        val kept = scripts.ownSecrets(item.id)
        val error = form.error(scripts.all(), edited?.name)
            ?: "A Script named ${form.name} already exists.".takeIf { taken(form.name, item.id) }
            ?: form.own.firstOrNull { it.name !in kept }?.let { "The Secret ${it.name} needs a value: save it on the Secrets card." }
        if (error != null) return PublishOutcome.Refused(show(model.addAttribute("use", modal), form, item, error))
        val errors = validator.findings(params).filter { it.error }
        if (errors.isNotEmpty()) {
            model.addAttribute("errors", errors.map { it.sentence })
            return PublishOutcome.Refused(show(model.addAttribute("use", modal), form, item, "The Script has ${if (errors.size == 1) "an error" else "${errors.size} errors"}, so it wasn't published:"))
        }
        val script = form.script(item.id, edited).copy(secrets = (kept.keys + form.linked).distinct())
        scripts.save(script, form.code, null)
        return PublishOutcome.Published("/scripts/${script.name}" + if (modal) "?saved" else "")
    }

    /** The page's [params] as a Draft keeps them: the fields alone, and no Secret's value. */
    private fun drafting(params: MultiValueMap<String, String>): String =
        yaml.dump(params.filterKeys { it !in NOT_DRAFTED && !SECRET_VALUE.matches(it) }.mapValues { (_, values) -> values.toList() })

    private fun show(model: Model, form: ScriptForm, item: ScriptItem, message: String? = null): String {
        model.addAttribute("form", form.keeping(scripts.ownSecrets(item.id).keys))
        model.addAttribute("saved", item.name)
        model.addAttribute("draft", item.head)
        model.addAttribute("labels", (settings.secrets().keys + form.linked).distinct())
        model.addAttribute("placeholder", ScriptForm.PLACEHOLDER)
        model.addAttribute("message", message)
        model.addAttribute("dependents", item.published?.let { usedBy.script(it) })
        return "script"
    }

    companion object {
        /** The pages of the Scripts pages' own, which no Script's page may take. */
        private val OWN_PAGES = setOf(TEST_RUN, "new")

        /** What a page posts that isn't the Script: the CSRF token, the Draft's version, the autosave's tab and number, the modal's flag, the saved name and a Test run's sample. */
        private val NOT_DRAFTED = setOf("_csrf", "version", "tab", "seq", "use", "script", "sample")
        private val SECRET_VALUE = Regex("""secrets\[\d+]\.value""")
        private val WAS = Regex("""secrets\[\d+]\.was""")
    }
}

/** A Script as its page shows it: the published Script, its Draft, or both. */
private typealias ScriptItem = DraftedItem<Script>

/** The page's fields a Script's [draft] keeps. */
internal fun scriptFields(draft: Draft): Map<String, List<String>> =
    Yaml().load<Map<*, *>>(draft.content).orEmpty().entries.associate { (field, values) -> "$field" to (values as? List<*>).orEmpty().map { "$it" } }

/** A Script on the list, the [page] it is at and its [state]. */
class ScriptRow(val name: String, val description: String, val page: String, val state: DraftState)

const val TEST_RUN = "test-run"
