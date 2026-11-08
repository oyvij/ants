package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper

/**
 * A Widget as its Dashboard's page draws it: the [posts] it holds, oldest first, its latest [post], the name of the
 * Run that sent that, and the Scripts and Processes that Post to it, [usedBy].
 */
class WidgetView(val dashboard: String, val widget: Widget, val posts: List<Post>, val runName: String?, val usedBy: Dependents = Dependents(emptyList())) {
    val post = posts.lastOrNull()

    /** Where a Script or an Agent Posts to it. */
    val address get() = WidgetAddress(dashboard, widget.id)

    /**
     * What removing it breaks, in the sentence its remove confirm ends with, such as "The Script measure Posts to it and
     * will break."; empty when nothing uses it.
     */
    val breaks: String get() {
        val processes = usedBy.processNames
        val kinds = listOf("Script" to usedBy.scripts, "Process" to processes).filter { it.second.isNotEmpty() }
            .map { (kind, names) -> "the ${if (names.size == 1) kind else plural(kind)} ${names.joinToString(", ")}" }
        if (kinds.isEmpty()) return ""
        val one = usedBy.scripts.size + processes.size == 1
        return kinds.joinToString(" and ").replaceFirstChar(Char::uppercase) + " Post${if (one) "s" else ""} to it and will break."
    }

    private fun plural(kind: String) = if (kind == "Process") "Processes" else "${kind}s"

    /** The number at [key] of the latest Post, without a needless `.0`. */
    fun number(key: String): String? = post?.data?.get(key)?.let(::shown)

