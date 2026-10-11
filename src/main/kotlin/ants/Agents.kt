package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The Agents page: a form over one agent file. Only Agents in ANTS_HOME's `agents/` are edited; shipped Agents and
 * those from extra folders are read-only, with Copy. Nothing is cached, so an edit reaches the next launch.
 */
@Controller
class Agents(private val catalogue: Catalogue, @Value("\${ants.home}") home: Path) {
    private val folder = home.resolve("agents")

    @GetMapping("/agents")
    fun list(model: Model) = list(model, null)

    private fun list(model: Model, error: String?): String {
        model.addAttribute("agents", catalogue.agents().sortedBy { it.name })
        model.addAttribute("folder", folder)
        model.addAttribute("error", error)
        return "agents"
    }

    @GetMapping("/agents/new")
    fun new(model: Model) = show(model, AgentForm.of(Agent(folder.resolve("new.md"), ""), name = ""), null)

    @PostMapping("/agents/new")
    fun create(@RequestParam params: Map<String, String>, model: Model) = save(model, AgentForm.of(params), null)

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
        val form = AgentForm.of(agent)
        val notice = form.kept.keys.takeIf { it.isNotEmpty() }?.let { "Dropped the keys Ants doesn't use: ${it.joinToString(", ")}." }
        return show(model, form.copy(kept = emptyMap<String, Any?>()), null, notice = notice)
    }

    @GetMapping("/agents/{name}")
    fun agent(@PathVariable name: String, model: Model) = find(name).let { show(model, AgentForm.of(it), it) }

    @PostMapping("/agents/{name}")
    fun edit(@PathVariable name: String, @RequestParam params: Map<String, String>, model: Model): String {
        val agent = editable(find(name))
        return save(model, AgentForm.of(params).copy(kept = AgentForm.of(agent).kept), agent)
    }

    /** Writes an editable copy of a read-only Agent into Ants' own folder, under the first free `<name>-copy`. */
    @PostMapping("/agents/{name}/copy")
    fun copy(@PathVariable name: String): String {
        val agent = find(name)
        val base = agent.name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-') + "-copy"
        val taken = catalogue.agents().map { it.name }.toSet()
        val copy = generateSequence(1, Int::inc).map { if (it == 1) base else "$base-$it" }.first { it !in taken && !folder.resolve("$it.md").exists() }
        write(folder.resolve("$copy.md"), AgentForm.of(agent, copy).text())
        return "redirect:/agents/$copy"
    }

    private fun save(model: Model, form: AgentForm, agent: Agent?): String {
        val target = if (agent != null && form.name == agent.name) agent.file else folder.resolve("${form.name}.md")
        val error = form.error() ?: when {
            catalogue.agents().any { it.name == form.name && it.file != agent?.file } -> "An Agent named ${form.name} already exists."
            target != agent?.file && target.exists() -> "$target already holds another Agent."
            else -> null
        }
        if (error != null) return show(model, form, agent, error)
        write(target, form.text())
        if (agent != null && target != agent.file) agent.file.deleteIfExists()
        return "redirect:/agents/${form.name}"
    }

    private fun show(model: Model, form: AgentForm, agent: Agent?, error: String? = null, notice: String? = null): String {
        model.addAttribute("form", form)
        model.addAttribute("action", agent?.let { "/agents/${it.name}" } ?: "/agents/new")
        model.addAttribute("file", agent?.file?.toString())
        model.addAttribute("readOnly", agent != null && agent.file.parent != folder)
        model.addAttribute("error", error)
        model.addAttribute("notice", notice)
        return "agent"
    }

    private fun find(name: String) = catalogue.agents().firstOrNull { it.name == name } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Agent named $name")

    private fun editable(agent: Agent) = agent.takeIf { it.file.parent == folder }
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "${agent.name} is read-only. Copy it to make one you can edit.")

    /** Written beside it and moved over, so a launch reading it meanwhile never sees half a file. */
    private fun write(file: Path, text: String) {
        Files.createDirectories(folder)
        val next = Files.createTempFile(folder, ".agent", ".tmp")
        next.writeText(text)
        Files.move(next, file, ATOMIC_MOVE, REPLACE_EXISTING)
    }
}

