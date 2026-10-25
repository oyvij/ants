package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.http.HttpHeaders.ALLOW
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED
import org.springframework.http.HttpStatus.UNAUTHORIZED
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The tools one token opens on `/mcp`: a claude Agent Task's `run` (ADR 0015), or a modeler page's (ADR 0019). */
interface McpTools {
    /** Each tool as `tools/list` gives it: name, description and inputSchema. */
    val tools: List<Map<String, Any?>>

    /** Calls the tool [name], one of [tools]: its text, and whether it failed. */
    fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean>

    /** False once the token is dead, as a page's is when the page has gone. */
    val alive: Boolean get() = true
}

/**
 * Ants' MCP server, `ants` to claude (so its tools are `mcp__ants__<tool>`): JSON-RPC over HTTP, one JSON response
 * per request, each token opening only the tools it was given.
 */
@RestController
class Mcp(private val server: WebServerApplicationContext, @Value("\${server.address:}") private val address: String) {
    private val tokens = ConcurrentHashMap<String, McpTools>()

    /** Gives [tools] a token, until [close], and returns it with the `--mcp-config` that reaches them. */
    fun open(tools: McpTools): Pair<String, String> {
        val token = UUID.randomUUID().toString()
        tokens[token] = tools
        val host = (address.takeUnless { it.isEmpty() || InetAddress.getByName(it).isAnyLocalAddress } ?: "127.0.0.1").let { if (':' in it) "[$it]" else it }
        val ants = mapOf("type" to "http", "url" to "http://$host:${server.webServer!!.port}/mcp", "headers" to mapOf(AUTHORIZATION to "Bearer $token"))
        return token to json.writeValueAsString(mapOf("mcpServers" to mapOf("ants" to ants)))
    }

    fun close(token: String) {
        tokens -= token
    }

    /**
     * No stream from server to client: MCP's answer to a GET is 405. Without it, Spring's error page redirects to the
     * login and claude asks again every second.
     */
    @GetMapping("/mcp")
    fun stream(): ResponseEntity<Void> = ResponseEntity.status(METHOD_NOT_ALLOWED).header(ALLOW, "POST").build()

    @PostMapping("/mcp")
    fun mcp(@RequestHeader(AUTHORIZATION, required = false) authorization: String?, @RequestBody request: Map<String, Any?>): ResponseEntity<Any> {
        val id = request["id"]
        val token = authorization?.removePrefix("Bearer ")
        val tools = token?.let(tokens::get)?.takeIf { it.alive || run { close(token); false } }
            ?: return ResponseEntity.status(UNAUTHORIZED).body(error(id, "No live Agent Task or modeler page holds this token."))
        if (id == null) return ResponseEntity.accepted().build() // a notification, such as notifications/initialized
        val params = request["params"] as? Map<*, *> ?: emptyMap<String, Any>()
        val result = when (request["method"]) {
            "initialize" -> mapOf(
                "protocolVersion" to (params["protocolVersion"] ?: "2025-06-18"),
                "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                "serverInfo" to mapOf("name" to "ants", "version" to "1"),
            )
            "ping" -> emptyMap<String, Any>()
            "tools/list" -> mapOf("tools" to tools.tools)
            "tools/call" -> {
                val name = params["name"]?.toString()
                val (text, failed) = if (tools.tools.none { it["name"] == name }) "No tool $name here." to true
                else tools.call(name!!, params["arguments"] as? Map<*, *> ?: emptyMap<String, Any>())
                mapOf("content" to listOf(mapOf("type" to "text", "text" to text)), "isError" to failed)
            }
            else -> return ResponseEntity.ok(error(id, "No method ${request["method"]}.", -32601))
        }
        return ResponseEntity.ok(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    }

    companion object {
        const val CLAUDE_PREFIX = "mcp__ants__"

        private val json = JsonMapper.shared()

        private fun error(id: Any?, message: String, code: Int = -32001) = mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

        /**
         * A tool's `tools/list` entry, its [arguments] by name: a string says what a string argument is, a map is the
         * argument's JSON schema. [optional] ones may be left out.
         */
        fun tool(name: String, description: String, arguments: Map<String, Any> = emptyMap(), optional: Set<String> = emptySet()) = mapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to arguments.mapValues { (_, what) -> what as? Map<*, *> ?: mapOf("type" to "string", "description" to what) },
                "required" to (arguments.keys - optional).toList(),
            ),
        )
    }
}
