package ants

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
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
import kotlin.io.path.writeBytes

/** A Skill's `SKILL.md` as the Skills page shows it; [name] is null on the create form. */
data class SkillForm(val name: String?, val skill: String, val readOnly: Boolean = false, val where: String = "")

/** A Skill on the list: where it comes from, whether a shipped Skill hides it by holding its name, and the Agents that may use it. */
data class SkillRow(val skill: Skill, val where: String, val hidden: Boolean, val runBy: List<String> = emptyList())

/**
 * The Skills page: edits `SKILL.md` alone (ADR 0024), writing only to the user's own Skills folder. The Catalogue
 * reads the files at every launch, so an edit reaches live Runs at their next one.
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
            val runBy = agents.filter { it.allows(skill.name) }.map { it.name }.sorted()
            SkillRow(skill, catalogue.where(skill.folder), hidden, runBy)
        })
        model.addAttribute("message", message)
        return "skills"
    }

    /**
     * Adds a Skill as it comes, from the web or another tool: one `SKILL.md`, or a folder holding one with the files
     * beside it, which are kept. It needs a name and a description, and a name no other Skill has; then its page opens.
     */
    @PostMapping("/skills/import")
    fun import(@RequestParam file: List<MultipartFile>, model: Model): String {
        val paths = file.associateBy { it.originalFilename.orEmpty().replace('\\', '/').trimStart('/') }
        val main = paths.keys.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }.minByOrNull { it.count { c -> c == '/' } }
            ?: return list(model, "${paths.keys.firstOrNull() ?: "The upload"}: a Skill is a SKILL.md, or a folder holding one.")
        val root = main.removeSuffix("SKILL.md")
        val skill = paths.getValue(main).bytes.decodeToString()
        val name = try {
            named(skill)
        } catch (e: IllegalArgumentException) {
            return list(model, "$main: ${e.message}")
        }
        val folder = catalogue.yourSkills.resolve(name)
        val error = nameError("A Skill", name) ?: catalogue.skills().firstOrNull { it.name == name }?.let { "A Skill named $name already exists, in ${it.folder}." }
            ?: "$folder already exists.".takeIf { folder.exists() }
        if (error != null) return list(model, "$main: $error")
        val beside = paths.filterKeys { it != main && it.startsWith(root) }.mapKeys { folder.resolve(it.key.removePrefix(root)).normalize() }
        if (beside.keys.any { !it.startsWith(folder) }) return list(model, "$main: a file beside it lies outside its folder.")
        for ((path, upload) in beside) path.also { it.parent.createDirectories() }.writeBytes(upload.bytes)
        folder.resolve("SKILL.md").writeAtomically(skill) // last, so a launch finds the Skill only once it is whole
        return "redirect:/skills/$name"
    }

    /**
     * The create form; with `use=<agent>`, as the modeler's New Skill modal shows it, for a Task run by that Agent.
     * Its Create and use also adds the Skill to the Agent's Skills, when the Agent is the user's own.
     */
    @GetMapping("/skills/new")
    fun new(@RequestParam use: String?, model: Model) = show(model.addAttribute("use", use), SkillForm(null, "---\nname: \ndescription: \n---\n"))

    @PostMapping("/skills/new")
    fun create(@RequestParam skill: String, @RequestParam use: String?, model: Model): String {
        val agent = catalogue.agents().firstOrNull { it.name == use && it.file.parent == catalogue.yourAgents }
        return save(SkillForm(null, skill), model.addAttribute("use", use)) { name ->
            if (agent != null) agent.file.writeAtomically(AgentForm.of(agent).copy(skills = (agent.skills + name).distinct().joinToString(", ")).text())
        }
    }

    @GetMapping("/skills/{name}")
    fun skill(@PathVariable name: String, @RequestParam use: String?, model: Model): String {
        model.addAttribute("use", use)
        val skill = find(name)
        return show(model, SkillForm(name, skill.file.readText(), !skill.file.startsWith(catalogue.yourSkills), catalogue.where(skill.folder)))
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
    fun edit(@PathVariable name: String, @RequestParam skill: String, @RequestParam confirm: String?, @RequestParam use: String?, model: Model) =
        save(SkillForm(name, skill), model.addAttribute("use", use), confirm != null)

    /** Deletes one of the user's Skills with its folder, once the user has confirmed it, seeing what uses it. */
    @PostMapping("/skills/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        holdingOthers(yours(name))?.let { return list(model, it) }
        if (confirm == null) return confirm(model, "Delete the Skill $name?", usedBy.skill(name), "/skills/$name/delete", emptyMap(), "/skills/$name")
        delete(name)
        return "redirect:/skills"
    }

    fun delete(name: String): String {
        val skill = yours(name)
        holdingOthers(skill)?.let { throw Refused(it) }
        skill.folder.toFile().deleteRecursively()
        return "/skills/$name"
    }

    private fun holdingOthers(skill: Skill): String? = catalogue.skills().filter { it.file != skill.file && it.file.startsWith(skill.folder) }
        .takeIf { it.isNotEmpty() }?.let { inside -> "${skill.folder} holds other Skills (${inside.joinToString { it.name }}); move or delete them first." }

    /**
     * The Process Builder's save (ADR 0019): a `SKILL.md` through the page's own save, as a new Skill or over the user's
     * own Skill [name], renaming it without asking. Gives the saved Skill's page, or throws with the page's error.
     */
    fun write(skill: String, schema: String, name: String?): String {
        // ponytail: until #230 drops `schema` from create_skill and change_skill, it is still written, and nothing reads it.
        ResultContract.schemaErrors(schema).takeIf { it.isNotEmpty() }?.let { throw Refused("result.schema.json is invalid: ${it.joinToString("; ")}") }
        val page = redirected("message") { save(SkillForm(name, skill), it, confirmed = true) }
        catalogue.skill(page.substringAfterLast('/').substringBefore('?')).folder.resolve("result.schema.json").run { if (schema.isBlank()) deleteIfExists() else writeAtomically(schema) }
        return page
    }

    /** Saves the Skill and opens it, calling [saved] with its name, else shows the form again with why not. */
    private fun save(posted: SkillForm, model: Model, confirmed: Boolean = false, saved: (String) -> Unit = {}): String {
        val modal = model.getAttribute("use")?.let { "?use=$it" }.orEmpty()
        val edited = posted.name?.let(::yours)
        val form = posted.copy(where = edited?.folder?.toString().orEmpty())
        val name = try {
            named(form.skill)
        } catch (e: IllegalArgumentException) {
            return show(model, form, e.message)
        }
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
            else -> null
        }
        if (error != null) return show(model, form, error)
        if (edited != null && name != edited.name) {
            if (!confirmed) {
                val params = mapOf("skill" to form.skill) + listOfNotNull(model.getAttribute("use")?.let { "use" to it.toString() })
                return confirm(model, "Rename the Skill ${edited.name} to $name?", usedBy.skill(edited.name), "/skills/${edited.name}", params, "/skills/${edited.name}$modal")
            }
            if (folder != edited.folder) Files.move(edited.folder, folder)
        }
        folder.resolve("SKILL.md").writeAtomically(form.skill)
        saved(name)
        return "redirect:/skills/$name" + if (modal.isEmpty()) "" else "?saved"
    }

    private fun find(name: String) = runCatching { catalogue.skill(name) }
        .getOrElse { throw ResponseStatusException(HttpStatus.NOT_FOUND, it.message) }
        .also { if (catalogue.hides(it.file)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Skill named $name") }

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
        model.addAttribute("runBy", skill?.let { catalogue.agents().filter { agent -> agent.allows(skill.name) }.sortedBy { it.name } }.orEmpty())
        return "skill"
    }
}
