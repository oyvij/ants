package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import org.operaton.bpm.engine.HistoryService
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** One thing a page follows: each piece of data [changed] gives is sent as an event named [name], polled every [every]. */
class Part(val name: String, val every: Duration = Duration.ofSeconds(1), val changed: () -> List<String>)

/**
 * The SSE stream a page follows: a virtual thread polls its [Part]s for what changed and sends it, until the page goes
 * away, the stream times out after a minute (EventSource reconnects), or Ants shuts down, which would otherwise wait for it.
 */
// ponytail: each open stream polls on its own thread; an in-process notification once pages are many
@Component
class Streams(private val templates: TemplateEngine) {
    @Volatile
    private var closing = false

    @EventListener(ContextClosedEvent::class)
    fun close() {
        closing = true
    }

    /** Sends what [render] gives as [name] on connect and whenever it changes. */
    fun changes(name: String, render: () -> String): Part {
        var last: String? = null
        return Part(name) { listOfNotNull(render().takeIf { it != last }?.also { last = it }) }
    }

    /**
     * Sends [template] rendered with [params] as [name] on connect and whenever it changes. A [csrf] token, for a form in
     * it, is passed as `csrf`, read now, while the request lives.
     */
    fun rendered(name: String, template: String, csrf: CsrfToken? = null, params: () -> Map<String, Any?>): Part {
        val token = csrf?.let { DefaultCsrfToken(it.headerName, it.parameterName, it.token) }
        return changes(name) {
            val all = if (token == null) params() else params() + ("csrf" to token)
            StringOutput().also { templates.render(template, all, it) }.toString().trim()
        }
    }

    /** One stream carrying all of [parts], each polled when it is due. */
    fun stream(parts: List<Part>): SseEmitter = SseEmitter(60_000).also { emitter ->
        val open = AtomicBoolean(true)
        emitter.onCompletion { open.set(false) }
        emitter.onError { open.set(false) }
        Thread.ofVirtual().start {
            try {
                val due = LongArray(parts.size)
                var sent = System.nanoTime()
                while (open.get() && !closing) {
                    parts.forEachIndexed { i, part ->
                        if (System.nanoTime() - due[i] < 0) return@forEachIndexed
                        due[i] = System.nanoTime() + part.every.toNanos()
                        for (data in part.changed()) emitter.send(SseEmitter.event().name(part.name).data(line(data))).also { sent = System.nanoTime() }
                    }
                    // Only a write finds that the page has gone, so a quiet stream writes a comment now and then.
                    if (System.nanoTime() - sent > QUIET) emitter.send(SseEmitter.event().comment("")).also { sent = System.nanoTime() }
                    Thread.sleep(parts.minOf { it.every })
                }
                emitter.complete()
            } catch (_: Exception) {
                // the page went away or the stream timed out
            }
        }
    }

    // One data line: a newline as a character reference still reads as one in HTML, in a <pre> too.
    private fun line(data: String) = data.replace("\n", "&#10;")

    private companion object {
        val QUIET = Duration.ofSeconds(2).toNanos()
    }
}

/**
 * The one SSE connection a page holds, since a browser opens at most 6 per host, shared by all its tabs and the
 * installed app: the sidebar's events, `count`, `runs`, `pauses` and `update`, on every page, and an event for each part
 * of the page the query names: `home`; `run=<id>`, with `drawer=<task>` while its Task drawer is open;
 * `transcript=<run>/<task>&from=<byte>`; `ticket=<run>/<name>`.
 */
@Controller
class Live(
    private val streams: Streams,
    private val notifications: Notifications,
    private val launcher: AgentLauncher,
    private val history: HistoryService,
    private val home: Home,
    private val updates: Updates,
    private val runs: Runs,
    private val transcripts: Transcripts,
    private val tickets: Tickets,
) {
    @GetMapping("/live", produces = [TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun live(@RequestParam query: Map<String, String>, csrf: CsrfToken): SseEmitter = streams.stream(
        listOfNotNull(
            notifications.count(),
            streams.rendered("runs", "running.kte") { mapOf("working" to launcher.running, "live" to history.liveRuns()) },
            home.pauses(csrf),
            updates.notice(csrf),
            home.live().takeIf { "home" in query },
            query["run"]?.let(runs::live),
            query["drawer"]?.let { runs.drawer(query.getValue("run"), it, csrf) },
            query["transcript"]?.let(::inRun)?.let { (run, task) -> transcripts.events(run, task, query.getValue("from").toLong()) },
            query["ticket"]?.let(::inRun)?.let { (run, name) -> tickets.live(run, name) },
        ),
    )

    /** A part's `<run>/<x>`, as the transcript and ticket parts name theirs: the run, and what in it. */
    private fun inRun(value: String) = value.substringBefore('/') to value.substringAfter('/')
}
