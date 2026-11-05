package ants

import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper

/**
 * The tools the Process Builder, the Template Builder and the script helper change Dashboards with (ADR 0032):
 * `dashboards` lists them, and each of [CHANGES] is refused unless the helper wrote a suggestion and the user has sent
 * a message since ([PageChat.replied]). Only Dashboard changes wait so; Process editing doesn't.
 */
@Component
class DashboardTools(private val dashboards: Dashboards, private val usedBy: UsedBy, private val json: JsonMapper) : HelperTools {
    override val instructions = INSTRUCTIONS
    override val lines = LINES
    override val tools = TOOLS
    override val gated = CHANGES

    override fun call(chat: PageChat, name: String, arguments: Map<*, *>): Pair<String, Boolean> = try {
        fun argument(key: String) = arguments[key]?.toString()?.takeIf(String::isNotBlank) ?: throw Refused("$name needs $key.")
        if (name in CHANGES && !chat.replied(name)) throw Refused(UNANSWERED)
        when (name) {
            "dashboards" -> list()
            "create_dashboard" -> dashboards.create().let { dashboards.rename(it.id, argument("title")) }.let { "Created the Dashboard ${it.id}, ${it.title}." }
            "rename_dashboard" -> dashboards.rename(argument("dashboard"), argument("title")).let { "The Dashboard ${it.id} is now titled ${it.title}." }
            "delete_dashboard" -> argument("dashboard").let { id ->
                val broke = broken(usedBy.dashboard(id))
                dashboards.delete(id)
                "Deleted the Dashboard $id.$broke"
            }
            "add_widget" -> {
                val dashboard = argument("dashboard")
                val type = WidgetType.of(argument("type")) ?: throw Refused("No Widget type ${arguments["type"]}: it is one of ${WidgetType.entries.joinToString(", ") { it.label }}.")
                val kind = arguments["kind"]?.toString()?.let { ChartKind.of(it) ?: throw Refused("No chart kind $it: it is one of ${ChartKind.entries.joinToString(", ") { k -> k.label }}.") }
                if (kind != null && type != WidgetType.CHART) throw Refused("Only a chart Widget takes a kind.")
                val widget = dashboards.addWidget(dashboard, type, kind ?: ChartKind.LINE, arguments["id"]?.toString()?.trim()?.takeIf(String::isNotEmpty), arguments["title"]?.toString())
                "Added the ${type.label} Widget ${widget.title} at ${WidgetAddress(dashboard, widget.id)}; it accepts ${type.shape}."
            }
            "rename_widget" -> dashboards.renameWidget(argument("dashboard"), argument("widget"), argument("title")).let { "The Widget ${WidgetAddress(argument("dashboard"), it.id)} is now titled ${it.title}." }
            "remove_widget" -> WidgetAddress(argument("dashboard"), argument("widget")).let { address ->
                val broke = broken(usedBy.widget(address))
                dashboards.removeWidget(address.dashboard, address.widget)
                "Removed the Widget $address.$broke"
            }
            else -> throw Refused("No tool $name here.")
        } to false
    } catch (e: Exception) {
        ((e as? ResponseStatusException)?.reason ?: e.message ?: e.toString()) to true
    }

    private fun list(): String = json.writeValueAsString(dashboards.all().map { dashboard ->
        val used = usedBy.widgets(dashboard.id, dashboard.widgets.map { it.id })
        mapOf(
            "id" to dashboard.id, "title" to dashboard.title, "usedBy" to usedBy.dashboard(dashboard.id).named,
            "widgets" to dashboard.widgets.map { widget ->
                WidgetTools.described(dashboard, widget) + ("id" to widget.id) +
                    listOfNotNull(("kind" to widget.kind.label).takeIf { widget.type == WidgetType.CHART }) +
                    ("usedBy" to used.getValue(widget.id).named)
            },
        )
    })

    /** What a delete or remove answers after its first sentence: what still names the Dashboard or Widget, and so breaks. */
    private fun broken(dependents: Dependents): String {
        val scripts = if (dependents.scripts.isEmpty()) "" else " The Scripts ${dependents.scripts.joinToString(", ")} still list it, so Publish refuses them until their Widgets are fixed."
        val processes = dependents.processNames.takeIf { it.isNotEmpty() }?.let { " The Processes ${it.joinToString(", ")} have Agent Tasks that Post to it, and Ants will refuse their Posts." }.orEmpty()
        return scripts + processes
    }

