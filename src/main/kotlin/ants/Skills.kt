package ants

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

/** A Skill's `SKILL.md` as the Skills page shows it; [name] is null on the create form. */
data class SkillForm(val name: String?, val skill: String, val readOnly: Boolean = false, val where: String = "")

/**
 * A Skill on the list: where it comes from, whether a shipped Skill hides it by holding its name, the Agents that may
 * use it, the [page] it is at and its [state].
 */
data class SkillRow(
    val skill: Skill, val where: String, val hidden: Boolean, val runBy: List<String> = emptyList(),
    val page: String = skill.name, val state: DraftState = DraftState.PUBLISHED,
)

/**
 * The Skills page: edits `SKILL.md` alone (ADR 0024), writing only to the user's own Skills folder. The page autosaves
 * to the Skill's Draft, and Publish makes the Draft the Skill's `SKILL.md`, which is all a launch reads; the Catalogue
 * reads the files at every launch, so a Publish reaches live Runs at their next one.
 */
@Controller
class Skills(private val catalogue: Catalogue, private val usedBy: UsedBy, private val drafts: Drafts) {
    /**
     * A Skill as its page shows it: the [published] Skill, its [draft], or both. A read-only Skill has no Draft. While
     * it is a Draft only, its page is at the Draft's key.
     */
    private inner class Item(val published: Skill?, val draft: Draft?) {
        val own get() = published == null || own(published)
        val state get() = DraftState.of(published != null, draft != null)
        val name get() = published?.name ?: draft!!.key
        val path get() = "/skills/$name"

        /** The `SKILL.md` the page shows: the Draft's when there is one. */
        val shown get() = draft?.content ?: published!!.file.readText()

        /** The folder of the files beside its `SKILL.md`: a Draft-only copy keeps them with its Draft. */
        val beside get() = published?.folder ?: drafts.files(DraftKind.SKILL, draft!!.id)
    }

    private fun own(skill: Skill) = skill.file.startsWith(catalogue.yourSkills)

    /** The Skill whose page is /skills/[name]: a published one by its name, else a Draft-only one by its key. */
    private fun item(name: String): Item {
        val found = runCatching { find(name) }
        found.getOrNull()?.let { skill -> return Item(skill, skill.id?.takeIf { own(skill) }?.let { drafts.get(DraftKind.SKILL, it) }) }
        return draftsOnly().firstOrNull { it.key == name }?.let { Item(null, it) } ?: throw found.exceptionOrNull()!!
    }

    /** The Drafts of Skills never published. */
    private fun draftsOnly(): List<Draft> {
        val published = catalogue.skills(withHidden = true).mapNotNull { it.id }.toSet()
        return drafts.all(DraftKind.SKILL).filter { it.id !in published }
    }

    /** Whether another Skill than the one with [id] has [name]: a published one, or a Draft-only one by its name or the page it is at. */
    private fun taken(name: String, id: String?) = catalogue.skills().any { it.name == name && it.id != id } ||
        draftsOnly().any { it.id != id && (it.name == name || it.key == name) }

    /** Whether the Skill with [id], or a new one, can't take [name]: it is [taken], or a folder of the user's Skills holds it. */
    private fun inUse(name: String, id: String? = null) = taken(name, id) || catalogue.yourSkills.resolve(name).exists()

