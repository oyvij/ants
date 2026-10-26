package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.yaml.snakeyaml.error.YAMLException
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

/** An Agent as its page shows it: the published agent file, its Draft, or both. */
private typealias AgentItem = DraftedItem<Agent>

/**
 * The Agents page: a form over one agent file. Only Agents in ANTS_HOME's `agents/` are edited; shipped Agents and
 * those from extra folders are read-only, with Copy; a shipped one has Your Skills, a Draft and Publish of their own. The page autosaves to the Agent's Draft, and Publish makes the
 * Draft the agent file, which is all a launch reads; nothing is cached, so a Publish reaches the next launch.
 */
@Controller
class Agents(
    private val catalogue: Catalogue, private val usedBy: UsedBy, private val models: Models, private val settings: Settings, private val scripts: Scripts,
    private val drafted: DraftedItems, private val drafts: Drafts, @Value("\${ants.home}") home: Path,
) {
    private val folder = home.resolve("agents")
    private val items = drafted.agents

    /** The agent file an Agent's page shows: the Draft's when there is one. */
    private val AgentItem.shown get() = draft?.let { Agent(folder.resolve("$name.md"), it.content) } ?: published!!

    /** The Agent whose page is /agents/[name]: a published one by its name, else a Draft-only one by its key. */
    private fun item(name: String): AgentItem = items.item(name)

    private fun taken(name: String, id: String?) = items.taken(name, id)

    @GetMapping("/agents")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, error: String?): String {
        val published = catalogue.agents().map { agent ->
            AgentRow(agent, catalogue.where(agent.file.parent), catalogue.skillNames(agent.skills), agent.name, DraftState.of(true, items.draftOf(agent) != null))
        }
        val draftOnly = items.draftOnlyItems().map { (draft, agent) ->
            AgentRow(agent, catalogue.where(folder), catalogue.skillNames(agent.skills), draft.key, DraftState.DRAFT_ONLY)
        }
        model.addAttribute("agents", (published + draftOnly).sortedBy { it.agent.name })
        model.addAttribute("error", error)
        return "agents"
    }

    /**
     * **New Agent**: a Draft-only Agent at once, with a fixed id and the first free name of `new-agent`, `new-agent-2`,
     * …, and its page; with `use`, as the modeler's New Agent modal opens it, with [skills], the Task's, filled in.
     */
    @PostMapping("/agents/new/draft")
    fun newDraft(@RequestParam use: String?, @RequestParam skills: String?) =
        draftOnly(AgentForm.of(Agent(folder.resolve("new.md"), ""), name = freeName("new-agent") { taken(it, null) }).copy(skills = skills.orEmpty()), use)

    private fun draftOnly(form: AgentForm, use: String? = null): String {
        val id = catalogue.unusedId(AGENT_ID)
        return items.create(id, form.name, published(form, id).text(), use)
    }

    /**
     * The modeler's New Skill modal's Use: lists [skill] in the Draft of the user's own Agent [name], making the Draft
     * from the published Agent when it has none, so the published Agent is as it was until Publish; on a shipped
     * Agent, in the Draft of its Your Skills, made from the published ones.
     */
    @PostMapping("/agents/{name}/draft/skills")
    @ResponseBody
    fun listInDraft(@PathVariable name: String, @RequestParam skill: String): ResponseEntity<Unit> {
        item(name).published?.takeIf { catalogue.shipped(it) && it.id != null }?.let { agent ->
            val draft = drafts.get(DraftKind.YOUR_SKILLS, agent.id!!)
            val listed = (draft?.let { words(it.content) } ?: catalogue.yourSkills(agent)) + skill
            drafts.save(DraftKind.YOUR_SKILLS, DraftPage(agent.id, agent.name, catalogue.skillIds(listed).joinToString(", ")), DraftFrom(draft?.version ?: 0))
            return ResponseEntity.noContent().build()
        }
        val item = editable(item(name))
        val form = AgentForm.of(item.shown)
        val id = idOf(item)
        val listed = form.copy(skills = (words(form.skills) + skill).distinct().joinToString(", "))
        items.edit(item, id, form.name, published(listed, id).text())
        return ResponseEntity.noContent().build()
    }

    /** Publishes one agent file at once, dropping the keys Ants doesn't use and saying so, or refuses it with a reason. */
    @PostMapping("/agents/import")
    fun import(@RequestParam file: MultipartFile, model: Model, request: HttpServletRequest): String {
        val filename = file.originalFilename?.substringAfterLast('/')?.ifEmpty { null } ?: "agent.md"
        val agent = try {
            Agent(Path.of(filename), file.bytes.decodeToString())
        } catch (e: YAMLException) {
            return list(model, "$filename has broken YAML: ${e.message}")
        }
        if (agent.cli !in AgentForm.CLIS) return list(model, "$filename runs cli ${agent.cli}, and Ants runs only ${AgentForm.CLIS.joinToString(" or ")}.")
        Agent.sandboxError(agent.cli, agent.sandbox)?.let { return list(model, "$filename: $it") }
        val form = AgentForm.of(agent)
        val published = save(model, form.copy(kept = emptyMap<String, Any?>(), id = null), null)
        if (published !is PublishOutcome.Published) return list(model, "$filename: ${model.getAttribute("error")}")
        form.kept.keys.takeIf { it.isNotEmpty() }?.let { Shell.toast(request, Toast("Imported ${form.name}", "Dropped the keys Ants doesn't use: ${it.joinToString(", ")}.", Toast.Kind.INFO)) }
        return published.view
    }

    /**
     * The Agents and Skills as the modeler's Agent Task panel picks them, read afresh each time so a new one shows up:
     * each Agent's description, Skills (every one by name for `skills: "*"`, and a shipped one's Your Skills) and folder, whether it is the user's own (Edit opens only those) and whether it is shipped (New Skill adds itself to those two kinds, to a shipped one's Your Skills), and each Skill's description, whether it is the user's own (Edit opens only those) and its folder;
     * each Script's input names, and the type, description and required mark of each as `about`;
     * each one's `draft` marker, null for one published with no Draft: a published one is as published, and a Draft-only
     * one, listed after them under the name its page is at, as its Draft;
     * and the Profiles a Process can name.
     */
    @GetMapping("/catalogue.json")
    @ResponseBody
    fun catalogue(): Map<String, Any> {
        fun marker(published: Boolean, draft: Boolean) = DraftState.of(published, draft).takeIf { it != DraftState.PUBLISHED }?.let { mapOf("css" to it.css, "label" to it.label) }
        fun agent(agent: Agent, name: String, draft: Map<String, String>?) =
            mapOf("id" to agent.id, "name" to name, "description" to agent.description, "skills" to catalogue.allowedNames(agent), "own" to (agent.file.parent == folder), "shipped" to catalogue.shipped(agent),
                "folder" to agent.file.parent.toString(), "draft" to draft)
        fun skill(skill: Skill, name: String, own: Boolean, draft: Map<String, String>?) =
            mapOf("id" to skill.id, "name" to name, "description" to skill.description, "own" to own, "folder" to skill.folder.parent.toString(), "draft" to draft)
        fun script(script: Script, name: String, draft: Map<String, String>?): Map<String, Any?> {
            val about = ScriptSchema.rows(script.inputs).associate { it.name to mapOf("type" to ScriptSchema.TYPES[it.type], "description" to it.description, "required" to it.required) }
            return mapOf("id" to script.id, "name" to name, "description" to script.description, "inputs" to script.inputNames, "about" to about, "draft" to draft)
        }
        return mapOf(
            "agents" to catalogue.agents().map { agent(it, it.name, marker(true, items.draftOf(it) != null)) } +
                items.draftsOnly().mapNotNull { draft -> runCatching { agent(items.asItem(draft), draft.key, marker(false, true)) }.getOrNull() },
            "skills" to catalogue.skills().map {
                val own = it.file.startsWith(catalogue.yourSkills)
                skill(it, it.name, own, marker(true, drafted.skills.draftOf(it) != null))
            } + drafted.skills.draftOnlyItems().map { (draft, item) -> skill(item, draft.key, true, marker(false, true)) },
            "scripts" to scripts.all().map { script(it, it.name, marker(true, drafted.scripts.draftOf(it) != null)) } +
                drafted.scripts.draftOnlyItems().map { (draft, item) -> script(item, draft.key, marker(false, true)) },
            "profiles" to settings.profiles().keys,
            "nameBeside" to LINKED_NAME,
        )
    }

    /** The Agent's page: its Draft when it has one, else the published Agent. */
    @GetMapping("/agents/{name}")
    fun agent(@PathVariable name: String, @RequestParam use: String?, model: Model) = item(name).let { show(model.addAttribute("use", use != null), AgentForm.of(it.shown), it) }

    /**
     * The page's autosave: the page as the Agent's Draft, whatever state it is in, unless the Draft changed since the
     * page loaded [version]. A Draft-only Agent's page follows its name, while no other Agent has it.
     */
    @PostMapping("/agents/{name}/draft")
    @ResponseBody
    fun autosave(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, @RequestParam version: Long, @RequestParam tab: String?, @RequestParam(defaultValue = "0") seq: Long): ResponseEntity<Map<String, Any>> {
        val item = editable(item(name))
        val form = pageForm(item, joined(params))
        val id = idOf(item)
        return items.autosave(item, id, form.name, published(form, id).text(), DraftFrom(version, tab, seq)) { nameError("An Agent", it) == null && !taken(it, id) }
    }

    /** **Publish**: keeps the page as the Draft, then saves it as Save did; if that passes, the Draft is the agent file and is removed. */
    @PostMapping("/agents/{name}")
    fun publish(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val item = editable(item(name))
        val fields = joined(params)
        val form = pageForm(item, fields)
        val id = idOf(item)
        return items.publish(item, id, form.name, published(form, id).text()) { save(model.addAttribute("use", "use" in fields), form, item(name), id) }.view
    }

    /** The page's fields as the agent file's form, keeping the frontmatter keys the form doesn't own from what the page showed. */
    private fun pageForm(item: AgentItem, fields: Map<String, String>) = AgentForm.of(fields).copy(kept = AgentForm.of(item.shown).kept)

    /** The form as its agent file holds it: with [id], and the Skills and Scripts it lists by their ids. */
    private fun published(form: AgentForm, id: String) =
        form.copy(id = id, skills = catalogue.skillIds(words(form.skills)).joinToString(", "), scripts = scripts.ids(form.scriptList).joinToString(", "))

    /** The id the Agent's Draft is kept under: a hand-written agent file without one gets one first, as the startup migration gives it. */
    private fun idOf(item: AgentItem): String {
        item.draft?.let { return it.id }
        val agent = item.published!!
        agent.id?.let { return it }
        return catalogue.unusedId(AGENT_ID).also { agent.file.writeAtomically(withId(agent.file.readText(), it)) }
    }

    /** **Discard Draft**: once confirmed, removes the Draft and reopens the published Agent. */
    @PostMapping("/agents/{name}/discard")
    fun discard(@PathVariable name: String, @RequestParam params: Map<String, String>, model: Model) = items.discard(editable(item(name)), "confirm" in params, model)

    /** The shipped Agent whose page is /agents/[name], the only kind with Your Skills. */
    private fun shippedAgent(name: String): Agent = item(name).published?.takeIf(catalogue::shipped)?.takeIf { it.id != null }
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only a shipped Agent has Your Skills.")

    /** Your Skills as the page shows them: its Draft's when it has one, else the published ones; and its Draft's header. */
    private fun yourSkills(agent: Agent): YourSkillsField {
        val draft = drafts.get(DraftKind.YOUR_SKILLS, agent.id!!)
        val listed = draft?.let { words(it.content) } ?: catalogue.yourSkills(agent)
        val head = DraftHead(DraftState.of(true, draft != null), draft?.version ?: 0, draft?.savedLine, "/agents/${agent.name}/your-skills/draft")
        return YourSkillsField(catalogue.skillNames(listed), head)
    }

    /** Your Skills as the page posts them, each Skill by its id where one has it, as their Draft keeps them. */
    private fun yourSkillsDraft(agent: Agent, params: MultiValueMap<String, String>) =
        DraftPage(agent.id!!, agent.name, catalogue.skillIds(words(joined(params)["skills"])).joinToString(", "))

    /** Your Skills' autosave: the page's Skills as their Draft, unless the Draft changed since the page loaded [version]. Only Skills: any other field is ignored. */
    @PostMapping("/agents/{name}/your-skills/draft")
    @ResponseBody
    fun autosaveYourSkills(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, @RequestParam version: Long, @RequestParam tab: String?, @RequestParam(defaultValue = "0") seq: Long): ResponseEntity<Map<String, Any>> {
        val agent = shippedAgent(name)
        val save = drafts.save(DraftKind.YOUR_SKILLS, yourSkillsDraft(agent, params), DraftFrom(version, tab, seq))
        return Drafts.answer(DraftKind.YOUR_SKILLS, save, DraftState.CHANGED, "/agents/${agent.name}/your-skills")
    }

    /**
     * **Publish** of Your Skills: keeps them as their Draft, then, unless one is a Draft only or names no Skill, makes
     * them the ones the Agent's Tasks may use and removes the Draft.
     */
    @PostMapping("/agents/{name}/your-skills")
    fun publishYourSkills(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val agent = shippedAgent(name)
        val page = yourSkillsDraft(agent, params)
        drafts.save(DraftKind.YOUR_SKILLS, page, DraftFrom(drafts.get(DraftKind.YOUR_SKILLS, page.id)?.version ?: 0))
        val listed = words(page.content)
        yourSkillsRefusal(listed)?.let { return show(model, AgentForm.of(agent), item(name), it) }
        catalogue.publishYourSkills(agent, listed)
        drafts.remove(DraftKind.YOUR_SKILLS, page.id)
        return "redirect:/agents/${agent.name}"
    }

    /** Why [listed] can't be published as Your Skills, naming each Skill that is a Draft only or missing; null when they can. */
    private fun yourSkillsRefusal(listed: List<String>) =
        listed.mapNotNull { drafted.skills.refusal(it) ?: if (catalogue.listedSkill(it) == null) "No Skill named $it." else null }.takeIf { it.isNotEmpty() }?.joinToString(" ")

    /**
     * The Process Builder's set_your_skills (ADR 0019): publishes [skills] at once as Your Skills on the shipped Agent
     * [name], leaving any Draft of them as it is, or throws why not. Gives the Agent's page.
     */
    fun setYourSkills(name: String, skills: List<String>): String {
        val agent = shippedAgent(name)
        yourSkillsRefusal(skills)?.let { throw Refused(it) }
        catalogue.publishYourSkills(agent, catalogue.skillIds(skills))
        return "/agents/${agent.name}"
    }

    /** **Discard Draft** of Your Skills: once confirmed, removes their Draft and reopens the Agent with the published ones. */
    @PostMapping("/agents/{name}/your-skills/discard")
    fun discardYourSkills(@PathVariable name: String, @RequestParam params: Map<String, String>, model: Model): String {
        val agent = shippedAgent(name)
        val page = "/agents/${agent.name}"
        return drafts.discard(DraftKind.YOUR_SKILLS, drafts.get(DraftKind.YOUR_SKILLS, agent.id!!), "Your Skills on ${agent.name}", agent.name, "$page/your-skills", page, "confirm" in params, model)
    }

    /** The form's fields, which repeat for a rule per Limit…, a chip per Skill and a tick per added tool: the filled ones, comma-separated. */
    private fun joined(params: MultiValueMap<String, String>) = params.mapValues { (_, values) -> values.filter(String::isNotBlank).joinToString(", ") }

    /** **Check now**: refreshes the model list from Anthropic's model status table, and goes back to the page with what it found. */
    @PostMapping("/models/check")
    fun checkModels(request: HttpServletRequest, @RequestHeader("Referer", required = false) from: String?): String {
        val toast = try {
            val new = models.check()
            if (new.isEmpty()) Toast("Models up to date", "No new models.", Toast.Kind.SUCCESS) else Toast("New models", "${new.joinToString(", ")}.", Toast.Kind.INFO)
        } catch (e: Exception) {
            Toast("Couldn't check models", "Anthropic's model list couldn't be read: ${e.message}", Toast.Kind.ERROR)
        }
        Shell.toast(request, toast)
        return back(from ?: "/agents")
    }

    /**
     * Deletes one of the user's Agents, once the user has confirmed it, seeing what uses it, and its Draft with it; a
     * Draft-only Agent, which nothing can use, is its Draft.
     */
    @PostMapping("/agents/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam params: Map<String, String>, model: Model): String {
        val item = editable(item(name))
        if ("confirm" !in params) {
            val question = if (item.published == null) "Delete the Draft $name?" else "Delete the Agent $name?"
            return confirm(model, question, if (item.published == null) Dependents(emptyList()) else usedBy.agent(name), "${item.path}/delete", emptyMap(), item.path)
        }
        delete(name)
        return "redirect:/agents"
    }

    fun delete(name: String): String {
        val item = editable(item(name))
        item.published?.file?.deleteIfExists()
        items.removeDraft(item)
        return "/agents/$name"
    }

    /** **Copy**: a Draft-only copy of an Agent, under the first free `<name>-copy`, and its page. */
    @PostMapping("/agents/{name}/copy")
    fun copyToDraft(@PathVariable name: String): String {
        val agent = item(name).shown
        return draftOnly(copied(agent, copyName(agent.name) { taken(it, null) }))
    }

    /** The form of a copy of [agent] named [name]: a shipped Agent's copy lists its shipped Skills and its Your Skills. */
    private fun copied(agent: Agent, name: String) =
        AgentForm.of(agent, name).copy(skills = catalogue.skillIds(agent.skills + catalogue.yourSkills(agent)).joinToString(", "), scripts = scripts.ids(agent.scripts).joinToString(", "))

    /** The Process Builder's copy (ADR 0019): an editable copy of a read-only Agent, published at once into Ants' own folder, under the first free `<name>-copy`. */
    fun copy(name: String): String {
        val agent = find(name)
        val copy = copyName(agent.name) { taken(it, null) || folder.resolve("$it.md").exists() }
        folder.resolve("$copy.md").writeAtomically(copied(agent, copy).copy(id = catalogue.unusedId(AGENT_ID)).text())
        return "redirect:/agents/$copy"
    }

    /**
     * The Process Builder's save (ADR 0019): [text], an agent file, through the page's own save, as a new Agent or over
     * the user's own Agent [name], renaming it without asking. Gives the saved Agent's page, or throws with the page's error.
     */
    fun write(text: String, name: String?): String {
        val agent = name?.let { editable(find(it)) }
        val parsed = try {
            Agent(folder.resolve("new.md"), text)
        } catch (e: YAMLException) {
            throw Refused("The agent file has broken YAML: ${e.message}")
        }
        return redirected("error") { save(it, AgentForm.of(parsed, name = parsed.meta["name"]?.toString().orEmpty()), agent?.let(items::publishedOnly)) }
    }

    /**
     * Publishes [form] as the agent file of [item], or as a new Agent for none, under [id] (a new one for none), or
     * shows the page with why not. A rename moves the file.
     */
    private fun save(model: Model, form: AgentForm, item: AgentItem?, id: String? = null): PublishOutcome {
        val agent = item?.published
        val modal = if (model.getAttribute("use") == true) "?use" else ""
        val target = if (agent != null && form.name == agent.name) agent.file else folder.resolve("${form.name}.md")
        val error = form.error() ?: when {
            taken(form.name, id ?: agent?.id) || catalogue.agents().any { it.name == form.name && it.file != agent?.file } -> "An Agent named ${form.name} already exists."
            target != agent?.file && target.exists() -> "$target already holds another Agent."
            else -> unpublished(form)
        }
        if (error != null) return PublishOutcome.Refused(show(model, form, item, error))
        target.writeAtomically(published(form, id ?: agent?.id ?: catalogue.unusedId(AGENT_ID)).text())
        if (agent != null && target != agent.file) agent.file.deleteIfExists()
        return PublishOutcome.Published("/agents/${form.name}" + if (modal.isEmpty()) "" else "?saved")
    }

    /** Why [form] can't be published yet: each Skill and Script it lists that is a Draft only; null for none. */
    private fun unpublished(form: AgentForm) = (words(form.skills).mapNotNull { drafted.skills.refusal(it) } +
        form.scriptList.mapNotNull { drafted.scripts.refusal(it) }).takeIf { it.isNotEmpty() }?.joinToString(" ")

    /** The page of [item], or the create form for none. */
    private fun show(model: Model, form: AgentForm, item: AgentItem?, error: String? = null): String {
        val shownScripts = scripts.names(form.scriptList)
        val agent = item?.published
        model.addAttribute("form", form.copy(skills = catalogue.skillNames(words(form.skills)).joinToString(", "), scripts = shownScripts.joinToString(", ")))
        model.addAttribute("scripts", (scripts.all().map { it.name } + shownScripts).distinct())
        model.addAttribute("action", item?.path ?: "/agents")
        model.addAttribute("item", item?.name)
        model.addAttribute("readOnly", item?.own == false)
        model.addAttribute("draft", item?.head)
        model.addAttribute("yourSkills", agent?.takeIf { catalogue.shipped(it) && it.id != null }?.let(::yourSkills))
        model.addAttribute("where", agent?.let { catalogue.where(it.file.parent) })
        model.addAttribute("skills", catalogue.skills().distinctBy { it.name })
        model.addAttribute("dependents", agent?.let { usedBy.agent(it.name) })
        model.addAttribute("error", error)
        model.addAttribute("models", models)
        return "agent"
    }

    private fun find(name: String) = catalogue.agents().firstOrNull { it.name == name } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Agent named $name")

    private fun editable(agent: Agent) = agent.takeIf { it.file.parent == folder }
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "${agent.name} is read-only. Copy it to make one you can edit.")

    private fun editable(item: AgentItem) = item.takeIf { it.own } ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "${item.name} is read-only. Copy it to make one you can edit.")
}