/**
 * The Agents page's form, one field per key of the agent file. [tools] holds each ticked tool with its Limit…, the
 * rule specifiers comma-separated (empty for no limit); [kept] holds the frontmatter keys the form doesn't own.
 */
data class AgentForm(
    val name: String,
    val description: String,
    val cli: String,
    val skills: String,
    val tools: Map<String, String>,
    val otherTools: String,
    val model: String,
    val subagent: Boolean,
    val instructions: String,
    val kept: Map<*, *> = emptyMap<String, Any?>(),
) {
    fun error() = when {
        !Regex("[a-z0-9][a-z0-9-]*").matches(name) -> "A name is lowercase letters, digits and hyphens."
        cli !in CLIS -> "Ants runs only ${CLIS.joinToString(" or ")}, not $cli."
        subagent && cli != "claude" -> "Only a claude Agent can be offered as a Subagent."
        else -> null
    }

    /** The model is none of the dropdown's, so it shows under Other model ID…. */
    val otherModel get() = model.isNotEmpty() && model !in Models.aliases && Models.all.none { it.id == model }

    /** The agent file: Claude Code's format, with `cli`, `skills` and `ants-subagent` for Ants. */
    fun text(): String {
        val meta = linkedMapOf<Any?, Any?>("name" to name)
        if (description.isNotEmpty()) meta["description"] = description
        meta["cli"] = cli
        if (skills.isNotBlank()) meta["skills"] = skills.split(Regex("[\\s,]+")).filter(String::isNotEmpty).joinToString(", ")
        val rules = TOOLS.filter { it in tools }.flatMap { tool -> commas(tools.getValue(tool)).map { "$tool($it)" }.ifEmpty { listOf(tool) } }
        meta["tools"] = (rules + commas(otherTools)).joinToString(", ")
        if (model.isNotEmpty()) meta["model"] = model
        if (subagent) meta["ants-subagent"] = true
        meta.putAll(kept)
        return "---\n" + yaml.dump(meta) + "---\n" + instructions.trim() + "\n"
    }

    companion object {
        val CLIS = listOf("claude", "codex")

        /** The tools offered as tick boxes; any other tool goes under Other tools. */
        val TOOLS = (AgentLauncher.DEFAULT_TOOLS + "Agent").sorted()

        private val KEYS = setOf("name", "description", "cli", "skills", "tools", "model", "ants-subagent")

        private val yaml = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; splitLines = false })

        private fun commas(text: String) = text.split(',').map(String::trim).filter(String::isNotEmpty)

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
                model = param("model").let { if (it == "other") param("model-other") else it },
                subagent = "subagent" in params,
                instructions = params["instructions"].orEmpty(),
            )
        }
    }
}

/** A Claude model from Anthropic's model status table; [retires] is set once it is deprecated. */
class ClaudeModel(val id: String, val retires: String?)

/** The model dropdown's choices: Anthropic's public model status table, without the retired models. */
// ponytail: the table is a copy bundled with Ants (models.md); the update check (#76) is to refresh it.
object Models {
    val all = Models::class.java.getResource("/models.md")!!.readText().lines().mapNotNull {
        Regex("""^\|\s*(claude-\S+)\s*\|\s*(Active|Deprecated)\s*\|[^|]*\|\s*(.*?)\s*\|""").find(it)?.destructured
    }.map { (id, state, retires) -> ClaudeModel(id, retires.takeIf { state == "Deprecated" }) }

    /** Claude Code's aliases, each with what it resolves to on Anthropic's API: the newest active model of its family. */
    val aliases = listOf("fable", "opus", "sonnet", "haiku").associateWith { alias -> all.firstOrNull { "-$alias-" in "${it.id}-" && it.retires == null }?.id }

    /** The model [id], if it is deprecated. */
    fun deprecated(id: String) = all.firstOrNull { it.id == id && it.retires != null }
}
