package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpStatus.UNAUTHORIZED
import org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who Posts with a token: the [script] that a Script Task, [task], runs in the Run [run]. */
data class Sender(val script: Script, val run: String, val task: String)

/**
 * Where `ants.post` sends a Script's Posts (ADR 0032): one token per run of a Script in a Script Task, given before it
 * starts and dropped when it ends. A Post to a Widget the Script doesn't list is refused here; the rest is [Dashboards.post]'s.
 */
@RestController
class Posts(private val dashboards: Dashboards, private val server: WebServerApplicationContext, @Value("\${server.address:}") private val address: String) {
    private val senders = ConcurrentHashMap<String, Sender>()

    /** A token for [sender], until [close], and the URL its Posts go to. */
    fun open(sender: Sender): Pair<String, String> = UUID.randomUUID().toString().also { senders[it] = sender } to localUrl(server, address, "/posts")

    fun close(token: String) {
        senders -= token
    }

    /** A Post, `{address, data}`: answered 200, or 422 with the message the Script's `ants.post` throws. */
    @PostMapping("/posts")
    fun post(@RequestHeader(AUTHORIZATION, required = false) authorization: String?, @RequestBody post: Map<String, Any?>): ResponseEntity<Map<String, String>> {
        val sender = authorization?.removePrefix("Bearer ")?.let(senders::get)
            ?: return ResponseEntity.status(UNAUTHORIZED).body(mapOf("error" to "No running Script holds this token."))
        val address = post["address"]?.toString().orEmpty()
        return try {
            if (address !in sender.script.widgets) throw PostRefused("The Script ${sender.script.name} doesn't list the Widget $address under its Widgets, so it may not Post to it.")
            dashboards.post(address, post["data"], sender.run, sender.task)
            ResponseEntity.ok(mapOf("posted" to address))
        } catch (refused: PostRefused) {
            ResponseEntity.status(UNPROCESSABLE_CONTENT).body(mapOf("error" to refused.message!!))
        }
    }
}
