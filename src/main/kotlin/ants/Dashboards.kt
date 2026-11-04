package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** A Dashboard (ADR 0032): its fixed [id], which its page address uses, its [title], its name, and its [widgets]. */
data class Dashboard(
    val id: String,
    val title: String,
    val created: Instant,
    val widgets: List<Widget> = emptyList(),
    /** The ids of its removed Widgets, which no new Widget gets, so their old senders' Posts can't land on one. */
    val retired: List<String> = emptyList(),
) {
    fun widget(id: String) = widgets.firstOrNull { it.id == id }
}

/** A Widget on a Dashboard: its fixed [id], its [type], its [title], and its place on the grid. */
data class Widget(val id: String, val type: WidgetType, val title: String, val x: Int, val y: Int, val w: Int, val h: Int)

/** A Widget's type: the data a Post to it holds, as [shape] says it, and the one plain [line] **+ Widget** shows. */
enum class WidgetType(val shape: String, val line: String) {
    NUMBER("{value: number, unit?: string, delta?: number}", "One number, with its change since the last Post") {
        override fun problems(data: Map<*, *>) = listOfNotNull(
            "value is missing".takeIf { "value" !in data },
            "value is not a number".takeIf { "value" in data && data["value"] !is Number },
            "unit is not text".takeIf { data["unit"] != null && data["unit"] !is String },
            "delta is not a number".takeIf { data["delta"] != null && data["delta"] !is Number },
        ) + (data.keys.map { "$it" } - setOf("value", "unit", "delta")).map { "it takes no $it" }
    };

    /** What is wrong with [data] as a Post to this type; nothing for a Post it accepts. */
    abstract fun problems(data: Map<*, *>): List<String>

    /** The name the user and a sender read, such as `number`. */
    val label get() = name.lowercase()

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label }
    }
}

/** The latest Post to a Widget: its [data], and the [run] and [task] that sent it [at]. */
data class Post(val data: Map<*, *>, val run: String, val task: String, val at: Instant)

/** A Post Ants refused, with a message naming the Widget, its type and the shape it needs, for the sender to fix it. */
class PostRefused(message: String) : RuntimeException(message)

