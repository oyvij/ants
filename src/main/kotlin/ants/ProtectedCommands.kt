package ants

import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.io.path.isDirectory

@Component
class ProtectedCommands(private val settings: Settings, private val mcp: Mcp) {
    /** How long a Protected Command may run before Ants kills it. */
    internal var timeLimit: Duration = Duration.ofMinutes(10)

    fun allowed(tools: List<String>): List<String> {
        val rules = tools.filter { it.substringBefore('(').trim() == AGENT_TOOL }
        val all = prefixes().keys.map { it.joinToString(" ") }
        if (rules.any { '(' !in it }) return all
        val limits = rules.flatMap { it.substringAfter('(').removeSuffix(")").split(',') }.map { Settings.strings(it).orEmpty().joinToString(" ") }
        return all.filter { it in limits }
    }

    fun open(workspace: Path, scripts: McpTools = NO_TOOLS, allowed: List<String> = emptyList()): Pair<String, String> = mcp.open(object : McpTools {
        override val tools = (if (allowed.isEmpty()) emptyList() else listOf(tool(allowed))) + scripts.tools

        override fun call(name: String, arguments: Map<*, *>) = if (name != TOOL) scripts.call(name, arguments) else try {
            val args = (arguments["args"] as? List<*>)?.map(Any?::toString).orEmpty()
            run(workspace, args, arguments["folder"]?.toString() ?: ".", allowed).let { (exit, output) -> "Exit code $exit\n\n$output" to (exit != 0) }
        } catch (refused: Refused) {
            refused.message to true
        }
    })

    /** Ends [token], once its Task's session has ended. */
    fun close(token: String) = mcp.close(token)

    private fun prefixes() = settings.protectedCommands().mapKeys { (prefix) -> Settings.strings(prefix).orEmpty() }.filterKeys { it.isNotEmpty() }

    internal fun run(workspace: Path, args: List<String>, folder: String, allowed: List<String>): Pair<Int, String> {
        val command = args.joinToString(" ")
        val (prefix, labels) = prefixes().filterKeys { args.take(it.size) == it }.maxByOrNull { it.key.size }
            ?: throw Refused(
                if (args.take(2) == listOf("git", "clone")) "`$command` is no Protected Command, so Ants ran nothing. Ants clones the Run's repos itself, on the work branch: name the repo where your Skill says to name repos, else end stuck with the repo in needs. Never ask for `git clone` as a Protected Command."
                else "`$command` is no Protected Command: it gets no Secrets, so Ants ran nothing. Run it with Bash. If Bash refuses it, your Agent isn't allowed to run it: end stuck, saying which Bash rule your Agent needs, such as `Bash(${args.firstOrNull().orEmpty()} *)`.",
            )
        val name = prefix.joinToString(" ")
        if (name !in allowed) throw Refused("`$command` is a Protected Command your Agent may not run, so Ants ran nothing: end stuck, saying your Agent needs `$AGENT_TOOL($name)`.")
        val root = workspace.toRealPath()
        val dir = runCatching { root.resolve(folder).toRealPath() }.getOrNull()?.takeIf { it.startsWith(root) && it.isDirectory() }
            ?: throw Refused("$folder is no folder in the Run workspace, so Ants ran nothing.")
        val names = settings.secrets()
        val values = settings.secretValues()
        val secrets = labels.flatMap { label ->
            val value = values[label] ?: throw Refused("The Secret $label has no value, so Ants ran nothing. End stuck: the user sets it in Settings.")
            names[label].orEmpty().map { it to value }
        }
        val process = try {
            ProcessBuilder(args).directory(dir.toFile()).redirectErrorStream(true).redirectInput(File("/dev/null"))
                .onAllowList(settings, secrets.toMap()).start()
        } catch (e: IOException) {
            throw Refused("Ants can't run `$command`: ${e.message}")
        }
        // ponytail: a child left running that holds the output is never killed, only stopped being read; kill the
        // process group if a build tool's daemon ever does this.
        val (finished, text) = process.within(timeLimit, "(Ants stopped reading: a process `$command` left running still holds its output.)")
        if (!finished) throw Refused("`$command` ran past its time limit of ${timeLimit.short()}, so Ants killed it.")
        return process.exitValue() to mask(text, values.values)
    }

    /** Refused: Ants ran nothing, or killed what it ran, and the Agent reads why. */
    class Refused(override val message: String) : Exception(message)

    companion object {
        private val NO_TOOLS = object : McpTools {
            override val tools = emptyList<Map<String, Any?>>()

            override fun call(name: String, arguments: Map<*, *>) = "No tool $name here." to true
        }

        /** What stands for a Secret's value wherever Ants shows output. */
        const val MASK = "••••"

        /** [text] with each of [secrets] as [MASK], the longest first, so one holding another is masked whole. */
        fun mask(text: String, secrets: Collection<String>) =
            secrets.filter(String::isNotEmpty).sortedByDescending { it.length }.fold(text) { masked, secret -> masked.replace(secret, MASK) }

        const val AGENT_TOOL = "ProtectedCommand"

        private const val TOOL = "run"

        private fun tool(allowed: List<String>) = Mcp.tool(
            TOOL,
            "Runs a Protected Command in a folder of the Run workspace, with the Secrets the user linked to it, which you never see. " +
                "The Protected Commands you may run: ${allowed.joinToString(", ") { "`$it`" }}. " +
                "No shell: run the Protected Command alone, then work with its output using your other tools. Returns the exit code and the output, " +
                "every Secret masked as $MASK. A command no Protected Command starts is refused: run it with Bash.",
            mapOf(
                "args" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "The command and its arguments, one item each, passed as they are, such as [\"yarn\", \"install\", \"--immutable\"].",
                ),
                "folder" to "The folder to run it in, relative to the Run workspace; by default the workspace.",
            ),
            optional = setOf("folder"),
        )
    }
}

fun ProcessBuilder.onAllowList(settings: Settings, variables: Map<String, String>): ProcessBuilder = apply {
    environment().keys.retainAll(settings.env("claude").toSet())
    environment() -= AgentLauncher.PAID
    environment() += variables
}

fun Process.within(limit: Duration, held: String): Pair<Boolean, String> {
    val output = CompletableFuture.supplyAsync({ inputStream.readAllBytes().toString(Charsets.UTF_8) }) { Thread.ofVirtual().start(it) }
    val finished = waitFor(limit.toMillis(), MILLISECONDS)
    if (!finished) (descendants().toList() + toHandle()).forEach(ProcessHandle::destroyForcibly)
    val text = try {
        output.get(5, SECONDS)
    } catch (_: TimeoutException) {
        held
    }
    return finished to text
}

fun Duration.short() = toString().removePrefix("PT").lowercase()
