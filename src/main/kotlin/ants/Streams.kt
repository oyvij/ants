package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Every SSE stream the pages follow: a virtual thread polls for what changed and sends it, until the page goes away,
 * the stream times out after a minute (EventSource reconnects), or Ants shuts down, which would otherwise wait for it.
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

    /** Sends what [render] gives on connect and whenever it changes, polling every [every]. */
    fun changes(every: Duration = Duration.ofSeconds(1), render: () -> String): SseEmitter {
        var last: String? = null
        return poll(every) { listOfNotNull(render().takeIf { it != last }?.also { last = it }) }
    }

    /**
     * Sends [template] rendered with [params] on connect and whenever it changes. A [csrf] token, for a form in it, is
     * passed as `csrf`, read now, while the request lives.
     */
    fun rendered(template: String, csrf: CsrfToken? = null, params: () -> Map<String, Any?>): SseEmitter {
        val token = csrf?.let { DefaultCsrfToken(it.headerName, it.parameterName, it.token) }
        return changes {
            val all = if (token == null) params() else params() + ("csrf" to token)
            StringOutput().also { templates.render(template, all, it) }.toString().trim()
        }
    }

    /** Sends each piece of data [changed] gives, as an event named [name], polling every [every]. */
    fun poll(every: Duration, name: String? = null, changed: () -> List<String>): SseEmitter = SseEmitter(60_000).also { emitter ->
        val open = AtomicBoolean(true)
        emitter.onCompletion { open.set(false) }
        emitter.onError { open.set(false) }
        Thread.ofVirtual().start {
            try {
                while (open.get() && !closing) {
                    for (data in changed()) emitter.send(SseEmitter.event().apply { if (name != null) name(name) }.data(line(data)))
                    Thread.sleep(every)
                }
                emitter.complete()
            } catch (_: Exception) {
                // the page went away or the stream timed out
            }
        }
    }

    // One data line: a newline as a character reference still reads as one in HTML, in a <pre> too.
    private fun line(data: String) = data.replace("\n", "&#10;")
}