/** Where the user put a Widget on the grid. */
data class Placement(val id: String, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * The user's Dashboards, each in its own folder in ANTS_HOME, `dashboards/<id>/`: its title and Widgets in
 * `dashboard.yaml`, each Widget's latest Post beside it in `<widget>.json`. Every change is kept as it is made, with no
 * Draft; nothing goes in the engine's tables. Every Post goes through [post], which alone checks it against its Widget.
 */
@Component
class Dashboards(@Value("\${ants.home}") home: Path, private val clock: Clock) {
    private val folder = home.resolve("dashboards")
    private val json = JsonMapper.shared()

    /** Every Dashboard, oldest first. */
    fun all(): List<Dashboard> =
        if (!folder.isDirectory()) emptyList()
        else folder.listDirectoryEntries().filter { ID.matches(it.name) }.mapNotNull { find(it.name) }.sortedBy { it.created }

    fun find(id: String): Dashboard? {
        val file = file(id)?.takeIf { it.exists() } ?: return null
        val yaml = Yaml().load<Any?>(file.readText()) as? Map<*, *> ?: emptyMap<String, Any?>()
        val widgets = (yaml["widgets"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { widget ->
            fun int(key: String, default: Int) = (widget[key] as? Number)?.toInt() ?: default
            val type = WidgetType.of(widget["type"].toString()) ?: return@mapNotNull null
            Widget(widget["id"].toString(), type, widget["title"]?.toString().orEmpty(), int("x", 0), int("y", 0), int("w", WIDTH), int("h", HEIGHT))
        }
        return Dashboard(
            id, title(yaml["title"]?.toString()), runCatching { Instant.parse(yaml["created"].toString()) }.getOrDefault(Instant.EPOCH),
            widgets, (yaml["retired"] as? List<*>).orEmpty().map { "$it" },
        )
    }

    /** A new "Untitled dashboard" with a random id no Dashboard has. */
    @Synchronized
    fun create(): Dashboard {
        val id = newId(DASHBOARD_ID) { folder.resolve(it).exists() }
        return Dashboard(id, UNTITLED, clock.instant()).also(::write)
    }

    /** Renames [id] to [title], trimmed; an empty title is "Untitled dashboard". The id never changes. */
    @Synchronized
    fun rename(id: String, title: String): Dashboard = get(id).copy(title = title(title)).also(::write)

    @Synchronized
    fun delete(id: String) {
        get(id)
        folder.resolve(id).toFile().deleteRecursively()
    }

    fun get(id: String) = find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Dashboard $id")

    /** A new Widget of [type] at the bottom of the Dashboard [id], titled "New <type>", with a random id it never had. */
    @Synchronized
    fun addWidget(id: String, type: WidgetType): Widget {
        val dashboard = get(id)
        val widgetId = newId(WIDGET_ID) { dashboard.widget(it) != null || it in dashboard.retired }
        val widget = Widget(widgetId, type, "New ${type.label}", 0, dashboard.widgets.maxOfOrNull { it.y + it.h } ?: 0, WIDTH, HEIGHT)
        write(dashboard.copy(widgets = dashboard.widgets + widget))
        return widget
    }

    /** Renames the Widget [widget] to [title], trimmed; its id never changes. */
    @Synchronized
    fun renameWidget(id: String, widget: String, title: String): Widget {
        val dashboard = get(id)
        val renamed = widget(dashboard, widget).copy(title = title.trim())
        write(dashboard.copy(widgets = dashboard.widgets.map { if (it.id == widget) renamed else it }))
        return renamed
    }

    /** Removes the Widget [widget] and its Posts; its id is retired, never given again. */
    @Synchronized
    fun removeWidget(id: String, widget: String) {
        val dashboard = get(id)
        widget(dashboard, widget)
        write(dashboard.copy(widgets = dashboard.widgets.filter { it.id != widget }, retired = dashboard.retired + widget))
        folder.resolve(id).resolve("$widget.json").deleteIfExists()
    }

    /** Places each Widget [placed] names where it says; the others stay. */
    @Synchronized
    fun layout(id: String, placed: List<Placement>) {
        val dashboard = get(id)
        val at = placed.associateBy { it.id }
        write(dashboard.copy(widgets = dashboard.widgets.map { widget -> at[widget.id]?.let { widget.copy(x = it.x, y = it.y, w = it.w, h = it.h) } ?: widget }))
    }

    /**
     * Posts [data] to the Widget at [address], `<dashboard id>/<widget id>`, as the Task [task] of the Run [run] sent
     * it. A missing Widget or data of the wrong shape is [PostRefused].
     */
    @Synchronized
    fun post(address: String, data: Any?, run: String, task: String) {
        val dashboard = find(address.substringBefore('/'))
        val widget = dashboard?.widget(address.substringAfter('/', ""))
            ?: throw PostRefused("No Widget $address: the Widget or its Dashboard was deleted, or the address is wrong. A Widget's address is <dashboard id>/<widget id>, as its Dashboard's Posting panel lists it.")
        val problems = if (data is Map<*, *>) widget.type.problems(data) else listOf("the data is not an object")
        if (problems.isNotEmpty()) throw PostRefused("The ${widget.type.label} Widget $address (${widget.title}) takes ${widget.type.shape}, but ${problems.joinToString("; ")}.")
        folder.resolve(dashboard.id).resolve("${widget.id}.json")
            .writeAtomically(json.writeValueAsString(mapOf("data" to data, "run" to run, "task" to task, "at" to clock.instant().toString())))
    }

    /** The latest Post to the Widget [widget] of the Dashboard [id], or null before its first. */
    fun latest(id: String, widget: String): Post? {
        if (!WIDGET.matches(widget)) return null
        val file = file(id)?.resolveSibling("$widget.json")?.takeIf { it.exists() } ?: return null
        val post = json.readValue(file.readText(), Map::class.java)
        return Post(post["data"] as? Map<*, *> ?: emptyMap<String, Any>(), post["run"].toString(), post["task"].toString(), Instant.parse(post["at"].toString()))
    }

    private fun widget(dashboard: Dashboard, widget: String) =
        dashboard.widget(widget) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Widget $widget on ${dashboard.id}")

    private fun write(dashboard: Dashboard) = file(dashboard.id)!!.writeAtomically(
        yaml.dump(
            linkedMapOf(
                "title" to dashboard.title,
                "created" to dashboard.created.toString(),
                "widgets" to dashboard.widgets.map { linkedMapOf("id" to it.id, "type" to it.type.label, "title" to it.title, "x" to it.x, "y" to it.y, "w" to it.w, "h" to it.h) },
                "retired" to dashboard.retired,
            ),
        ),
    )

    /** `dashboard.yaml` of [id], or null for a string that is no Dashboard id, so no path leaves the folder. */
    private fun file(id: String) = if (ID.matches(id)) folder.resolve(id).resolve("dashboard.yaml") else null

    private fun title(title: String?) = title?.trim().takeUnless { it.isNullOrEmpty() } ?: UNTITLED

    companion object {
        const val UNTITLED = "Untitled dashboard"
        private const val DASHBOARD_ID = 'd'
        private const val WIDGET_ID = 'w'
        private const val WIDTH = 3
        private const val HEIGHT = 2
        private val ID = Regex("d-[a-z0-9]{6}")
        private val WIDGET = Regex("w-[a-z0-9]{6}")
    }
}

/** A Widget as its Dashboard's page draws it: its latest [post], and the name of the Run that sent it. */
class WidgetView(val dashboard: String, val widget: Widget, val post: Post?, val runName: String?) {
    /** Where a Script or an Agent Posts to it. */
    val address get() = "$dashboard/${widget.id}"

    /** The number a number Widget shows, without a needless `.0`. */
    fun number(key: String): String? = when (val value = post?.data?.get(key)) {
        null -> null
        is Double -> if (value % 1.0 == 0.0 && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    val delta get() = (post?.data?.get("delta") as? Number)?.toDouble()

    val at get() = post?.at?.let(::shortTime)
}

/**
 * The Dashboards pages: **+ Add Dashboard** in the menu, a Dashboard's page with its title saved as typed, its grid of
 * Widgets, and Delete. `/dashboards` opens the Dashboard viewed last, kept in the `dashboard` cookie.
 */
@Controller
class DashboardsPage(private val dashboards: Dashboards, private val names: RunNames, private val templates: TemplateEngine, private val streams: Streams) {
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

    /** The title as typed, saved; answers the title kept, which an emptied one makes "Untitled dashboard". */
    @PostMapping("/dashboards/{id}/title")
    @ResponseBody
    fun rename(@PathVariable id: String, @RequestParam title: String) = mapOf("title" to dashboards.rename(id, title).title)

    @PostMapping("/dashboards/{id}/delete")
    fun delete(@PathVariable id: String, @RequestParam confirm: String?, model: Model): String {
        val dashboard = dashboards.get(id)
        if (confirm == null) return confirm(model, "Delete the Dashboard ${dashboard.title}?", Dependents(emptyList()), "/dashboards/$id/delete", emptyMap(), "/dashboards/$id")
        dashboards.delete(id)
        return "redirect:/dashboards"
    }

    /** **+ Widget**: a new Widget of [type], answered with its id and its grid item for the page to add. */
    @PostMapping("/dashboards/{id}/widgets")
    @ResponseBody
    fun addWidget(@PathVariable id: String, @RequestParam type: String): Map<String, String> {
        val widget = dashboards.addWidget(id, WidgetType.of(type) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No Widget type $type"))
        val view = WidgetView(id, widget, null, null)
        return mapOf(
            "id" to widget.id, "type" to widget.type.label, "shape" to widget.type.shape,
            "html" to render("widget.kte", mapOf("view" to view)), "body" to render("widgetbody.kte", mapOf("view" to view)),
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
        json.writeValueAsString(views(dashboard).associate { it.widget.id to render("widgetbody.kte", mapOf("view" to it)) })
    }

    private fun views(dashboard: Dashboard) = dashboard.widgets.map { widget ->
        val post = dashboards.latest(dashboard.id, widget.id)
        WidgetView(dashboard.id, widget, post, post?.let { runCatching { names.name(it.run) }.getOrNull() })
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
