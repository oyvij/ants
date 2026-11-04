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
import java.time.Duration
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

/**
 * A Widget on a Dashboard: its fixed [id], its [type], its [title], its place on the grid, and for a type that
 * [WidgetType.keeps] Posts, the days it [keep]s them.
 */
data class Widget(val id: String, val type: WidgetType, val title: String, val x: Int, val y: Int, val w: Int, val h: Int, val keep: Int = Dashboards.KEEP)

/** A field a Post's object may hold: its [key], whether it is [required], and what is wrong with a value, if anything. */
private class PostField(val key: String, val required: Boolean, val wrong: (Any) -> String?)

private fun number(key: String, required: Boolean = true) = PostField(key, required) { "$key is not a number".takeIf { _ -> it !is Number } }
private fun text(key: String, required: Boolean = true) = PostField(key, required) { "$key is not text".takeIf { _ -> it !is String } }
private fun oneOf(key: String, values: List<String>, required: Boolean = true) =
    PostField(key, required) { "$key is not ${values.dropLast(1).joinToString(", ")} or ${values.last()}".takeIf { _ -> it !in values } }

/** What is wrong with [data] as an object of [fields], each problem prefixed by [where]; nothing when it fits. */
private fun problems(data: Map<*, *>, fields: List<PostField>, where: String = ""): List<String> =
    fields.flatMap { field ->
        val value = data[field.key]
        when {
            value == null -> listOfNotNull("$where${field.key} is missing".takeIf { field.required })
            else -> listOfNotNull(field.wrong(value)?.let { where + it })
        }
    } + (data.keys.map { "$it" } - fields.map { it.key }.toSet()).map { if (where.isEmpty()) "it takes no $it" else "${where.removeSuffix(" ")} takes no $it" }

/** What is wrong with [data]'s `items` as a list of objects of [fields]. */
private fun items(data: Map<*, *>, fields: List<PostField>): List<String> {
    val items = data["items"] ?: return listOf("items is missing")
    if (items !is List<*>) return listOf("items is not a list")
    return problems(data, listOf(PostField("items", true) { null })) + items.withIndex().flatMap { (index, item) ->
        if (item is Map<*, *>) problems(item, fields, "item ${index + 1}'s ") else listOf("item ${index + 1} is not an object")
    }
}

/**
 * A Widget's type: the data a Post to it holds, as [shape] says it, the one plain [line] **+ Widget** shows, and
 * whether it [keeps] its Posts for a number of days, appending each, rather than replacing them.
 */
enum class WidgetType(val shape: String, val line: String, val keeps: Boolean = false) {
    NUMBER("{value: number, unit?: string, delta?: number}", "One number, with its change since the last Post") {
        override fun problems(data: Map<*, *>) = problems(data, listOf(number("value"), text("unit", false), number("delta", false)))
    },
    PROGRESS("{done: number, total: number}", "Done out of total") {
        override fun problems(data: Map<*, *>) = problems(data, listOf(number("done"), number("total")))
    },
    STATUS("{items: [{name: string, state: ok | warn | fail | running}]}", "Named items that are ok, warn, fail or running") {
        override fun problems(data: Map<*, *>) = items(data, listOf(text("name"), oneOf("state", STATES)))
        override fun kept(data: Map<*, *>) = newest(data)
    },
    LIST("{items: [{title: string, url?: string, note?: string}]}", "Titles with links and notes") {
        override fun problems(data: Map<*, *>) = items(
            data,
            listOf(
                text("title"),
                PostField("url", false) { "url is not an http or https address".takeIf { _ -> it !is String || !Regex("^https?://\\S+$").matches(it) } },
                text("note", false),
            ),
        )
        override fun kept(data: Map<*, *>) = newest(data)
    },
    TEXT("{text: string}", "A paragraph, such as an agent's summary") {
        override fun problems(data: Map<*, *>) = problems(data, listOf(text("text")))
    },
    LOG("{line: string, level?: info | warn | error}", "Lines added one at a time, kept for a number of days", keeps = true) {
        override fun problems(data: Map<*, *>) = problems(data, listOf(text("line"), oneOf("level", LEVELS, false)))
    };

    /** What is wrong with [data] as a Post to this type; nothing for a Post it accepts. */
    abstract fun problems(data: Map<*, *>): List<String>

    /** What the Widget keeps of [data], a Post it accepted. */
    open fun kept(data: Map<*, *>): Map<*, *> = data

    /** [data] with its newest [Dashboards.ITEMS] items, the last ones, so a long list is trimmed rather than refused. */
    protected fun newest(data: Map<*, *>) = data + ("items" to (data["items"] as List<*>).takeLast(Dashboards.ITEMS))

