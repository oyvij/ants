package ants

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer
import org.operaton.bpm.engine.HistoryService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Date
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A file of a Run's tracker, read once: the Spec, `tickets/spec.md` in the Run workspace, named `spec`, or a Ticket,
 * `tickets/issues/<name>.md`, such as `01-hello`. The files are the truth; their `Status:` line is the status.
 */
class Tracked(val run: String, val name: String, val file: Path) {
    val text = file.readText()
    val title = Regex("""^# +(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim() ?: name
    val status = STATUS.find(text)?.groupValues?.get(1)

    /** Done, by its implementer and the review, or by the user. */
    val done get() = status == DONE

    /** Waiting on the review of the changes. */
    val inReview get() = status == IN_REVIEW

    /** Its work is in, under review or done, so the Tickets it blocks may start. */
    val landed get() = inReview || done

    /** The user's to do, never an implementer's. */
    val forHuman get() = status == READY_FOR_HUMAN

    /** Finished one way or another: done, won't be done, or a Spec closed with its Run. */
    val closed get() = status in setOf(DONE, WONTFIX, CLOSED)

    /** A Ticket's number, which `Blocked by:` lines name it by: 1 for `01-hello`. */
    val number = name.substringBefore('-').toIntOrNull()

    /** The numbers its `Blocked by:` line names, as written: `01, 03`. */
    val blockedBy = BLOCKED_BY.find(text)?.groupValues?.get(2)?.let { line -> NUMBER.findAll(line).map { it.value }.toList() }.orEmpty()

    /** What a page sends back to say which version of the file it shows. */
    val version: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    val href get() = "/runs/$run/tickets/$name"

    /** Sets the `Status:` line, adding one under the title when there is none. */
    fun status(status: String) = file.writeText(
        when {
            STATUS.containsMatchIn(text) -> STATUS.replaceFirst(text, "Status: $status")
            text.startsWith("# ") && '\n' in text -> text.replaceFirst("\n", "\n\nStatus: $status\n")
            else -> "Status: $status\n\n$text"
        },
    )

    /** The statuses Ants reads and sets; a Skill may write others, which Ants shows as they are. */
    companion object {
        const val DONE = "done"
        const val IN_REVIEW = "in-review"
        const val READY_FOR_HUMAN = "ready-for-human"
        const val WONTFIX = "wontfix"
        const val CLOSED = "closed"
    }
}

/** A `ready-for-human` Ticket of a live Run, waiting on the user until they mark it done. */
// ponytail: waiting since the file last changed, not since it became ready-for-human; nothing records that moment.
class TicketEntry(ticket: Tracked, process: String) : Entry(
    "ticket-${ticket.run}-${ticket.name}", "[ready-for-human]", process, ticket.title, "Yours to do; mark it done when it is.",
    Date(ticket.file.getLastModifiedTime().toMillis()),
) {
    val run = ticket.run
    val href = ticket.href
}

/** A Spec on the Tickets page, with its Tickets, of the Run [run] of the Process [process]. */
class SpecRow(val run: String, val process: String, val spec: Tracked, val tickets: List<Tracked>) {
    val done = tickets.count { it.done }

    /** Open until the Run ends and closes it, or the user does. */
    val open = !spec.closed
}

/** A `Status:` line, the first. */
private val STATUS = Regex("""^Status:[ \t]*(\S+)""", RegexOption.MULTILINE)
private val BLOCKED_BY = Regex("""^(Blocked by:)(.*)$""", RegexOption.MULTILINE)
private val NUMBER = Regex("""\d+""")

/** Where a Run workspace keeps its Tickets, one file each. */
const val TICKETS = "tickets/issues"

/**
 * The Ticket an item of an Items are Tickets multi-instance names, in the Run workspace [workspace] of the Run [run]: by
 * its path, file name or number, as `tickets/issues/01-hello.md`, `01-hello` or `01`.
 */
fun ticket(workspace: Path, run: String, item: Any?): Tracked? {
    val number = Regex("""^\d+""").find(item.toString().substringAfterLast('/'))?.value?.toInt() ?: return null
    return tickets(workspace, run).firstOrNull { it.number == number }
}

/**
 * Why the instance implementing [ticket] waits before launch, or null when it may launch: `Ready for human` while the
 * Ticket is the user's to do, else `Blocked by 01, 03` while those of its blockers among [tickets] aren't `in-review`
 * or `done` yet.
 */
fun held(ticket: Tracked, tickets: List<Tracked>): String? {
    if (ticket.forHuman) return "Ready for human"
    val open = ticket.blockedBy.filter { n -> tickets.any { it.number == n.toInt() && !it.landed } }
    return if (open.isEmpty()) null else "Blocked by ${open.joinToString()}"
}

/** The Spec in the Run workspace [workspace] of the Run [run], if it has one. */
fun spec(workspace: Path, run: String) = workspace.resolve("tickets/spec.md").takeIf { it.exists() }?.let { Tracked(run, "spec", it) }

/** The Tickets in the Run workspace [workspace] of the Run [run], in file order. */
fun tickets(workspace: Path, run: String): List<Tracked> = workspace.resolve(TICKETS).takeIf { it.isDirectory() }
    ?.listDirectoryEntries("*.md")?.sortedBy { it.name }?.map { Tracked(run, it.nameWithoutExtension, it) }.orEmpty()

/** The Report's Tickets section, for the Run [run] in [workspace]: its Spec, then each Ticket and its status. */
fun ticketsReport(workspace: Path, run: String): String = buildString {
    val spec = spec(workspace, run)
    val tickets = tickets(workspace, run)
    if (spec == null && tickets.isEmpty()) return ""
    append("## Tickets\n\n")
    spec?.let { append("[${it.title}](${it.href})\n\n") }
    for (ticket in tickets) append("- [${ticket.title}](${ticket.href}) · ${ticket.status.orEmpty()}\n")
}

/**
 * Ants' `issue-tracker.md`, telling Skills where the tracker is and how its files look, put in a new Run [workspace]
 * at `docs/agents/issue-tracker.md`, where Matt Pocock's Skills look for it.
 */
fun trackerDoc(workspace: Path) {
    val doc = workspace.resolve("docs/agents").createDirectories().resolve("issue-tracker.md")
    Tickets::class.java.getResourceAsStream("/issue-tracker.md")!!.use { Files.copy(it, doc, REPLACE_EXISTING) }
}

/** As the Run in [workspace] ends, its Spec closes, or becomes `wontfix` when the Run was [cancelled]. */
fun closeSpec(workspace: Path, cancelled: Boolean) = spec(workspace, "")?.status(if (cancelled) Tracked.WONTFIX else Tracked.CLOSED)

/**
 * The Tickets page, listing every Spec across Runs, open first, and a page per Spec and Ticket, rendered GitHub-style.
 */
@Controller
class Tickets(private val history: HistoryService, private val streams: Streams, @Value("\${ants.home}") private val home: Path) {
    private fun spec(run: String) = ants.spec(home.runWorkspace(run), run)

    private fun tickets(run: String) = ants.tickets(home.runWorkspace(run), run)

    @GetMapping("/tickets")
    fun list(model: Model): String {
        val runs = home.resolve("runs").takeIf { it.isDirectory() }?.listDirectoryEntries().orEmpty().map { it.name }
        val specs = runs.mapNotNull { run ->
            val spec = spec(run) ?: return@mapNotNull null
            val process = history.createHistoricProcessInstanceQuery().processInstanceId(run).singleResult() ?: return@mapNotNull null
            SpecRow(run, process.processDefinitionName ?: process.processDefinitionKey, spec, tickets(run))
        }
        model.addAttribute("specs", specs.sortedWith(compareBy<SpecRow> { !it.open }.thenByDescending { it.spec.file.getLastModifiedTime() }))
        return "tickets"
    }

    /** The Spec (`spec`) or Ticket [name] of the Run [run], rendered; the Spec lists its Tickets. */
    @GetMapping("/runs/{run}/tickets/{name}")
    fun page(@PathVariable run: String, @PathVariable name: String, model: Model): String {
        val file = tracked(run, name)
        model.addAttribute("file", file)
        model.addAttribute("article", article(file))
        model.addAttribute("tickets", if (name == "spec") tickets(run) else emptyList())
        return "ticket"
    }

    /** The rendered file, sent on connect and whenever it changes. */
    @GetMapping("/runs/{run}/tickets/{name}/live", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun live(@PathVariable run: String, @PathVariable name: String): SseEmitter {
        tracked(run, name)
        return streams.changes { article(tracked(run, name)) }
    }

    /**
     * Ticks or unticks the checkbox on [line] of the file, if the page showed its [version]; else shows the new
     * version, refusing, since the line may now hold something else.
     */
    @PostMapping("/runs/{run}/tickets/{name}/tick")
    fun tick(@PathVariable run: String, @PathVariable name: String, @RequestParam line: Int, @RequestParam version: String, model: Model): String {
        val file = tracked(run, name)
        if (file.version != version) return changed(file, model)
        val lines = file.text.split("\n").toMutableList()
        val box = CHECKBOX.find(lines.getOrNull(line).orEmpty()) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No checkbox on line $line")
        lines[line] = lines[line].replaceRange(box.groups[1]!!.range, if (box.groupValues[1] == " ") "x" else " ")
        file.file.writeText(lines.joinToString("\n"))
        return "redirect:${file.href}"
    }

    /** The `ready-for-human` Tickets of live Runs, on the Notification list until marked done. */
    fun waiting(): List<TicketEntry> = history.createHistoricProcessInstanceQuery().rootProcessInstances().unfinished().list().flatMap { run ->
        tickets(run.id).filter { it.forHuman }.map { TicketEntry(it, run.processDefinitionName ?: run.processDefinitionKey) }
    }

    /** **Mark done**, on a Ticket that waits on the user. */
    @PostMapping("/runs/{run}/tickets/{name}/done")
    fun done(@PathVariable run: String, @PathVariable name: String): String {
        val file = tracked(run, name)
        file.status(Tracked.DONE)
        return "redirect:${file.href}"
    }

    /** **Edit**: the file's markdown in a text area. */
    @GetMapping("/runs/{run}/tickets/{name}/edit")
    fun edit(@PathVariable run: String, @PathVariable name: String, model: Model): String {
        val file = tracked(run, name)
        return edit(file, file.text, null, model)
    }

    /**
     * Save: writes [text] over the file, if it is still the [version] the user opened; else refuses, showing the
     * new version above the user's text, so a second Save, over the version now shown, is the user's choice.
     */
    @PostMapping("/runs/{run}/tickets/{name}/edit")
    fun save(@PathVariable run: String, @PathVariable name: String, @RequestParam text: String, @RequestParam version: String, model: Model): String {
        val file = tracked(run, name)
        if (file.version != version) return edit(file, text, article(file), model.addAttribute("error", CHANGED))
        file.file.writeText(text.replace("\r\n", "\n")) // a text area sends CRLF
        return "redirect:${file.href}"
    }

    private fun edit(file: Tracked, text: String, newVersion: String?, model: Model): String {
        model.addAttribute("file", file)
        model.addAttribute("text", text)
        model.addAttribute("article", newVersion)
        return "ticket-edit"
    }

    /** The page of [file] as it is now, saying it changed since the user opened it. */
    private fun changed(file: Tracked, model: Model): String {
        model.addAttribute("error", CHANGED)
        return page(file.run, file.name, model)
    }

    private fun tracked(run: String, name: String): Tracked {
        val safe = Regex("""[\w-]+""")
        val file = if (safe.matches(run) && safe.matches(name)) (if (name == "spec") spec(run) else tickets(run).firstOrNull { it.name == name }) else null
        return file ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Ticket $name in Run $run")
    }

    /** The file rendered, its version on it, so a tick says which version it ticked. */
    private fun article(file: Tracked) = """<article id="ticket" data-version="${file.version}">${html(file)}</article>"""

    /**
     * The file as HTML, GitHub-style: tables, `Blocked by:` numbers as links to those Tickets, and each checkbox
     * tickable, carrying the line it is on.
     */
    private fun html(file: Tracked): String {
        val tickets = tickets(file.run)
        val linked = BLOCKED_BY.replace(file.text) { line ->
            line.groupValues[1] + NUMBER.replace(line.groupValues[2]) { number ->
                tickets.firstOrNull { it.number == number.value.toInt() }?.let { "[${number.value}](${it.href})" } ?: number.value
            }
        }
        return renderer.render(parser.parse(linked))
    }

    private companion object {
        const val CHANGED = "This file changed since you opened it, so nothing was saved. This is the new version."

        /** A task list item's checkbox, its mark in group 1. */
        val CHECKBOX = Regex("""^\s*(?:[-*+]|\d+[.)])\s+\[([ xX])]""")
        val extensions = listOf(TaskListItemsExtension.create(), TablesExtension.create())
        val parser: Parser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
        val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true)
            .attributeProviderFactory {
                AttributeProvider { node, _, attributes ->
                    if (node is TaskListItemMarker) {
                        attributes.remove("disabled")
                        attributes["data-line"] = line(node).toString()
                    }
                }
            }.build()

        /** The line [node] starts on: the nearest block that knows it. */
        fun line(node: Node): Int = generateSequence(node) { it.parent }.firstNotNullOf { it.sourceSpans.firstOrNull() }.lineIndex
    }
}
