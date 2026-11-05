package ants

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * The MCP tools an Agent Task Posts to Widgets with (ADR 0032): [LIST] for every claude Agent, and [POST], which claude
 * runs only for an Agent whose tools hold [AGENT_TOOL]; for any other, claude refuses it and the user gets a
 * Permission request. `PostToWidget(<dashboard id>)` limits the Agent to that Dashboard, as `ProtectedCommand(yarn)`
 * limits a Protected Command.
 */
@Component
class WidgetTools(private val dashboards: Dashboards) {
    private val json = JsonMapper.shared()

    /** The Dashboards [tools] let an Agent Post to: null for none, empty for every one. */
    fun allowed(tools: List<String>): List<String>? {
        val rules = tools.filter { it.substringBefore('(').trim() == AGENT_TOOL }
        if (rules.isEmpty()) return null
        if (rules.any { '(' !in it }) return emptyList()
        return rules.flatMap { it.substringAfter('(').removeSuffix(")").split(',') }.map(String::trim).filter(String::isNotEmpty)
    }

    /** The tools for the Task [task] of the Run [run], whose Agent may Post to the Dashboards [allowed] gives. */
    fun of(allowed: List<String>?, run: String, task: String): McpTools = object : McpTools {
        override val tools = listOf(LIST_TOOL, POST_TOOL)

        override fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean> = if (name == LIST) list(allowed) to false
        else post(allowed, arguments["dashboard"]?.toString().orEmpty(), arguments["widget"]?.toString().orEmpty(), arguments["data"], run, task)
    }

    private fun list(allowed: List<String>?): String = json.writeValueAsString(
        dashboards.all().filter { allowed.isNullOrEmpty() || it.id in allowed }.flatMap { dashboard ->
            dashboard.widgets.map { widget ->
                mapOf(
                    "address" to "${dashboard.id}/${widget.id}", "dashboard" to dashboard.id, "dashboardTitle" to dashboard.title,
                    "type" to widget.type.label, "title" to widget.title, "accepts" to widget.type.shape,
                )
            }
        },
    )

    private fun post(allowed: List<String>?, dashboard: String, widget: String, data: Any?, run: String, task: String): Pair<String, Boolean> {
        val address = "$dashboard/$widget"
        if (allowed == null) return "Your Agent may not Post to Widgets, so Ants Posted nothing: end stuck, saying your Agent needs `$AGENT_TOOL($dashboard)`." to true
        if (allowed.isNotEmpty() && dashboard !in allowed) {
            val named = if (allowed.size == 1) "the Dashboard ${allowed.single()}" else "the Dashboards ${allowed.joinToString(", ")}"
            return "Your Agent may Post only to $named, so Ants Posted nothing to $address: end stuck, saying your Agent needs `$AGENT_TOOL($dashboard)`." to true
        }
        return try {
            dashboards.post(address, data, run, task)
            "Posted to $address." to false
        } catch (refused: PostRefused) {
            refused.message!! to true
        }
    }

    companion object {
        /** The Agent's tool that lets it Post, as its file and the Agents page name it. */
        const val AGENT_TOOL = "PostToWidget"

        const val LIST = "ListWidgets"

        /** The tools as claude names them: [LIST] every claude Agent may run; [POST] only one with [AGENT_TOOL]. */
        const val LIST_RULE = "${Mcp.CLAUDE_PREFIX}$LIST"
        const val POST_RULE = "${Mcp.CLAUDE_PREFIX}$AGENT_TOOL"

        private val LIST_TOOL = Mcp.tool(
            LIST,
            "Lists the Widgets you may Post to: each one's address (<dashboard id>/<widget id>), its Dashboard, type and title, " +
                "and the data it accepts. List them before you Post, and Post only to a Widget listed here.",
        )

        private val POST_TOOL = Mcp.tool(
            AGENT_TOOL,
            "Posts data to one Widget of a Dashboard, which shows it with your Run. The data must have the shape ListWidgets gives " +
                "for the Widget; a Post of another shape, to a Widget that is gone, or over 256 KB is refused with what to fix.",
            mapOf(
                "dashboard" to "The Dashboard's id, the part of the Widget's address before the slash.",
                "widget" to "The Widget's id, the part of its address after the slash.",
                "data" to mapOf("type" to "object", "description" to "The Post, in the shape the Widget accepts, such as {\"value\": 42, \"unit\": \"GB\"} for a number Widget."),
            ),
        )
    }
}