/** Your Skills on a shipped Agent as its page shows them: the [skills] by name, and the [head] of their Draft. */
class YourSkillsField(val skills: List<String>, val head: DraftHead)

/** An Agent on the list, the folder it comes from, the [page] it is at and its [state]. */
class AgentRow(val agent: Agent, val where: String, val skills: List<String>, val page: String = agent.name, val state: DraftState = DraftState.PUBLISHED)

/**
 * A Claude Code tool as the Agent page offers it: what it [does], and for one with **Limit…**, what its rules narrow
 * ([limits]), an [example] rule and a [note].
 */
class Tool(val name: String, val does: String, val limits: String? = null, val example: String? = null, val note: String? = null)

/**
 * The Agents page's form, one field per key of the agent file. [tools] holds each ticked tool with its Limit…, the
 * rule specifiers comma-separated (empty for no limit), and a codex Agent has its [sandbox] in their place; [kept]
 * holds the frontmatter keys the form doesn't own.
 */
data class AgentForm(
    val name: String,
    val description: String,
    val cli: String,
    val skills: String,
    val tools: Map<String, String>,
    val otherTools: String,
    val sandbox: String,
    val model: String,
    val subagent: Boolean,
    val instructions: String,
    val kept: Map<*, *> = emptyMap<String, Any?>(),
    val id: String? = null,
    val scripts: String = "",
    val level: String = "",
) {
    val scriptList get() = rules(scripts)

    /** Each rule of [tool]'s **Limit…**, as the page lists them. */
    fun limits(tool: String) = rules(tools[tool].orEmpty())

    /** The tools Ants doesn't list, such as an MCP tool, each ticked on the page under "Added by you". */
    val custom get() = rules(otherTools)

    fun error() = nameError("An Agent", name) ?: when {
        cli !in CLIS -> "Ants runs only ${CLIS.joinToString(" or ")}, not $cli."
        subagent && cli != "claude" -> "Only a claude Agent can be offered as a Subagent."
        level.isNotEmpty() && level.toIntOrNull() !in Level.RANGE -> "A level is ${Level.RANGE.first} to ${Level.RANGE.last}, not $level."
        else -> Agent.sandboxError(cli, sandbox)
    }


    /** The agent file: Claude Code's format, with `cli`, `skills` and `ants-subagent` for Ants. */
    fun text(): String {
        val meta = linkedMapOf<Any?, Any?>()
        if (id != null) meta["id"] = id
        meta["name"] = name
        if (description.isNotEmpty()) meta["description"] = description
        meta["cli"] = cli
        if (skills.isNotBlank()) meta["skills"] = words(skills).joinToString(", ")
        if (scriptList.isNotEmpty()) meta["scripts"] = scriptList.joinToString(", ")
        if (cli == "codex") meta["sandbox"] = sandbox
        else {
            val limited = TOOLS.filter { it in tools }.flatMap { tool -> rules(tools.getValue(tool)).map { "$tool($it)" }.ifEmpty { listOf(tool) } }
            meta["tools"] = (limited + rules(otherTools)).joinToString(", ")
        }
        if (model.isNotEmpty()) meta["model"] = model
        level.toIntOrNull()?.let { meta["level"] = it }
        if (subagent) meta["ants-subagent"] = true
        meta.putAll(kept)
        return "---\n" + yaml.dump(meta) + "---\n" + instructions.trim() + "\n"
    }

    companion object {
        val CLIS = listOf("claude", "codex")

        /**
         * Claude Code's tools an Agent most often needs, in the page's groups. The task-list tools feed the Run page's
         * checklist. `Skill` is left out: Ants never allows it, since a Task's Skills come through `--add-dir`.
         */
        val GROUPS = linkedMapOf(
            "Read the code" to listOf(
                Tool("Read", "Open a file.", "paths", "src/**", "Also limits Glob, Grep and LSP."),
                Tool("Glob", "Find files by name."),
                Tool("Grep", "Search inside files."),
                Tool("LSP", "Jump to definitions and find references."),
            ),
            "Change files" to listOf(
                // Probed on claude 2.1.303: `Write` ticked without a limit writes anywhere, whatever Edit's limit.
                Tool("Edit", "Change part of a file.", "paths", "src/**", "A ticked Write needs a Limit of its own."),
                Tool("Write", "Create or overwrite a file.", "paths", "src/**"),
                Tool("NotebookEdit", "Edit Jupyter notebooks (.ipynb). Only matters if your repos have them."),
            ),
            "Run commands" to listOf(Tool("Bash", "Shell commands: builds, tests, git.", "commands", "pnpm test", "* matches the rest, as in git *.")),
            "Web" to listOf(
                Tool("WebFetch", "Read the page at a URL.", "domains", "domain:docs.spring.io"),
                Tool("WebSearch", "Search the web."),
            ),
            "Helpers" to listOf(
                Tool("Agent", "Start Subagents inside this Task's slot.", "Subagent types", "Explore"),
                Tool("TaskCreate", "Add a step to its own task list, the checklist on the Run page."),
                Tool("TaskGet", "Read one step of its task list."),
                Tool("TaskList", "Read its whole task list."),
                Tool("TaskUpdate", "Tick off or change a step."),
            ),
        )

        /** Claude Code's other tools (2.1.295), each with why it is usually off for an Agent running unattended in a Run. */
        val OTHER_TOOLS = linkedMapOf(
            "Artifact" to "Publishes to claude.ai.",
            "AskUserQuestion" to "Nobody is at a terminal, so the question goes unanswered. A Round is how an Agent asks.",
            "CronCreate" to "Its schedule dies with the session. Schedule the Process instead.",
            "CronDelete" to "Its schedule dies with the session.",
            "CronList" to "Its schedule dies with the session.",
            "EndConversation" to "Ends the session without a Result.",
            "EnterPlanMode" to "Plan mode waits for an approval nobody gives.",
            "EnterWorktree" to "Moves the Agent out of the Run workspace.",
            "ExitPlanMode" to "Plan mode waits for an approval nobody gives.",
            "ExitWorktree" to "Moves the Agent out of the Run workspace.",
            "ListAgents" to "Reaches your other Claude sessions on this machine.",
            "ListMcpResourcesTool" to "Only useful with MCP, which Ants turns off.",
            "Monitor" to "Background work is off for Agents.",
            "PowerShell" to "Windows only.",
            "PushNotification" to "Notifies outside Ants; the Notification list is the place.",
            "ReadMcpResourceTool" to "Only useful with MCP, which Ants turns off.",
            "RemoteTrigger" to "Creates Routines on claude.ai, outside Ants.",
            "ReportFindings" to "Draws review findings in Claude Code's own screen, which nobody sees.",
            "ScheduleWakeup" to "Its schedule dies with the session.",
            "SendFeedback" to "Queues feedback nobody reviews.",
            "SendMessage" to "Reaches your other Claude sessions on this machine.",
            "SendUserFile" to "Sends files outside the Run.",
            "ShareOnboardingGuide" to "Uploads to claude.ai.",
            "TaskOutput" to "Background work is off for Agents.",
            "TaskStop" to "Background work is off for Agents.",
            "ToolSearch" to "Only useful with MCP, which Ants turns off.",
            "WaitForMcpServers" to "Only useful with MCP, which Ants turns off.",
            "Workflow" to "Starts many background agents outside the slot cap.",
        )

        /** The tools offered as tick boxes; any other tool is one the user added by name. */
        val TOOLS = (GROUPS.values.flatten().map { it.name } + OTHER_TOOLS.keys).sorted()

        /** What the page says of a tool Ants doesn't list. */
        fun note(tool: String) = if (tool.startsWith("mcp__")) MCP_NOTE else UNKNOWN_NOTE
        const val MCP_NOTE = "An MCP tool. Ants starts Agents with MCP off, so it needs an MCP server Ants doesn't configure yet."
        const val UNKNOWN_NOTE = "Not a tool Ants knows. Passed to Claude Code as written."

        private val KEYS = setOf("id", "name", "description", "cli", "skills", "scripts", "tools", "sandbox", "model", "level", "ants-subagent")

        fun of(agent: Agent, name: String = agent.name): AgentForm {
            val rules = agent.tools ?: AgentLauncher.DEFAULT_TOOLS
            fun tool(rule: String) = rule.substringBefore('(').trim()
            return AgentForm(
                name = name,
                description = agent.description,
                cli = agent.cli,
                skills = agent.skills.joinToString(", "),
                tools = rules.filter { tool(it) in TOOLS }.groupBy(::tool) { it.substringAfter('(', "").removeSuffix(")") }
                    .mapValues { (_, limits) -> limits.filter(String::isNotEmpty).joinToString(", ") },
                otherTools = rules.filter { tool(it) !in TOOLS }.joinToString(", "),
                sandbox = agent.sandbox,
                model = agent.model.orEmpty(),
                subagent = agent.subagent,
                instructions = agent.instructions,
                kept = agent.meta.filterKeys { it !in KEYS },
                id = agent.id,
                scripts = agent.scripts.joinToString(", "),
                level = agent.meta["level"]?.toString().orEmpty(),
            )
        }

        fun of(params: Map<String, String>): AgentForm {
            fun param(name: String) = params[name].orEmpty().trim()
            return AgentForm(
                name = param("name"),
                description = param("description"),
                cli = param("cli").ifEmpty { "claude" },
                skills = param("skills"),
                tools = TOOLS.filter { "tool.$it" in params }.associateWith { param("limit.$it") },
                otherTools = param("other-tools"),
                sandbox = param("sandbox").ifEmpty { "workspace-write" },
                model = param("model").let { if (it == "other") param("model-other") else it },
                subagent = "subagent" in params,
                instructions = params["instructions"].orEmpty(),
                scripts = param("scripts"),
                level = param("level"),
            )
        }
    }
}

