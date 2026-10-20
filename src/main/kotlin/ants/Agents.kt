package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.ExtendedModelMap
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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * The Agents page: a form over one agent file. Only Agents in ANTS_HOME's `agents/` are edited; shipped Agents and
 * those from extra folders are read-only, with Copy. Nothing is cached, so an edit reaches the next launch.
 */
@Controller
class Agents(private val catalogue: Catalogue, private val usedBy: UsedBy, private val models: Models, private val settings: Settings, @Value("\${ants.home}") home: Path) {
    private val folder = home.resolve("agents")

    @GetMapping("/agents")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, error: String?): String {
        val vault = catalogue.skills().map { it.name }.distinct().associateWith { name -> runCatching { catalogue.vault(catalogue.skill(name)) }.getOrDefault(false) }
        model.addAttribute("agents", catalogue.agents().sortedBy { it.name }.map { AgentRow(it, catalogue.where(it.file.parent), it.skills.any { name -> vault[name] == true }) })
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

    @PostMapping("/agents/new")
    fun create(@RequestParam params: MultiValueMap<String, String>, model: Model) = joined(params).let { save(model.addAttribute("use", "use" in it), AgentForm.of(it), null) }

    /** Fills the create form from one agent file, dropping the keys Ants doesn't use, or refuses it with a reason. */
    @PostMapping("/agents/import")
    fun import(@RequestParam file: MultipartFile, model: Model): String {
        val filename = file.originalFilename?.substringAfterLast('/')?.ifEmpty { null } ?: "agent.md"
        val agent = try {
            Agent(Path.of(filename), file.bytes.decodeToString())
        } catch (e: YAMLException) {
            return list(model, "$filename has broken YAML: ${e.message}")
        }
        if (agent.cli !in AgentForm.CLIS) return list(model, "$filename runs cli ${agent.cli}, and Ants runs only ${AgentForm.CLIS.joinToString(" or ")}.")
        Agent.sandboxError(agent.cli, agent.sandbox)?.let { return list(model, "$filename: $it") }
        val form = AgentForm.of(agent)
        val notice = form.kept.keys.takeIf { it.isNotEmpty() }?.let { "Dropped the keys Ants doesn't use: ${it.joinToString(", ")}." }
        return show(model, form.copy(kept = emptyMap<String, Any?>()), null, notice = notice)
    }

    /**
     * The Agents and Skills as the modeler's Agent Task panel picks them, read afresh each time so a new one shows up:
     * each Agent's description, Skills and folder, and whether it is the user's own (New Skill adds itself only to those), and each Skill's declared inputs, its Vault and Answer History defaults and its result schema;
     * and the Profiles a Process can name.
     */
    @GetMapping("/catalogue.json")
    @ResponseBody
    fun catalogue() = mapOf(
        "agents" to catalogue.agents().map {
            mapOf("name" to it.name, "description" to it.description, "skills" to it.skills, "own" to (it.file.parent == folder), "folder" to it.file.parent.toString())
        },
        "skills" to catalogue.skills().map { skill ->
            mapOf(
                "name" to skill.name, "description" to skill.description, "inputs" to skill.inputs,
                "vault" to runCatching { catalogue.vault(skill) }.getOrDefault(skill.vault), "answerHistory" to skill.answerHistory,
                "output" to runCatching { skill.outputSchema.takeIf { it.isNotEmpty() } }.getOrNull(),
            )
        },
        "profiles" to settings.profiles().keys,
    )

    @GetMapping("/agents/{name}")
    fun agent(@PathVariable name: String, model: Model) = find(name).let { show(model, AgentForm.of(it), it) }

    @PostMapping("/agents/{name}")
    fun edit(@PathVariable name: String, @RequestParam params: MultiValueMap<String, String>, model: Model): String {
        val agent = editable(find(name))
        val fields = joined(params)
        return save(model, AgentForm.of(fields).copy(kept = AgentForm.of(agent).kept), agent, fields)
    }

    /** The form's fields, which repeat for a rule per Limit…, a chip per Skill and a tick per added tool: the filled ones, comma-separated. */
    private fun joined(params: MultiValueMap<String, String>) = params.mapValues { (_, values) -> values.filter(String::isNotBlank).joinToString(", ") }

    /** **Check now**: refreshes the model list from Anthropic's model status table, and goes back to the page with what it found. */
    @PostMapping("/models/check")
    fun checkModels(request: HttpServletRequest, @RequestHeader("Referer", required = false) from: String?): String {
        val toast = try {
            val new = models.check()
            Toast("Checked", if (new.isEmpty()) "No new models." else "New: ${new.joinToString(", ")}.")
        } catch (e: Exception) {
            Toast("Not checked", "Anthropic's model list couldn't be read: ${e.message}")
        }
        Shell.toast(request, toast)
        return back(from ?: "/agents")
    }

    /** Deletes one of the user's Agents, once the user has confirmed it, seeing what uses it. */
    @PostMapping("/agents/{name}/delete")
    fun delete(@PathVariable name: String, @RequestParam params: Map<String, String>, model: Model): String {
        val agent = editable(find(name))
        if ("confirm" !in params) return confirm(model, "Delete the Agent $name?", usedBy.agent(name), "/agents/$name/delete", emptyMap(), "/agents/$name")
        agent.file.deleteIfExists()
        return "redirect:/agents"
    }

    /** Writes an editable copy of a read-only Agent into Ants' own folder, under the first free `<name>-copy`. */
    @PostMapping("/agents/{name}/copy")
    fun copy(@PathVariable name: String): String {
        val agent = find(name)
        val taken = catalogue.agents().map { it.name }.toSet()
        val copy = copyName(agent.name) { it in taken || folder.resolve("$it.md").exists() }
        folder.resolve("$copy.md").writeAtomically(AgentForm.of(agent, copy).text())
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
        val model = ExtendedModelMap()
        val view = save(model, AgentForm.of(parsed, name = parsed.meta["name"]?.toString().orEmpty()), agent, mapOf("confirm" to ""))
        return view.removePrefix("redirect:").takeIf { it != view } ?: throw Refused(model["error"]?.toString())
    }

    private fun save(model: Model, form: AgentForm, agent: Agent?, params: Map<String, String> = emptyMap()): String {
        val target = if (agent != null && form.name == agent.name) agent.file else folder.resolve("${form.name}.md")
        val error = form.error() ?: when {
            catalogue.agents().any { it.name == form.name && it.file != agent?.file } -> "An Agent named ${form.name} already exists."
            target != agent?.file && target.exists() -> "$target already holds another Agent."
            else -> null
        }
        if (error != null) return show(model, form, agent, error)
        if (agent != null && form.name != agent.name && "confirm" !in params) {
            return confirm(model, "Rename the Agent ${agent.name} to ${form.name}?", usedBy.agent(agent.name), "/agents/${agent.name}", params, "/agents/${agent.name}")
        }
        target.writeAtomically(form.text())
        if (agent != null && target != agent.file) agent.file.deleteIfExists()
        return "redirect:/agents/${form.name}"
    }

    private fun show(model: Model, form: AgentForm, agent: Agent?, error: String? = null, notice: String? = null): String {
        model.addAttribute("form", form)
        model.addAttribute("action", agent?.let { "/agents/${it.name}" } ?: "/agents/new")
        model.addAttribute("file", agent?.file?.toString())
        model.addAttribute("readOnly", agent != null && agent.file.parent != folder)
        model.addAttribute("where", agent?.let { catalogue.where(it.file.parent) })
        model.addAttribute("skills", catalogue.skills().distinctBy { it.name })
        model.addAttribute("dependents", agent?.let { usedBy.agent(it.name) })
        model.addAttribute("error", error)
        model.addAttribute("notice", notice)
        model.addAttribute("models", models)
        return "agent"
    }

    private fun find(name: String) = catalogue.agents().firstOrNull { it.name == name } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Agent named $name")

    private fun editable(agent: Agent) = agent.takeIf { it.file.parent == folder }
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "${agent.name} is read-only. Copy it to make one you can edit.")
}

/** An Agent on the list: the folder it comes from, and whether any of its Skills reads the Vault. */
class AgentRow(val agent: Agent, val where: String, val vault: Boolean)

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
) {
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
        val meta = linkedMapOf<Any?, Any?>("name" to name)
        if (description.isNotEmpty()) meta["description"] = description
        meta["cli"] = cli
        if (skills.isNotBlank()) meta["skills"] = words(skills).joinToString(", ")
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

        private val KEYS = setOf("name", "description", "cli", "skills", "tools", "sandbox", "model", "ants-subagent")

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
        get() = checked?.atZone(ZoneId.systemDefault())?.let { if (it.toLocalDate() == LocalDate.now()) "%tR".format(it) else "%1\$te %1\$tb %1\$tR".format(it) }

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
