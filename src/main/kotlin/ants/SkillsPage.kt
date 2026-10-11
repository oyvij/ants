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
import kotlin.io.path.writeText

/** A Skill's two files as the Skills page shows them; [name] is null on the create form. */
data class SkillForm(val name: String?, val skill: String, val schema: String, val readOnly: Boolean = false, val where: String = "")

/** A Skill on the list: where it comes from, and whether a shipped Skill hides it by holding its name. */
data class SkillRow(val skill: Skill, val where: String, val hidden: Boolean)

/**
 * The Skills page: edits `SKILL.md` and `result.schema.json`, writing only to the user's own Skills folder. The
 * Catalogue reads the files at every launch, so an edit reaches live Runs at their next one.
 */
@Controller
class SkillsPage(private val catalogue: Catalogue, private val usedBy: UsedBy) {
    @GetMapping("/skills")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, message: String?): String {
        val skills = catalogue.skills()
        val shipped = skills.filter(catalogue::shipped).map { it.name }.toSet()
        model.addAttribute("skills", skills.map { skill ->
            val isShipped = catalogue.shipped(skill)
            SkillRow(skill, if (isShipped) "shipped" else skill.folder.toString(), !isShipped && skill.name in shipped)
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

    @GetMapping("/skills/new")
    fun new(model: Model) = show(model, SkillForm(null, "---\nname: \ndescription: \n---\n", ""))

    @PostMapping("/skills/new")
    fun create(@RequestParam skill: String, @RequestParam schema: String, model: Model) = save(SkillForm(null, skill, schema), model)

    @GetMapping("/skills/{name}")
    fun skill(@PathVariable name: String, model: Model): String {
        val skill = find(name)
        val schema = skill.folder.resolve("result.schema.json")
        val readOnly = !skill.file.startsWith(catalogue.yourSkills)
        return show(model, SkillForm(name, skill.file.readText(), if (schema.exists()) schema.readText() else "", readOnly, skill.folder.toString()))
    }

    /** Copies the Skill's folder into the user's own as `<name>-copy`, renamed in its frontmatter, and opens it. */
    @PostMapping("/skills/{name}/copy")
    fun copy(@PathVariable name: String): String {
        val source = find(name)
        val taken = catalogue.skills().map { it.name }.toSet()
        val copy = generateSequence(1, Int::inc).map { if (it == 1) "$name-copy" else "$name-copy-$it" }
            .first { it !in taken && !catalogue.yourSkills.resolve(it).exists() }
        val folder = catalogue.yourSkills.resolve(copy)
        // A Skill folder can hold other Skills' folders; those stay where they are.
        for (file in source.folder.walk().filter { file -> generateSequence(file.parent) { it.parent }.takeWhile { it != source.folder }.none { it.resolve("SKILL.md").exists() } }) {
            folder.resolve(file.relativeTo(source.folder).toString()).also { it.parent.createDirectories() }.let { file.copyTo(it) }
        }
        val skill = folder.resolve("SKILL.md")
        skill.writeText(Regex("(?m)^name:.*$").replaceFirst(skill.readText(), "name: $copy"))
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

    private fun save(posted: SkillForm, model: Model, confirmed: Boolean = false): String {
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
        val error = when {
            !Regex("[a-z0-9][a-z0-9-]{0,63}").matches(name) -> "A Skill's name is up to 64 lowercase letters, digits and hyphens."
            name in setOf("new", "import", "schema") -> "The Skills page keeps the name $name for itself."
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
        folder.createDirectories().resolve("SKILL.md").writeText(form.skill)
        folder.resolve("result.schema.json").run { if (form.schema.isBlank()) deleteIfExists() else writeText(form.schema) }
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
        model.addAttribute("users", form.name?.let(usedBy::skill))
        return "skill"
    }
}