/** A Claude model from Anthropic's model status table; [retires] is set once it is deprecated. */
class ClaudeModel(val id: String, val retires: String?) {
    /** How the model dropdown names it: Opus 5.5 for claude-opus-5-5, Haiku 4.5 for claude-haiku-4-5-20251001. */
    val label: String
        get() {
            val (family, version) = Regex("""^claude-([a-z]+)-(.*?)(-\d{8})?$""").find(id)?.destructured ?: return id
            return family.replaceFirstChar(Char::uppercase) + " " + if (version.firstOrNull()?.isDigit() == true) version.replace('-', '.') else version
        }
}

/**
 * The model dropdown's choices: Anthropic's public model status table, without the retired models. It starts as the
 * copy bundled with Ants (models.md), and the update check refreshes it.
 */
@Component
class Models(@Value("\${ants.models:https://docs.claude.com/en/docs/about-claude/model-deprecations.md}") private val table: String) {
    @Volatile
    final var all = read(javaClass.getResource("/models.md")!!.readText())
        private set

    /** When the list was last read from the table; null while it is the copy bundled with Ants. */
    @Volatile
    final var checked: Instant? = null
        private set

    /** Reads the table now, returning the models new since the last read; throws when it can't be read. */
    fun check(): List<String> {
        val before = all.map { it.id }.toSet()
        refresh(URI(table).toURL().readText())
        checked = Instant.now()
        return all.map { it.id }.filter { it !in before }
    }