    companion object {
        /** The tools that change a Dashboard, which wait for the user's reply. */
        private val CHANGES = setOf("create_dashboard", "rename_dashboard", "delete_dashboard", "add_widget", "rename_widget", "remove_widget")

        /** What the helpers are told about Dashboards, after their own instructions. */
        private val INSTRUCTIONS = DashboardTools::class.java.getResource("/dashboard-changes.md")!!.readText()

        private const val UNANSWERED = "Ants changed nothing: a Dashboard changes only after you suggest it in one sentence and the user has replied since. " +
            "Write the suggestion and end your turn. Once the user agrees, make the changes before you write anything, as anything you write waits for a reply again."

        private const val DASHBOARD = "The Dashboard's id, such as d-k3x9q2, as `dashboards` lists it."
        private const val WIDGET = "The Widget's id, the part of its address after the slash."

        private val TOOLS = listOf(
            Mcp.tool("dashboards", "The user's Dashboards in their order: each one's id, title, what Posts to any of its Widgets (usedBy: each Script that lists one and each Process whose Agent Tasks may Post only to Dashboards including it, such as Script measure-disk), and its Widgets, " +
                "each with its address (<dashboard id>/<widget id>, which a Script lists and Posts to), id, type, title, the data it accepts, a chart's kind, " +
                "and what Posts to it (usedBy)."),
            Mcp.tool("create_dashboard", "Creates a Dashboard, at the bottom of the user's Dashboards, and gives its id. Only after the user replied to your suggestion.",
                mapOf("title" to "Its title, such as Home lab.")),
            Mcp.tool("rename_dashboard", "Renames a Dashboard; its id never changes. Only after the user replied to your suggestion.",
                mapOf("dashboard" to DASHBOARD, "title" to "Its new title.")),
            Mcp.tool("delete_dashboard", "Deletes a Dashboard with its Widgets and their data. Only after the user replied to a suggestion naming what it breaks (usedBy).",
                mapOf("dashboard" to DASHBOARD)),
            Mcp.tool("add_widget", "Adds a Widget at the bottom of a Dashboard and gives its address. Only after the user replied to your suggestion.",
                mapOf(
                    "dashboard" to DASHBOARD,
                    "type" to mapOf("type" to "string", "enum" to WidgetType.entries.map { it.label },
                        "description" to "Its type, which fixes the data it accepts: " + WidgetType.entries.joinToString("; ") { "${it.label} takes ${it.shape}" } + "."),
                    "title" to "Its title, such as Disk used.",
                    "id" to "A readable id for it, such as disk: lowercase letters, digits and -. It never changes and is never used again on that Dashboard. " +
                        "Left out, it gets a random one.",
                    "kind" to mapOf("type" to "string", "enum" to ChartKind.entries.map { it.label }, "description" to "A chart's kind, line unless given; only a chart takes one."),
                ), optional = setOf("title", "id", "kind")),
            Mcp.tool("rename_widget", "Renames a Widget; its id and address never change. Only after the user replied to your suggestion.",
                mapOf("dashboard" to DASHBOARD, "widget" to WIDGET, "title" to "Its new title.")),
            Mcp.tool("remove_widget", "Removes a Widget and its data; its id is never used again. Only after the user replied to a suggestion naming what it breaks (usedBy).",
                mapOf("dashboard" to DASHBOARD, "widget" to WIDGET)),
        )

        /** How the dialog words each tool's call. */
        private val LINES: Map<String, (Map<*, *>) -> String> = mapOf(
            "dashboards" to { _ -> "read the Dashboards" },
            "create_dashboard" to { a -> "created the Dashboard <i>${esc(a["title"])}</i>" },
            "rename_dashboard" to { a -> "renamed the Dashboard <i>${esc(a["dashboard"])}</i> to <i>${esc(a["title"])}</i>" },
            "delete_dashboard" to { a -> "deleted the Dashboard <i>${esc(a["dashboard"])}</i>" },
            "add_widget" to { a -> "added the ${esc(a["type"])} Widget <i>${esc(a["title"] ?: a["id"] ?: "")}</i> to <i>${esc(a["dashboard"])}</i>" },
            "rename_widget" to { a -> "renamed the Widget <i>${esc(a["dashboard"])}/${esc(a["widget"])}</i> to <i>${esc(a["title"])}</i>" },
            "remove_widget" to { a -> "removed the Widget <i>${esc(a["dashboard"])}/${esc(a["widget"])}</i>" },
        )

        private fun esc(value: Any?) = org.springframework.web.util.HtmlUtils.htmlEscape(value?.toString().orEmpty())
    }
}