    /** The name the user and a sender read, such as `number`. */
    val label get() = name.lowercase()

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label }

        val STATES = listOf("ok", "warn", "fail", "running")
        val LEVELS = listOf("info", "warn", "error")
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
 * `dashboard.yaml`, each Widget's latest Post beside it in `<widget>.json`; the user's order of them in `dashboards/order.yaml`. Every change is kept as it is made, with no
 * Draft; nothing goes in the engine's tables. Every Post goes through [post], which alone checks it against its Widget.
 */
@Component
class Dashboards(@Value("\${ants.home}") home: Path, private val clock: Clock) {
    private val folder = home.resolve("dashboards")
    private val json = JsonMapper.shared()

    private val order = folder.resolve("order.yaml")

    /** Every Dashboard in the user's order, kept in `dashboards/order.yaml`; any it doesn't list follow, oldest first. */
    fun all(): List<Dashboard> {
        if (!folder.isDirectory()) return emptyList()
        val kept = order().withIndex().associate { (index, id) -> id to index }
        return folder.listDirectoryEntries().filter { ID.matches(it.name) }.mapNotNull { find(it.name) }
            .sortedWith(compareBy({ kept[it.id] ?: Int.MAX_VALUE }, { it.created }))
    }

    /** Puts the Dashboards in the order of [ids]; any Dashboard they leave out keeps its place after them. */
    @Synchronized
    fun reorder(ids: List<String>) {
        ids.firstOrNull { !ID.matches(it) }?.let { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No Dashboard id: $it") }
        val all = all().map { it.id }
        keep(ids.distinct().filter { it in all } + all.filter { it !in ids })
    }

    private fun order(): List<String> =
        if (order.exists()) (Yaml().load<Any?>(order.readText()) as? List<*>).orEmpty().map { "$it" } else emptyList()

    private fun keep(ids: List<String>) = order.writeAtomically(yaml.dump(ids))

    fun find(id: String): Dashboard? {
        val file = file(id)?.takeIf { it.exists() } ?: return null
        val yaml = Yaml().load<Any?>(file.readText()) as? Map<*, *> ?: emptyMap<String, Any?>()
        val widgets = (yaml["widgets"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { widget ->
            fun int(key: String, default: Int) = (widget[key] as? Number)?.toInt() ?: default
            val type = WidgetType.of(widget["type"].toString()) ?: return@mapNotNull null
            Widget(widget["id"].toString(), type, widget["title"]?.toString().orEmpty(), int("x", 0), int("y", 0), int("w", WIDTH), int("h", HEIGHT), int("keep", KEEP))
        }
        return Dashboard(
            id, title(yaml["title"]?.toString()), runCatching { Instant.parse(yaml["created"].toString()) }.getOrDefault(Instant.EPOCH),
            widgets, (yaml["retired"] as? List<*>).orEmpty().map { "$it" },
        )
    }

    /** A new "Untitled dashboard" with a random id no Dashboard has, at the bottom of the order. */
    @Synchronized
    fun create(): Dashboard {
        val id = newId(DASHBOARD_ID) { folder.resolve(it).exists() }
        val below = all().map { it.id }
        return Dashboard(id, UNTITLED, clock.instant()).also(::write).also { keep(below + id) }
    }

    /** Renames [id] to [title], trimmed; an empty title is "Untitled dashboard". The id never changes. */
    @Synchronized
    fun rename(id: String, title: String): Dashboard = get(id).copy(title = title(title)).also(::write)

    @Synchronized
    fun delete(id: String) {
        get(id)
        folder.resolve(id).toFile().deleteRecursively()
        keep(order() - id)
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
        val named = "The ${widget.type.label} Widget $address (${widget.title}) takes ${widget.type.shape}"
        val size = json.writeValueAsBytes(data).size
        if (size > LIMIT) throw PostRefused("$named in Posts of up to 256 KB, but this one is ${(size + 1023) / 1024} KB.")
        val problems = if (data is Map<*, *>) widget.type.problems(data) else listOf("the data is not an object")
        if (problems.isNotEmpty()) throw PostRefused("$named, but ${problems.joinToString("; ")}.")
        val post = Post(widget.type.kept(data as Map<*, *>), run, task, clock.instant())
        val earlier = posts(dashboard.id, widget)
        keep(dashboard.id, widget.id, if (widget.type.keeps) earlier + post else listOfNotNull(earlier.lastOrNull(), post))
    }

    /**
     * The Posts the Widget [widget] of the Dashboard [id] holds, oldest first: for a type that [WidgetType.keeps] them,
     * those of its last [Widget.keep] days; for the others the latest, after the one before it, which a number Widget's
     * change since the last Post needs.
     */
    fun posts(id: String, widget: Widget): List<Post> {
        val file = file(id)?.resolveSibling("${widget.id}.json")?.takeIf { it.exists() } ?: return emptyList()
        val since = clock.instant().minus(Duration.ofDays(widget.keep.toLong()))
        return (json.readValue(file.readText(), Map::class.java)["posts"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .map { Post(it["data"] as? Map<*, *> ?: emptyMap<String, Any>(), it["run"].toString(), it["task"].toString(), Instant.parse(it["at"].toString())) }
            .filter { !widget.type.keeps || it.at > since }
    }

    private fun keep(id: String, widget: String, posts: List<Post>) = folder.resolve(id).resolve("$widget.json").writeAtomically(
        json.writeValueAsString(mapOf("posts" to posts.map { mapOf("data" to it.data, "run" to it.run, "task" to it.task, "at" to it.at.toString()) })),
    )

    /** **Clear data**: empties the Widget [widget], which keeps its id and title and waits for its next Post. */
    @Synchronized
    fun clear(id: String, widget: String) {
        widget(get(id), widget)
        folder.resolve(id).resolve("$widget.json").deleteIfExists()
    }

    /** Sets the [days] the Widget [widget], of a type that [WidgetType.keeps] its Posts, keeps them. */
    @Synchronized
    fun keepFor(id: String, widget: String, days: Int): Widget {
        val dashboard = get(id)
        val kept = widget(dashboard, widget)
        if (!kept.type.keeps) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A ${kept.type.label} Widget keeps only its latest Post")
        if (days !in 1..MAX_KEEP) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A Widget keeps its Posts for 1 to $MAX_KEEP days")
        val changed = kept.copy(keep = days)
        write(dashboard.copy(widgets = dashboard.widgets.map { if (it.id == widget) changed else it }))
        return changed
    }

    private fun widget(dashboard: Dashboard, widget: String) =
        dashboard.widget(widget) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No Widget $widget on ${dashboard.id}")

    private fun write(dashboard: Dashboard) = file(dashboard.id)!!.writeAtomically(
        yaml.dump(
            linkedMapOf(
                "title" to dashboard.title,
                "created" to dashboard.created.toString(),
                "widgets" to dashboard.widgets.map { widget ->
                    linkedMapOf<String, Any>("id" to widget.id, "type" to widget.type.label, "title" to widget.title, "x" to widget.x, "y" to widget.y, "w" to widget.w, "h" to widget.h)
                        .also { if (widget.type.keeps) it["keep"] = widget.keep }
                },
                "retired" to dashboard.retired,
            ),
        ),
    )

    /** `dashboard.yaml` of [id], or null for a string that is no Dashboard id, so no path leaves the folder. */
    private fun file(id: String) = if (ID.matches(id)) folder.resolve(id).resolve("dashboard.yaml") else null

    private fun title(title: String?) = title?.trim().takeUnless { it.isNullOrEmpty() } ?: UNTITLED

    companion object {
        const val UNTITLED = "Untitled dashboard"

        /** The days a Widget keeps its Posts unless the user sets other, for a type that [WidgetType.keeps] them. */
        const val KEEP = 90
        private const val MAX_KEEP = 3650

        /** The items a status or list Widget keeps of a Post, its newest. */
        const val ITEMS = 100

        /** The largest Post, in bytes of its JSON. */
        private const val LIMIT = 256 * 1024
        private const val DASHBOARD_ID = 'd'
        private const val WIDGET_ID = 'w'
        private const val WIDTH = 3
        private const val HEIGHT = 2
        private val ID = Regex("d-[a-z0-9]{6}")
        private val WIDGET = Regex("w-[a-z0-9]{6}")
    }
}

/**
 * A Widget as its Dashboard's page draws it: the [posts] it holds, oldest first, its latest [post], and the name of the
 * Run that sent that.
 */
class WidgetView(val dashboard: String, val widget: Widget, val posts: List<Post>, val runName: String?) {
    val post = posts.lastOrNull()

    /** Where a Script or an Agent Posts to it. */
    val address get() = "$dashboard/${widget.id}"

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

    val at get() = post?.at?.let(::shortTime)

    fun time(post: Post) = shortTime(post.at)

    companion object {
        /** The lines a log Widget draws, its newest; it keeps more, for its days. */
        const val LINES = 200
    }
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
        if (confirm == null) return confirm(model, "Delete the Dashboard ${dashboard.title}?", Dependents(emptyList()), "/dashboards/$id/delete", emptyMap(), "/dashboards/$id")
        dashboards.delete(id)
        return "redirect:/dashboards"
    }

    /** **+ Widget**: a new Widget of [type], answered with its id and its grid item for the page to add. */
    @PostMapping("/dashboards/{id}/widgets")
    @ResponseBody
    fun addWidget(@PathVariable id: String, @RequestParam type: String): Map<String, String> {
        val widget = dashboards.addWidget(id, WidgetType.of(type) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No Widget type $type"))
        val view = WidgetView(id, widget, emptyList(), null)
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

    private fun views(dashboard: Dashboard) = dashboard.widgets.map { view(dashboard.id, it) }

    private fun view(dashboard: String, widget: Widget): WidgetView {
        val posts = dashboards.posts(dashboard, widget)
        return WidgetView(dashboard, widget, posts, posts.lastOrNull()?.let { runCatching { names.name(it.run) }.getOrNull() })
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
