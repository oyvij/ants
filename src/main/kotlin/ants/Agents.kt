package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
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
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * The Agents page: a form over one agent file. Only Agents in ANTS_HOME's `agents/` are edited; shipped Agents and
 * those from extra folders are read-only, with Copy. Nothing is cached, so an edit reaches the next launch.
 */
@Controller
class Agents(private val catalogue: Catalogue, private val usedBy: UsedBy, private val models: Models, @Value("\${ants.home}") home: Path) {
    private val folder = home.resolve("agents")

    @GetMapping("/agents")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, error: String?): String {
        model.addAttribute("agents", catalogue.agents().sortedBy { it.name })
        model.addAttribute("folder", folder)
        model.addAttribute("error", error)
        return "agents"
    }

    /** The create form; with `use`, as the modeler's New Agent modal shows it, whose Create and use picks it on the Task. */
    @GetMapping("/agents/new")
    fun new(@RequestParam use: String?, model: Model) =
        show(model.addAttribute("use", use != null), AgentForm.of(Agent(folder.resolve("new.md"), ""), name = ""), null)

    @PostMapping("/agents/new")
    fun create(@RequestParam params: Map<String, String>, model: Model) = save(model.addAttribute("use", "use" in params), AgentForm.of(params), null)

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
     * each Agent's Skills, and each Skill's declared inputs, its Vault and Answer History defaults and its result schema.
     */
    @GetMapping("/catalogue.json")
    @ResponseBody
    fun catalogue() = mapOf(
        "agents" to catalogue.agents().map { mapOf("name" to it.name, "description" to it.description, "skills" to it.skills) },
        "skills" to catalogue.skills().map { skill ->
            mapOf(
                "name" to skill.name, "description" to skill.description, "inputs" to skill.inputs,
                "vault" to runCatching { catalogue.vault(skill) }.getOrDefault(skill.vault), "answerHistory" to skill.answerHistory,
                "output" to runCatching { skill.outputSchema.takeIf { it.isNotEmpty() } }.getOrNull(),
            )
        },
    )

    @GetMapping("/agents/{name}")
    fun agent(@PathVariable name: String, model: Model) = find(name).let { show(model, AgentForm.of(it), it) }

    @PostMapping("/agents/{name}")
    fun edit(@PathVariable name: String, @RequestParam params: Map<String, String>, model: Model): String {
        val agent = editable(find(name))
        return save(model, AgentForm.of(params).copy(kept = AgentForm.of(agent).kept), agent, params)
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

        /** The tools offered as tick boxes; any other tool goes under Other tools. */
        val TOOLS = (AgentLauncher.DEFAULT_TOOLS + "Agent").sorted()

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
class ClaudeModel(val id: String, val retires: String?)

/**
 * The model dropdown's choices: Anthropic's public model status table, without the retired models. It starts as the
 * copy bundled with Ants (models.md), and the update check refreshes it.
 */
@Component
class Models {
    @Volatile
    final var all = read(javaClass.getResource("/models.md")!!.readText())
        private set

    /** Replaces the list with the models in [table], the status table as markdown; a table with none changes nothing. */
    fun refresh(table: String) {
        all = read(table).ifEmpty { all }
    }

    private fun read(table: String) = table.lines().mapNotNull {
        Regex("""^\|\s*(claude-\S+)\s*\|\s*(Active|Deprecated)\s*\|[^|]*\|\s*(.*?)\s*\|""").find(it)?.destructured
    }.map { (id, state, retires) -> ClaudeModel(id, retires.takeIf { state == "Deprecated" }) }

    /** Claude Code's aliases, each with what it resolves to on Anthropic's API: the newest active model of its family. */
    val aliases get() = listOf("fable", "opus", "sonnet", "haiku").associateWith { alias -> all.firstOrNull { "-$alias-" in "${it.id}-" && it.retires == null }?.id }

    /** The model [id], if it is deprecated. */
    fun deprecated(id: String) = all.firstOrNull { it.id == id && it.retires != null }

    /** [id] is neither a listed model nor an alias, such as a model ID the user typed. */
    fun unlisted(id: String) = id !in aliases && all.none { it.id == id }
}
