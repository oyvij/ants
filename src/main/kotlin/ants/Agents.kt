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

/**
 * The Agents page: a form over one agent file. Only Agents in ANTS_HOME's `agents/` are edited; shipped Agents and
 * those from extra folders are read-only, with Copy. The page autosaves to the Agent's Draft, and Publish makes the
 * Draft the agent file, which is all a launch reads; nothing is cached, so a Publish reaches the next launch.
 */
@Controller
class Agents(
    private val catalogue: Catalogue, private val usedBy: UsedBy, private val models: Models, private val settings: Settings, private val scripts: Scripts,
    private val drafts: Drafts, @Value("\${ants.home}") home: Path,
) {
    private val folder = home.resolve("agents")

    /**
     * An Agent as its page shows it: the [published] agent file, its [draft], or both. A read-only Agent has no Draft.
     * While it is a Draft only, its page is at the Draft's key.
     */
    private inner class Item(val published: Agent?, val draft: Draft?) {
        val own get() = published == null || published.file.parent == folder
        val state get() = DraftState.of(published != null, draft != null)
        val name get() = published?.name ?: draft!!.key
        val path get() = "/agents/$name"

        /** The agent file the page shows: the Draft's when there is one. */
        val shown get() = draft?.let { Agent(folder.resolve("$name.md"), it.content) } ?: published!!
    }

    /** The Agent whose page is /agents/[name]: a published one by its name, else a Draft-only one by its key. */
    private fun item(name: String): Item {
        catalogue.agents().firstOrNull { it.name == name }?.let { agent -> return Item(agent, agent.id?.takeIf { agent.file.parent == folder }?.let { drafts.get(DraftKind.AGENT, it) }) }
        return draftsOnly().firstOrNull { it.key == name }?.let { Item(null, it) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Agent named $name")
    }

    /** The Drafts of Agents never published. */
    private fun draftsOnly(): List<Draft> {
        val published = catalogue.agents(withHidden = true).mapNotNull { it.id }.toSet()
        return drafts.all(DraftKind.AGENT).filter { it.id !in published }
    }

    /** Whether another Agent than the one with [id] has [name]: a published one, or a Draft-only one by its name or the page it is at. */
    private fun taken(name: String, id: String?) = catalogue.agents().any { it.name == name && it.id != id } ||
        draftsOnly().any { it.id != id && (it.name == name || it.key == name) }

    @GetMapping("/agents")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, error: String?): String {
        val published = catalogue.agents().map { agent ->
            val draft = agent.id?.takeIf { agent.file.parent == folder }?.let { drafts.get(DraftKind.AGENT, it) }
            AgentRow(agent, catalogue.where(agent.file.parent), catalogue.skillNames(agent.skills), agent.name, DraftState.of(true, draft != null))
        }
        val draftOnly = draftsOnly().map { draft ->
            val agent = Agent(folder.resolve("${draft.key}.md"), draft.content)
            AgentRow(agent, catalogue.where(folder), catalogue.skillNames(agent.skills), draft.key, DraftState.DRAFT_ONLY)
        }
        model.addAttribute("agents", (published + draftOnly).sortedBy { it.agent.name })
        model.addAttribute("error", error)
        return "agents"
    }

    /**
     * The create form; with `use`, as the modeler's New Agent modal shows it, whose Create and use picks it on the Task,
     * and with [skills], the Task's Skill, filled in.
     */
    @GetMapping("/agents/new")
    fun new(@RequestParam use: String?, @RequestParam skills: String?, model: Model) =
        show(model.addAttribute("use", use != null), AgentForm.of(Agent(folder.resolve("new.md"), ""), name = "").copy(skills = skills.orEmpty()), null)

    /** The create form's Create and use, in the modeler's modal: it publishes at once. */
    @PostMapping("/agents/new")
    fun create(@RequestParam params: MultiValueMap<String, String>, model: Model) = joined(params).let { save(model.addAttribute("use", "use" in it), AgentForm.of(it), null) }

    /** **New Agent**: a Draft-only Agent at once, with a fixed id and the first free name of `new-agent`, `new-agent-2`, …, and its page. */
    @PostMapping("/agents/new/draft")
    fun newDraft() = draftOnly(AgentForm.of(Agent(folder.resolve("new.md"), ""), name = freeName("new-agent") { taken(it, null) }))

    private fun draftOnly(form: AgentForm): String {
        val id = catalogue.unusedId(AGENT_ID)
        drafts.save(DraftKind.AGENT, id, form.name, form.name, form.copy(id = id).text(), 0)
        return "redirect:/agents/${form.name}"
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
        if (!published.startsWith("redirect:")) return list(model, "$filename: ${model.getAttribute("error")}")
        form.kept.keys.takeIf { it.isNotEmpty() }?.let { Shell.toast(request, Toast("Imported ${form.name}", "Dropped the keys Ants doesn't use: ${it.joinToString(", ")}.", Toast.Kind.INFO)) }
        return published
    }

    /**
     * The Agents and Skills as the modeler's Agent Task panel picks them, read afresh each time so a new one shows up:
     * each Agent's description, Skills (every one by name for `skills: "*"`) and folder, and whether it is the user's own (New Skill adds itself only to those, Edit opens only those), and each Skill's description, whether it is the user's own (Edit opens only those) and its folder;
     * each Script's input names, and the type, description and required mark of each as `about`;
     * and the Profiles a Process can name.
     */
    @GetMapping("/catalogue.json")
    @ResponseBody
    fun catalogue() = mapOf(
        "agents" to catalogue.agents().map {
            val skills = catalogue.allowedNames(it)
            mapOf("id" to it.id, "name" to it.name, "description" to it.description, "skills" to skills, "own" to (it.file.parent == folder), "folder" to it.file.parent.toString())
        },
        "skills" to catalogue.skills().map { skill ->
            mapOf("id" to skill.id, "name" to skill.name, "description" to skill.description, "own" to skill.file.startsWith(catalogue.yourSkills), "folder" to skill.folder.parent.toString())
        },
        "scripts" to scripts.all().map { script ->
            val about = ScriptSchema.rows(script.inputs).associate { it.name to mapOf("type" to ScriptSchema.TYPES[it.type], "description" to it.description, "required" to it.required) }
            mapOf("id" to script.id, "name" to script.name, "description" to script.description, "inputs" to script.inputNames, "about" to about)
        },
        "profiles" to settings.profiles().keys,
        "nameBeside" to LINKED_NAME,
    )

    /** The Agent's page: its Draft when it has one, else the published Agent. */
    @GetMapping("/agents/{name}")
    fun agent(@PathVariable name: String, @RequestParam use: String?, model: Model) = item(name).let { show(model.addAttribute("use", use != null), AgentForm.of(it.shown), it) }

    /**
     * The page's autosave: the page as the Agent's Draft, whatever state it is in, unless the Draft changed since the
     * page loaded [version]. A Draft-only Agent's page follows its name, while no other Agent has it.
     */
    @PostMapping("/agents/{name}/draft")
    @ResponseBody
    fun autosave(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, @RequestParam version: Long): ResponseEntity<Map<String, Any>> {
        val item = editable(item(name))
        val form = pageForm(item, joined(params))
        val id = idOf(item)
        val key = item.published?.name ?: form.name.takeIf { it == item.draft!!.key || nameError("An Agent", it) == null && !taken(it, id) } ?: item.draft!!.key
        val save = drafts.save(DraftKind.AGENT, id, key, form.name, published(form, id).text(), version)
        return Drafts.answer(DraftKind.AGENT, save, DraftState.of(item.published != null, true), "/agents/$key")
    }

    /** **Publish**: keeps the page as the Draft, then saves it as Save did; if that passes, the Draft is the agent file and is removed. */
    @PostMapping("/agents/{name}")
    fun publish(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val item = editable(item(name))
        val fields = joined(params)
        val form = pageForm(item, fields)
        val id = idOf(item)
        drafts.save(DraftKind.AGENT, id, item.draft?.key ?: item.name, form.name, published(form, id).text(), drafts.get(DraftKind.AGENT, id)?.version ?: 0)
        val view = save(model.addAttribute("use", "use" in fields), form, item(name), id)
        if (view.startsWith("redirect:")) drafts.remove(DraftKind.AGENT, id)
        return view
    }

    /** The page's fields as the agent file's form, keeping the frontmatter keys the form doesn't own from what the page showed. */
    private fun pageForm(item: Item, fields: Map<String, String>) = AgentForm.of(fields).copy(kept = AgentForm.of(item.shown).kept)

    /** The form as its agent file holds it: with [id], and the Skills and Scripts it lists by their ids. */
    private fun published(form: AgentForm, id: String) =
        form.copy(id = id, skills = catalogue.skillIds(words(form.skills)).joinToString(", "), scripts = scripts.ids(form.scriptList).joinToString(", "))

    /** The id the Agent's Draft is kept under: a hand-written agent file without one gets one first, as the startup migration gives it. */
    private fun idOf(item: Item): String {
        item.draft?.let { return it.id }
        val agent = item.published!!
        agent.id?.let { return it }
        return catalogue.unusedId(AGENT_ID).also { agent.file.writeAtomically(withId(agent.file.readText(), it)) }
    }

    /** **Discard Draft**: once confirmed, removes the Draft and reopens the published Agent. */
    @PostMapping("/agents/{name}/discard")
    fun discard(@PathVariable name: String, @RequestParam params: Map<String, String>, model: Model): String {
        val item = editable(item(name))
        val draft = item.draft ?: return "redirect:${item.path}"
        if (item.published == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name was never published: Delete it instead.")
        if ("confirm" !in params) return confirm(model, "Discard the Draft of $name?", Dependents(emptyList()), "${item.path}/discard", emptyMap(), item.path)
        drafts.remove(DraftKind.AGENT, draft.id)
        return "redirect:${item.path}"
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
        (item.draft?.id ?: item.published?.id)?.let { drafts.remove(DraftKind.AGENT, it) }
        return "/agents/$name"
    }

    /** **Copy**: a Draft-only copy of an Agent, under the first free `<name>-copy`, and its page. */
    @PostMapping("/agents/{name}/copy")
    fun copyToDraft(@PathVariable name: String): String {
        val agent = item(name).shown
        return draftOnly(copied(agent, copyName(agent.name) { taken(it, null) }))
    }

    private fun copied(agent: Agent, name: String) = AgentForm.of(agent, name).copy(skills = catalogue.skillIds(agent.skills).joinToString(", "), scripts = scripts.ids(agent.scripts).joinToString(", "))

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
        return redirected("error") { save(it, AgentForm.of(parsed, name = parsed.meta["name"]?.toString().orEmpty()), agent?.let { Item(it, null) }) }
    }

    /**
     * Publishes [form] as the agent file of [item], or as a new Agent for none, under [id] (a new one for none), or
     * shows the page with why not. A rename moves the file.
     */
    private fun save(model: Model, form: AgentForm, item: Item?, id: String? = null): String {
        val agent = item?.published
        val modal = if (model.getAttribute("use") == true) "?use" else ""
        val target = if (agent != null && form.name == agent.name) agent.file else folder.resolve("${form.name}.md")
        val error = form.error() ?: when {
            taken(form.name, id ?: agent?.id) || catalogue.agents().any { it.name == form.name && it.file != agent?.file } -> "An Agent named ${form.name} already exists."
            target != agent?.file && target.exists() -> "$target already holds another Agent."
            else -> null
        }
        if (error != null) return show(model, form, item, error)
        target.writeAtomically(published(form, id ?: agent?.id ?: catalogue.unusedId(AGENT_ID)).text())
        if (agent != null && target != agent.file) agent.file.deleteIfExists()
        return "redirect:/agents/${form.name}" + if (modal.isEmpty()) "" else "?saved"
    }

    /** The page of [item], or the create form for none. */
    private fun show(model: Model, form: AgentForm, item: Item?, error: String? = null): String {
        val shownScripts = scripts.names(form.scriptList)
        val agent = item?.published
        model.addAttribute("form", form.copy(skills = catalogue.skillNames(words(form.skills)).joinToString(", "), scripts = shownScripts.joinToString(", ")))
        model.addAttribute("scripts", (scripts.all().map { it.name } + shownScripts).distinct())
        model.addAttribute("action", item?.path ?: "/agents/new")
        model.addAttribute("item", item?.name)
        model.addAttribute("readOnly", item?.own == false)
        model.addAttribute("draft", item?.takeIf { it.own }?.let { DraftHead(it.state, it.draft?.version ?: 0, it.draft?.savedLine, "${it.path}/draft") })
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

    private fun editable(item: Item) = item.takeIf { it.own } ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "${item.name} is read-only. Copy it to make one you can edit.")
}

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
) {
    val scriptList get() = rules(scripts)

    /** Each rule of [tool]'s **Limit…**, as the page lists them. */
    fun limits(tool: String) = rules(tools[tool].orEmpty())

    /** The tools Ants doesn't list, such as an MCP tool, each ticked on the page under "Added by you". */
    val custom get() = rules(otherTools)

    fun error() = nameError("An Agent", name) ?: when {
        cli !in CLIS -> "Ants runs only ${CLIS.joinToString(" or ")}, not $cli."
        subagent && cli != "claude" -> "Only a claude Agent can be offered as a Subagent."
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

        private val KEYS = setOf("id", "name", "description", "cli", "skills", "scripts", "tools", "sandbox", "model", "ants-subagent")

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