    /** When the list was last checked, as the Agent page says it: the time, with the day when it isn't today. */
    val lastChecked: String?
        get() = checked?.let(::shortTime)

    /** The name the dropdown gives [id]. */
    fun label(id: String) = all.firstOrNull { it.id == id }?.label ?: id

    /** Replaces the list with the models in [table], the status table as markdown; a table with none changes nothing. */
    fun refresh(table: String) {
        all = read(table).ifEmpty { all }
    }

    private fun read(table: String) = table.lines().mapNotNull {
        Regex("""^\|\s*(claude-\S+)\s*\|\s*(Active|Deprecated)\s*\|[^|]*\|\s*(.*?)\s*\|""").find(it)?.destructured
    }.map { (id, state, retires) -> ClaudeModel(id, retires.takeIf { state == "Deprecated" }) }

    /** Claude Code's aliases, each with what it resolves to on Anthropic's API: the newest active model of its family. */
    val aliases get() = listOf("opus", "sonnet", "haiku", "fable").associateWith { alias -> all.firstOrNull { "-$alias-" in "${it.id}-" && it.retires == null }?.id }

    /** The model [id], if it is deprecated. */
    fun deprecated(id: String) = all.firstOrNull { it.id == id && it.retires != null }

    /** [id] is neither a listed model nor an alias, such as a model ID the user typed. */
    fun unlisted(id: String) = id !in aliases && all.none { it.id == id }
}