    private fun shown(value: Any): String = when (value) {
        is Double -> if (value % 1.0 == 0.0 && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    /** A number Widget's change: the Post's own delta, or else its change since the Post before it. */
    val delta: Double? get() {
        val data = post?.data ?: return null
        (data["delta"] as? Number)?.let { return it.toDouble() }
        val before = posts.dropLast(1).lastOrNull()?.data?.get("value") as? Number ?: return null
        return (data["value"] as? Number)?.let { java.math.BigDecimal(it.toString()) - java.math.BigDecimal(before.toString()) }?.toDouble()
    }

    /** [delta] as shown, without its sign, which its arrow gives. */
    val deltaShown get() = delta?.let { shown(Math.abs(it)) }

    /** A progress Widget's share done, 0 to 100. */
    val percent: Int get() {
        val done = (post?.data?.get("done") as? Number)?.toDouble() ?: return 0
        val total = (post?.data?.get("total") as? Number)?.toDouble()?.takeIf { it > 0 } ?: return 0
        return Math.round(100 * done / total).toInt().coerceIn(0, 100)
    }

    /** A status or list Widget's items. */
    val items: List<Map<*, *>> get() = (post?.data?.get("items") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    /** A log Widget's newest [LINES] lines, oldest first, each with when it came. */
    val lines: List<Post> get() = posts.takeLast(LINES)

    /** A chart's points, as JSON for the page to draw: each point's x, y and series, oldest first, text without its Markdown. */
    val points: String get() = JSON.writeValueAsString(posts.map { post -> post.data.mapValues { (_, value) -> if (value is String) Transcript.plain(value) else value } })

    /** A custom chart's Vega-Lite spec, as JSON for the page to draw. */
    val spec: String get() = JSON.writeValueAsString(post?.data?.get("spec"))

    val at get() = post?.at?.let(::shortTime)

    fun time(post: Post) = shortTime(post.at)

    companion object {
        /** The lines a log Widget draws, its newest; it keeps more, for its days. */
        const val LINES = 200

        private val JSON = JsonMapper.shared()
    }
}

/**
 * The Dashboards pages: **+ Add Dashboard** in the menu, a Dashboard's page with its title saved as typed, its grid of
 * Widgets, and Delete. `/dashboards` opens the Dashboard viewed last, kept in the `dashboard` cookie.
 */
@Controller
class DashboardsPage(private val dashboards: Dashboards, private val names: RunNames, private val templates: TemplateEngine, private val streams: Streams, private val usedBy: UsedBy) {
    private val json = JsonMapper.shared()

    @GetMapping("/dashboards")
    fun dashboards(@CookieValue(LAST, required = false) last: String?, model: Model): String {
        val open = last?.let(dashboards::find) ?: dashboards.all().firstOrNull()
        if (open != null) return "redirect:/dashboards/${open.id}"
        model.addAttribute("dashboard", null)
        model.addAttribute("widgets", emptyList<WidgetView>())
        return "dashboards"
    }

    @GetMapping("/dashboards/{id}")
    fun dashboard(@PathVariable id: String, model: Model, response: HttpServletResponse): String {
        val dashboard = dashboards.get(id)
        model.addAttribute("dashboard", dashboard)
        model.addAttribute("widgets", views(dashboard))
        model.addAttribute("types", WidgetType.entries)
        response.addCookie(Cookie(LAST, id).apply { path = "/"; maxAge = YEAR; isHttpOnly = true })
        return "dashboards"
    }

    /** **+ Add Dashboard**: an "Untitled dashboard", opened with its title selected (`?new`). */
    @PostMapping("/dashboards")
    fun create() = "redirect:/dashboards/${dashboards.create().id}?new"

    /** The menu's Dashboards dragged into a new order, [order] their ids comma-separated, kept. */
    @PostMapping("/dashboards/order")
    @ResponseBody
    fun reorder(@RequestParam order: String): Map<String, List<String>> {
        dashboards.reorder(order.split(',').map(String::trim).filter(String::isNotEmpty))
        return mapOf("order" to dashboards.all().map { it.id })
    }

    /** The title as typed, saved; answers the title kept, which an emptied one makes "Untitled dashboard". */
    @PostMapping("/dashboards/{id}/title")
    @ResponseBody
    fun rename(@PathVariable id: String, @RequestParam title: String) = mapOf("title" to dashboards.rename(id, title).title)

    @PostMapping("/dashboards/{id}/delete")
    fun delete(@PathVariable id: String, @RequestParam confirm: String?, model: Model): String {
        val dashboard = dashboards.get(id)
        if (confirm == null) return confirm(model, "Delete the Dashboard ${dashboard.title}?", usedBy.dashboard(id), "/dashboards/$id/delete", emptyMap(), "/dashboards/$id")
        dashboards.delete(id)
        return "redirect:/dashboards"
    }

    /** **+ Widget**: a new Widget of [type], answered with its id, its grid item and its Posting panel row for the page to add. */
    @PostMapping("/dashboards/{id}/widgets")
    @ResponseBody
    fun addWidget(@PathVariable id: String, @RequestParam type: String, @RequestParam kind: String?): Map<String, String> {
        val widget = dashboards.addWidget(id, WidgetType.of(type) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No Widget type $type"), kind(kind ?: "line"))
        val view = WidgetView(id, widget, emptyList(), null, usedBy.widget(WidgetAddress(id, widget.id)))
        return mapOf(
            "id" to widget.id, "html" to render("widget.kte", mapOf("view" to view)), "body" to render("widgetbody.kte", mapOf("view" to view)),
            "row" to render("address.kte", mapOf("view" to view)),
        )
    }

    /** A Widget's title as typed, saved; answers the title kept. */
    @PostMapping("/dashboards/{id}/widgets/{widget}/title")
    @ResponseBody
    fun renameWidget(@PathVariable id: String, @PathVariable widget: String, @RequestParam title: String) = mapOf("title" to dashboards.renameWidget(id, widget, title).title)

    /** Removes a Widget, once the page has asked the user. */
    @PostMapping("/dashboards/{id}/widgets/{widget}/delete")
    @ResponseBody
    fun removeWidget(@PathVariable id: String, @PathVariable widget: String): Map<String, String> {
        dashboards.removeWidget(id, widget)
        return mapOf("removed" to widget)
    }

    /** The grid as the user left it: `[{id, x, y, w, h}, …]` for each Widget that moved. */
    @PostMapping("/dashboards/{id}/layout")
    @ResponseBody
    fun layout(@PathVariable id: String, @RequestParam layout: String): Map<String, String> {
        val placed = json.readValue(layout, List::class.java).filterIsInstance<Map<*, *>>().map {
            fun int(key: String) = (it[key] as? Number)?.toInt() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A placement needs $key")
            Placement(it["id"].toString(), int("x"), int("y"), int("w"), int("h"))
        }
        dashboards.layout(id, placed)
        return mapOf("saved" to id)
    }

    /** An open Dashboard's Widgets, live: each one's body, by its id, whenever a Post or a rename changes any. */
    fun live(id: String): Part = streams.changes("widgets") {
        val dashboard = dashboards.find(id) ?: return@changes "{}"
        json.writeValueAsString(dashboard.widgets.associate { it.id to render("widgetbody.kte", mapOf("view" to view(id, it))) })
    }

    /** **Clear data**: empties a Widget, which keeps its id and title; answers its body, waiting for its next Post. */
    @PostMapping("/dashboards/{id}/widgets/{widget}/clear")
    @ResponseBody
    fun clear(@PathVariable id: String, @PathVariable widget: String): Map<String, String> {
        dashboards.clear(id, widget)
        return mapOf("body" to render("widgetbody.kte", mapOf("view" to view(id, dashboards.get(id).widget(widget)!!))))
    }

    /** The days a log Widget keeps its Posts, as the user set them in edit mode; answers the days kept. */
    @PostMapping("/dashboards/{id}/widgets/{widget}/keep")
    @ResponseBody
    fun keep(@PathVariable id: String, @PathVariable widget: String, @RequestParam days: Int) = mapOf("keep" to dashboards.keepFor(id, widget, days).keep)

    /** A chart's kind as the user picked it in edit mode; answers its body, drawn as that kind. */
    @PostMapping("/dashboards/{id}/widgets/{widget}/kind")
    @ResponseBody
    fun drawAs(@PathVariable id: String, @PathVariable widget: String, @RequestParam kind: String): Map<String, String> {
        val chart = dashboards.drawAs(id, widget, kind(kind))
        return mapOf("kind" to chart.kind.label, "body" to render("widgetbody.kte", mapOf("view" to view(id, chart))))
    }

    private fun kind(label: String) = ChartKind.of(label) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No chart kind $label")

    /** Each Widget of [dashboard] as its page draws it, with what uses it. */
    private fun views(dashboard: Dashboard): List<WidgetView> {
        val used = usedBy.widgets(dashboard.id, dashboard.widgets.map { it.id })
        return dashboard.widgets.map { view(dashboard.id, it, used.getValue(it.id)) }
    }

    /** The Widget [widget] as its page draws it; its body alone needs no [used]. */
    private fun view(dashboard: String, widget: Widget, used: Dependents = Dependents(emptyList())): WidgetView {
        val posts = dashboards.posts(dashboard, widget)
        return WidgetView(dashboard, widget, posts, posts.lastOrNull()?.let { runCatching { names.name(it.run) }.getOrNull() }, used)
    }

    private fun render(template: String, params: Map<String, Any?>) = StringOutput().also { templates.render(template, params, it) }.toString().trim()

    companion object {
        /** The cookie naming the Dashboard viewed last. */
        const val LAST = "dashboard"

        /** The cookie keeping the menu's Dashboards toggle open (`open`) or closed, so every page draws the menu as it was. */
        const val MENU = "dashboards-menu"

        private const val YEAR = 365 * 24 * 3600
    }
}