    @GetMapping("/skills")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, message: String?): String {
        val skills = catalogue.skills()
        val shipped = skills.filter(catalogue::shipped).map { it.name }.toSet()
        val agents = catalogue.agents()
        val drafted = drafts.all(DraftKind.SKILL).map { it.id }.toSet()
        val published = skills.map { skill ->
            val hidden = !catalogue.shipped(skill) && skill.name in shipped
            val runBy = agents.filter { it.allows(skill) }.map { it.name }.sorted()
            SkillRow(skill, catalogue.where(skill.folder), hidden, runBy, state = DraftState.of(true, own(skill) && skill.id in drafted))
        }
        val draftOnly = draftsOnly().map { draft ->
            val description = runCatching { frontmatter(draft.content).first["description"]?.toString() }.getOrNull().orEmpty()
            val skill = Skill(draft.key, description, catalogue.yourSkills.resolve(draft.key).resolve("SKILL.md"), id = draft.id)
            SkillRow(skill, catalogue.where(catalogue.yourSkills), false, page = draft.key, state = DraftState.DRAFT_ONLY)
        }
        model.addAttribute("skills", published + draftOnly.sortedBy { it.page })
        model.addAttribute("message", message)
        return "skills"
    }

    /**
     * Adds a Skill as it comes, from the web or another tool: one `SKILL.md`, or a folder holding one with the files
     * beside it, which are kept. It needs a name and a description, and a name no other Skill has; then its page opens,
     * published.
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
        nameProblem(name, null, folder, null, null)?.let { return list(model, "$main: $it") }
        val beside = paths.filterKeys { it != main && it.startsWith(root) }.mapKeys { folder.resolve(it.key.removePrefix(root)).normalize() }
        if (beside.keys.any { !it.startsWith(folder) }) return list(model, "$main: a file beside it lies outside its folder.")
        for ((path, upload) in beside) path.also { it.parent.createDirectories() }.writeBytes(upload.bytes)
        catalogue.giveSkillId(folder)
        folder.resolve("SKILL.md").writeAtomically(skill) // last, so a launch finds the Skill only once it is whole
        return "redirect:/skills/$name"
    }

    /**
     * The create form; with `use=<agent>`, as the modeler's New Skill modal shows it, for a Task run by that Agent.
     * Its Create and use also adds the Skill to the Agent's Skills, when the Agent is the user's own.
     */
    @GetMapping("/skills/new")
    fun new(@RequestParam use: String?, model: Model) = show(model.addAttribute("use", use), SkillForm(null, "---\nname: \ndescription: \n---\n"), null)

    /** The create form's Create and use, in the modeler's modal: it publishes at once. */
    @PostMapping("/skills/new")
    fun create(@RequestParam skill: String, @RequestParam use: String?, model: Model): String {
        val agent = catalogue.agents().firstOrNull { it.name == use && it.file.parent == catalogue.yourAgents }
        return save(SkillForm(null, skill), model.addAttribute("use", use), null) { name ->
            if (agent != null) agent.file.writeAtomically(AgentForm.of(agent).copy(skills = catalogue.skillIds(agent.skills + name).joinToString(", ")).text())
        }
    }

    /** **New Skill**: a Draft-only Skill at once, with a fixed id and the first free name of `new-skill`, `new-skill-2`, …, and its page. */
    @PostMapping("/skills/new/draft")
    fun newDraft(): String {
        val name = freeName("new-skill") { inUse(it) }
        return draftOnly(name, "---\nname: $name\ndescription: \n---\n", catalogue.unusedId(SKILL_ID))
    }

    private fun draftOnly(name: String, skill: String, id: String): String {
        drafts.save(DraftKind.SKILL, id, name, name, skill, 0)
        return "redirect:/skills/$name"
    }

    /** The Skill's page: its Draft when it has one, else the published Skill. */
    @GetMapping("/skills/{name}")
    fun skill(@PathVariable name: String, @RequestParam use: String?, model: Model): String {
        model.addAttribute("use", use)
        val item = item(name)
        return show(model, SkillForm(item.name, item.shown, !item.own, item.published?.let { catalogue.where(it.folder) }.orEmpty()), item)
    }

    /**
     * The page's autosave: the page's `SKILL.md` as the Skill's Draft, whatever state it is in, unless the Draft changed
     * since the page loaded [version]. A Draft-only Skill's page follows its name, while no other Skill has it.
     */
    @PostMapping("/skills/{name}/draft")
    @ResponseBody
    fun autosave(@PathVariable name: String, @RequestParam("skill") posted: String, @RequestParam version: Long): ResponseEntity<Map<String, Any>> {
        val skill = unixLines(posted)
        val item = editable(item(name))
        val id = idOf(item)
        val named = runCatching { named(skill) }.getOrNull()
        val key = item.published?.name ?: named?.takeIf { it == item.draft!!.key || nameError("A Skill", it) == null && !inUse(it, id) } ?: item.draft!!.key
        val save = drafts.save(DraftKind.SKILL, id, key, named ?: key, skill, version)
        return Drafts.answer(DraftKind.SKILL, save, DraftState.of(item.published != null, true), "/skills/$key")
    }

    /** **Publish**: keeps the page as the Draft, then saves it as Save did; if that passes, the Draft is the Skill's `SKILL.md` and is removed. */
    @PostMapping("/skills/{name}")
    fun publish(@PathVariable name: String, @RequestParam("skill") posted: String, @RequestParam use: String?, model: Model): String {
        val skill = unixLines(posted)
        model.addAttribute("use", use)
        val item = editable(item(name))
        val id = idOf(item)
        val named = runCatching { named(skill) }.getOrNull()
        drafts.save(DraftKind.SKILL, id, item.draft?.key ?: item.name, named ?: item.name, skill, drafts.get(DraftKind.SKILL, id)?.version ?: 0)
        val view = save(SkillForm(item.name, skill), model, item(name), id)
        if (view.startsWith("redirect:")) drafts.remove(DraftKind.SKILL, id)
        return view
    }

    /** [skill] as the page posted it, with `\n` line ends: a form's submit sends a textarea's as `\r\n`, an autosave as `\n`. */
    private fun unixLines(skill: String) = skill.replace("\r\n", "\n")

    /** The id the Skill's Draft is kept under: a hand-written Skill without one gets one first, as the startup migration gives it. */
    private fun idOf(item: Item): String {
        item.draft?.let { return it.id }
        val skill = item.published!!
        return skill.id ?: catalogue.giveSkillId(skill.folder)
    }

    /** **Discard Draft**: once confirmed, removes the Draft and reopens the published Skill. */
    @PostMapping("/skills/{name}/discard")
    fun discard(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        val item = editable(item(name))
        val draft = item.draft ?: return "redirect:${item.path}"
        if (item.published == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name was never published: Delete it instead.")
        if (confirm == null) return confirm(model, "Discard the Draft of $name?", Dependents(emptyList()), "${item.path}/discard", emptyMap(), item.path)
        drafts.remove(DraftKind.SKILL, draft.id)
        return "redirect:${item.path}"
    }

    /** **Copy**: a Draft-only copy of a Skill, with the files beside its `SKILL.md`, under the first free `<name>-copy`, and its page. */
    @PostMapping("/skills/{name}/copy")
    fun copyToDraft(@PathVariable name: String): String {
        val source = item(name)
        val copy = copyName(source.name) { inUse(it) }
        val id = catalogue.unusedId(SKILL_ID)
        copyBeside(source.beside, drafts.files(DraftKind.SKILL, id))
        return draftOnly(copy, renamed(source.shown, copy), id)
    }

    /** The Process Builder's copy (ADR 0019): an editable copy of a Skill, published at once into the user's own Skills, under the first free `<name>-copy`. */
    fun copy(name: String): String {
        val source = find(name)
        val copy = copyName(name) { inUse(it) }
        val folder = catalogue.yourSkills.resolve(copy)
        copyBeside(source.folder, folder)
        catalogue.giveSkillId(folder)
        folder.resolve("SKILL.md").writeAtomically(renamed(source.file.readText(), copy)) // last, so a launch finds the copy only once it is whole
        return "redirect:/skills/$copy"
    }

    private fun renamed(skill: String, name: String) = Regex("(?m)^name:.*$").replaceFirst(skill, "name: $name")

    /** Copies the files beside the `SKILL.md` in [from] into [to]; a Skill folder can hold other Skills' folders, and those stay where they are. */
    private fun copyBeside(from: Path, to: Path) {
        val files = from.takeIf { it.exists() }?.walk().orEmpty()
            .filter { file -> generateSequence(file.parent) { it.parent }.takeWhile { it != from }.none { it.resolve("SKILL.md").exists() } }
        for (file in files) {
            val relative = file.relativeTo(from).toString()
            if (relative != "SKILL.md" && relative != SKILL_ID_FILE) file.copyTo(to.resolve(relative).also { it.parent.createDirectories() })
        }
    }

    /**
     * Deletes one of the user's Skills with its folder and its Draft, once the user has confirmed it, seeing what uses
     * it; a Draft-only Skill, which nothing can use, is its Draft.
     */
    @PostMapping("/skills/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam confirm: String?, model: Model): String {
        val item = editable(item(name))
        item.published?.let(::holdingOthers)?.let { return list(model, it) }
        if (confirm == null) {
            if (item.published == null) return confirm(model, "Delete the Draft $name?", Dependents(emptyList()), "${item.path}/delete", emptyMap(), item.path)
            return confirm(model, "Delete the Skill $name?", usedBy.skill(name), "${item.path}/delete", emptyMap(), item.path)
        }
        delete(name)
        return "redirect:/skills"
    }

    fun delete(name: String): String {
        val item = editable(item(name))
        item.published?.let { skill ->
            holdingOthers(skill)?.let { throw Refused(it) }
            skill.folder.toFile().deleteRecursively()
        }
        (item.draft?.id ?: item.published?.id)?.let { drafts.remove(DraftKind.SKILL, it) }
        return "/skills/$name"
    }

    private fun holdingOthers(skill: Skill): String? = catalogue.skills().filter { it.file != skill.file && it.file.startsWith(skill.folder) }
        .takeIf { it.isNotEmpty() }?.let { inside -> "${skill.folder} holds other Skills (${inside.joinToString { it.name }}); move or delete them first." }

    /**
     * The Process Builder's save (ADR 0019): a `SKILL.md` through the page's own save, as a new Skill or over the user's
     * own Skill [name], renaming it without asking. Gives the saved Skill's page, or throws with the page's error.
     */
    fun write(skill: String, name: String?): String = redirected("message") { save(SkillForm(name, skill), it, name?.let { Item(yours(it), null) }) }

    /**
     * Publishes the posted `SKILL.md` as [item]'s, or as a new Skill for none, under [id] (a new one for none), and
     * opens it, calling [saved] with its name; else shows the page again with why not. A Draft-only Skill's folder is
     * made then, with the files its Draft keeps beside it.
     */
    private fun save(posted: SkillForm, model: Model, item: Item?, id: String? = null, saved: (String) -> Unit = {}): String {
        val modal = model.getAttribute("use")?.let { "?use=$it" }.orEmpty()
        val edited = item?.published
        val form = posted.copy(where = edited?.folder?.toString().orEmpty())
        val name = try {
            named(form.skill)
        } catch (e: IllegalArgumentException) {
            return show(model, form, item, e.message)
        }
        // A renamed Skill's folder follows the name when it was named after the Skill.
        val folder = when {
            edited == null -> catalogue.yourSkills.resolve(name)
            edited.folder.fileName.toString() == edited.name -> edited.folder.resolveSibling(name)
            else -> edited.folder
        }
        nameProblem(name, edited?.name, folder, edited?.folder, id ?: edited?.id)?.let { return show(model, form, item, it) }
        if (edited != null && folder != edited.folder) Files.move(edited.folder, folder)
        if (edited == null) {
            item?.beside?.takeIf { it.exists() }?.toFile()?.copyRecursively(folder.toFile())
            if (id == null) catalogue.giveSkillId(folder) else catalogue.giveSkillId(folder, id)
        }
        folder.resolve("SKILL.md").writeAtomically(form.skill) // last, so a launch finds a new Skill only once it is whole
        saved(name)
        return "redirect:/skills/$name" + if (modal.isEmpty()) "" else "?saved"
    }

    /**
     * Why a Skill can't be saved as [name] into [folder], or null: a name that isn't valid or another Skill has, a
     * Draft-only one too, or a folder taken. [was] is the Skill's name before, null for a new one, [current] its folder
     * and [id] its id.
     */
    private fun nameProblem(name: String, was: String?, folder: Path, current: Path?, id: String?): String? = nameError("A Skill", name)
        ?: catalogue.skills().firstOrNull { name != was && it.name == name }?.let { "A Skill named $name already exists, in ${it.folder}." }
        ?: "A Skill named $name already exists, as a Draft.".takeIf { draftsOnly().any { it.id != id && (it.name == name || it.key == name) } }
        ?: "$folder already exists.".takeIf { folder != current && folder.exists() }

    private fun find(name: String) = runCatching { catalogue.skill(name) }
        .getOrElse { throw ResponseStatusException(HttpStatus.NOT_FOUND, it.message) }
        .also { if (catalogue.hides(it.file)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Skill named $name") }

    /** The Skill [name], if it's the user's own; shipped and outside Skills are read-only. */
    private fun yours(name: String) = find(name).takeIf(::own) ?: throw readOnly(name)

    private fun editable(item: Item) = item.takeIf { it.own } ?: throw readOnly(item.name)

    private fun readOnly(name: String) = ResponseStatusException(HttpStatus.FORBIDDEN, "The Skill $name is read-only; Copy it to make your own.")

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

    /** The page of [item], or the create form for none. */
    private fun show(model: Model, form: SkillForm, item: Item?, message: String? = null): String {
        model.addAttribute("form", form)
        model.addAttribute("message", message)
        model.addAttribute("draft", item?.takeIf { it.own }?.let { DraftHead(it.state, it.draft?.version ?: 0, it.draft?.savedLine, "${it.path}/draft") })
        model.addAttribute("dependents", item?.published?.let { usedBy.skill(it.name) })
        model.addAttribute("runBy", item?.published?.let { skill -> catalogue.agents().filter { agent -> agent.allows(skill) }.sortedBy { it.name } }.orEmpty())
        return "skill"
    }
}
