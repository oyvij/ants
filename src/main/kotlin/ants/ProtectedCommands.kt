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

/**
 * Protected Commands (ADR 0015): every claude Agent Task gets one MCP tool, `run(command, folder)`, served on Ants'
 * MCP server ([Mcp]) with a bearer token of its own. Ants runs a command a Protected Command's prefix starts, with no shell, in a
 * folder of the Task's Run workspace, on the CLI's environment allow-list plus the Secrets linked to it, and hands back
 * its exit code and output with every Secret's value masked. The Agent never sees a Secret.
 */
@Component
class ProtectedCommands(private val settings: Settings, private val mcp: Mcp) {
    /** How long a Protected Command may run before Ants kills it. */
    internal var timeLimit: Duration = Duration.ofMinutes(10)

    fun open(workspace: Path, scripts: McpTools = NO_TOOLS): Pair<String, String> = mcp.open(object : McpTools {
        override val tools = listOf(TOOL) + scripts.tools

        override fun call(name: String, arguments: Map<*, *>) = if (name != TOOL["name"]) scripts.call(name, arguments) else try {
            run(workspace, arguments["command"]?.toString().orEmpty(), arguments["folder"]?.toString() ?: ".").let { (exit, output) -> "Exit code $exit\n\n$output" to (exit != 0) }
        } catch (refused: Refused) {
            refused.message to true
        }
    })

    /** Ends [token], once its Task's session has ended. */
    fun close(token: String) = mcp.close(token)

    /**
     * Runs [command] in [folder] of [workspace] and returns its exit code and its output, stdout and stderr together,
     * with every Secret's value as [MASK]. Refuses a command no Protected Command starts, a folder outside the
     * workspace, and one that runs past [timeLimit], which Ants kills with every process under it.
     */
    internal fun run(workspace: Path, command: String, folder: String): Pair<Int, String> {
        // ponytail: quotes group words, but a word half quoted, as in a"b c", splits; a shell's rules if it matters
        val words = WORD.findAll(command).map { match -> match.groups.drop(1).firstNotNullOf { it?.value } }.toList()
        val prefixes = settings.protectedCommands().mapKeys { (prefix) -> Settings.strings(prefix).orEmpty() }
        val labels = prefixes.filterKeys { it.isNotEmpty() && words.take(it.size) == it }.maxByOrNull { it.key.size }?.value
            ?: throw Refused(
                if (words.take(2) == listOf("git", "clone")) "`$command` is no Protected Command, so Ants ran nothing. Ants clones the Run's repos itself, on the work branch: name the repo where your Skill says to name repos, else end stuck with the repo in needs. Never ask for `git clone` as a Protected Command."
                else "`$command` is no Protected Command, so Ants ran nothing. End stuck, with `$command` in needs: the user adds it in Settings, then continues your session, and you ask again.",
            )
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
            ProcessBuilder(words).directory(dir.toFile()).redirectErrorStream(true).redirectInput(File("/dev/null"))
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

        /** A word: double-quoted, single-quoted, or bare. */
        private val WORD = Regex(""""([^"]*)"|'([^']*)'|(\S+)""")

        private val TOOL = Mcp.tool(
            "run",
            "Runs a Protected Command, such as `yarn install`, in a folder of the Run workspace, with the Secrets the user " +
                "linked to it, which you never see. No shell: the command is split into words. Returns the exit code and the output, " +
                "every Secret masked as $MASK. A command that is no Protected Command is refused: then end stuck with it in needs.",
            mapOf(
                "command" to "The command and its arguments, such as `yarn install --immutable`.",
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
