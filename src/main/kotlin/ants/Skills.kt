package ants

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.yaml.snakeyaml.error.YAMLException
import java.nio.file.Files
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** A Skill's two files as the Skills page shows them; [name] is null on the create form. */
data class SkillForm(val name: String?, val skill: String, val schema: String, val readOnly: Boolean = false, val where: String = "")

/**
 * A Skill on the list: where it comes from, whether a shipped Skill hides it by holding its name, the Agents that run
 * it, and whether it reads the Vault.
 */
data class SkillRow(val skill: Skill, val where: String, val hidden: Boolean, val runBy: List<String> = emptyList(), val vault: Boolean = false)

/**
 * The Skills page: edits `SKILL.md` and `result.schema.json`, writing only to the user's own Skills folder. The
 * Catalogue reads the files at every launch, so an edit reaches live Runs at their next one.
 */
@Controller
class Skills(private val catalogue: Catalogue, private val usedBy: UsedBy) {
    @GetMapping("/skills")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, message: String?): String {
        val skills = catalogue.skills()
        val shipped = skills.filter(catalogue::shipped).map { it.name }.toSet()
        val agents = catalogue.agents()
        model.addAttribute("skills", skills.map { skill ->
            val hidden = !catalogue.shipped(skill) && skill.name in shipped
            val runBy = agents.filter { skill.name in it.skills }.map { it.name }.sorted()
            SkillRow(skill, catalogue.where(skill.folder), hidden, runBy, runCatching { catalogue.vault(skill) }.getOrDefault(skill.vault))
        })
        model.addAttribute("message", message)
        return "skills"
    }

    /** Fills the create form with one uploaded `SKILL.md`, refusing one without a name or a description. */
    @PostMapping("/skills/import")
    fun import(@RequestParam file: MultipartFile, model: Model): String {
        val skill = file.bytes.decodeToString()
        return try {
            named(skill)
            show(model, SkillForm(null, skill, ""))
        } catch (e: IllegalArgumentException) {
            list(model, "${file.originalFilename}: ${e.message}")
        }
    }

    /**
     * The create form; with `use=<agent>`, as the modeler's New Skill modal shows it, for a Task run by that Agent.
     * Its Create and use also adds the Skill to the Agent's Skills, when the Agent is the user's own.
     */
    @GetMapping("/skills/new")
    fun new(@RequestParam use: String?, model: Model) = show(model.addAttribute("use", use), SkillForm(null, "---\nname: \ndescription: \n---\n", ""))

    @PostMapping("/skills/new")
    fun create(@RequestParam skill: String, @RequestParam schema: String, @RequestParam use: String?, model: Model): String {
        val agent = catalogue.agents().firstOrNull { it.name == use && it.file.parent == catalogue.yourAgents }
        return save(SkillForm(null, skill, schema), model.addAttribute("use", use)) { name ->
            if (agent != null) agent.file.writeAtomically(AgentForm.of(agent).copy(skills = (agent.skills + name).distinct().joinToString(", ")).text())
        }
    }

    @GetMapping("/skills/{name}")
    fun skill(@PathVariable name: String, model: Model): String {
        val skill = find(name)
        val schema = skill.folder.resolve("result.schema.json")
        val readOnly = !skill.file.startsWith(catalogue.yourSkills)
        return show(model, SkillForm(name, skill.file.readText(), if (schema.exists()) schema.readText() else "", readOnly, catalogue.where(skill.folder)))
    }

    /** Copies the Skill's folder into the user's own as `<name>-copy`, renamed in its frontmatter, and opens it. */
    @PostMapping("/skills/{name}/copy")
    fun copy(@PathVariable name: String): String {
        val source = find(name)
        val taken = catalogue.skills().map { it.name }.toSet()
        val copy = copyName(name) { it in taken || catalogue.yourSkills.resolve(it).exists() }
        val folder = catalogue.yourSkills.resolve(copy)
        // A Skill folder can hold other Skills' folders; those stay where they are. SKILL.md goes last, so a launch
        // finds the copy only once it is whole.
        for (file in source.folder.walk().filter { file -> generateSequence(file.parent) { it.parent }.takeWhile { it != source.folder }.none { it.resolve("SKILL.md").exists() } }) {
            if (file != source.file) folder.resolve(file.relativeTo(source.folder).toString()).also { it.parent.createDirectories() }.let { file.copyTo(it) }
        }
        folder.resolve("SKILL.md").writeAtomically(Regex("(?m)^name:.*$").replaceFirst(source.file.readText(), "name: $copy"))
        return "redirect:/skills/$copy"
    }

    @PostMapping("/skills/{name}")
    fun edit(@PathVariable name: String, @RequestParam skill: String, @RequestParam schema: String, @RequestParam confirm: String?, model: Model) =
        save(SkillForm(name, skill, schema), model, confirm != null)

    /** Deletes one of the user's Skills with its folder, once the user has confirmed it, seeing what uses it. */
    @PostMapping("/skills/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        val skill = yours(name)
        val inside = catalogue.skills().filter { it.file != skill.file && it.file.startsWith(skill.folder) }
        if (inside.isNotEmpty()) return list(model, "${skill.folder} holds other Skills (${inside.joinToString { it.name }}); move or delete them first.")
        if (confirm == null) return confirm(model, "Delete the Skill $name?", usedBy.skill(name), "/skills/$name/delete", emptyMap(), "/skills/$name")
        skill.folder.toFile().deleteRecursively()
        return "redirect:/skills"
    }

    /** The schema editor's check as you type: why the schema is invalid, as a JSON list; empty when it's valid. */
    @PostMapping("/skills/schema")
    @ResponseBody
    fun check(@RequestParam schema: String) = ResultContract.schemaErrors(schema)

    /**
     * The Process Builder's save (ADR 0019): a `SKILL.md` and its result schema through the page's own save, as a new
     * Skill or over the user's own Skill [name], renaming it without asking. Gives the saved Skill's page, or throws with the page's error.
     */
    fun write(skill: String, schema: String, name: String?): String = redirected("message") { save(SkillForm(name, skill, schema), it, confirmed = true) }

    /** Saves the Skill and opens it, calling [saved] with its name, else shows the form again with why not. */
    private fun save(posted: SkillForm, model: Model, confirmed: Boolean = false, saved: (String) -> Unit = {}): String {
        val edited = posted.name?.let(::yours)
        val form = posted.copy(where = edited?.folder?.toString().orEmpty())
        val name = try {
            named(form.skill)
        } catch (e: IllegalArgumentException) {
            return show(model, form, e.message)
        }
        val schemaErrors = ResultContract.schemaErrors(form.schema)
        val taken = catalogue.skills().firstOrNull { it.name == name }
        // A renamed Skill's folder follows the name when it was named after the Skill.
        val folder = when {
            edited == null -> catalogue.yourSkills.resolve(name)
            edited.folder.fileName.toString() == edited.name -> edited.folder.resolveSibling(name)
            else -> edited.folder
        }
        val error = nameError("A Skill", name) ?: when {
            name != form.name && taken != null -> "A Skill named $name already exists, in ${taken.folder}."
            folder != edited?.folder && folder.exists() -> "$folder already exists."
            schemaErrors.isNotEmpty() -> "result.schema.json is invalid: ${schemaErrors.joinToString("; ")}"
            else -> null
        }
        if (error != null) return show(model, form, error)
        if (edited != null && name != edited.name) {
            if (!confirmed) {
                val params = mapOf("skill" to form.skill, "schema" to form.schema)
                return confirm(model, "Rename the Skill ${edited.name} to $name?", usedBy.skill(edited.name), "/skills/${edited.name}", params, "/skills/${edited.name}")
            }
            if (folder != edited.folder) Files.move(edited.folder, folder)
        }
        folder.resolve("result.schema.json").run { if (form.schema.isBlank()) deleteIfExists() else writeAtomically(form.schema) }
        folder.resolve("SKILL.md").writeAtomically(form.skill)
        saved(name)
        return "redirect:/skills/$name"
    }

    private fun find(name: String) = runCatching { catalogue.skill(name) }
        .getOrElse { throw ResponseStatusException(HttpStatus.NOT_FOUND, it.message) }

    /** The Skill [name], if it's the user's own; shipped and outside Skills are read-only. */
    private fun yours(name: String) = find(name).takeIf { it.file.startsWith(catalogue.yourSkills) }
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "The Skill $name is read-only; Copy it to make your own.")

    /** The name in a `SKILL.md`'s frontmatter, refusing broken YAML and a Skill without a name or a description. */
    private fun named(skill: String): String {
        val meta = try {
            frontmatter(skill).first
        } catch (e: YAMLException) {
            throw IllegalArgumentException("The frontmatter isn't valid YAML: ${e.message}")
        }
        val (name, description) = listOf("name", "description").map { meta[it]?.toString()?.trim().orEmpty() }
        require(name.isNotEmpty() && description.isNotEmpty()) { "A Skill needs a name and a description in its frontmatter." }
        return name
    }

    private fun show(model: Model, form: SkillForm, message: String? = null): String {
        model.addAttribute("form", form)
        model.addAttribute("message", message)
        model.addAttribute("dependents", form.name?.let(usedBy::skill))
        val skill = form.name?.let { runCatching { catalogue.skill(it) }.getOrNull() }
        model.addAttribute("runBy", skill?.let { catalogue.agents().filter { agent -> skill.name in agent.skills }.sortedBy { it.name } }.orEmpty())
        model.addAttribute("fields", skill?.let { runCatching { (it.outputSchema["properties"] as? Map<*, *>)?.keys?.map(Any?::toString) }.getOrNull() }.orEmpty())
        return "skill"
    }
}
