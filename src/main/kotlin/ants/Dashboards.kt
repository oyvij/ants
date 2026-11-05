package ants

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
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
data class Widget(
    val id: String, val type: WidgetType, val title: String, val x: Int, val y: Int, val w: Int, val h: Int, val keep: Int = Dashboards.KEEP,
    /** How a chart draws its points; switching it never changes what the chart takes. */
    val kind: ChartKind = ChartKind.LINE,
)

/** Where a Script or an Agent Posts to a Widget, `<dashboard id>/<widget id>`, as a Script lists it; it never changes. */
data class WidgetAddress(val dashboard: String, val widget: String) {
    override fun toString() = "$dashboard/$widget"

    companion object {
        fun of(dashboard: Dashboard, widget: Widget) = WidgetAddress(dashboard.id, widget.id)

        /** The address [text] names, as a Script lists it or a sender gives it. */
        fun of(text: String) = WidgetAddress(text.substringBefore('/'), text.substringAfter('/', ""))
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
            Widget(
                widget["id"].toString(), type, widget["title"]?.toString().orEmpty(), int("x", 0), int("y", 0), int("w", WIDTH), int("h", HEIGHT), int("keep", KEEP),
                ChartKind.of(widget["kind"].toString()) ?: ChartKind.LINE,
            )
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

    /**
     * A new Widget of [type] at the bottom of the Dashboard [id], titled "New <type>", or for a chart "New <kind>
     * chart", with a random id it never had. A chart is bigger than the others. A helper may give a readable [named]
     * id, such as `disk`, and a [titled] title; that id is just as fixed, and one the Dashboard has or had is refused.
     */
    @Synchronized
    fun addWidget(id: String, type: WidgetType, kind: ChartKind = ChartKind.LINE, named: String? = null, titled: String? = null): Widget {
        val dashboard = get(id)
        fun taken(it: String) = dashboard.widget(it) != null || it in dashboard.retired
        if (named != null) {
            if (!READABLE.matches(named)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A Widget id is lowercase letters, digits and -, starting with a letter, up to 32 long, such as disk; not $named")
            if (taken(named)) throw ResponseStatusException(HttpStatus.CONFLICT, "The Dashboard ${dashboard.id} has or had a Widget $named, and a Widget id is never used again: pick another")
        }
        val widgetId = named ?: newId(WIDGET_ID, ::taken)
        val title = titled?.trim()?.takeIf(String::isNotEmpty) ?: if (type == WidgetType.CHART) "New ${kind.title.lowercase()} chart" else "New ${type.label}"
        val (w, h) = if (type.chart) CHART_WIDTH to CHART_HEIGHT else WIDTH to HEIGHT
        val widget = Widget(widgetId, type, title, 0, dashboard.widgets.maxOfOrNull { it.y + it.h } ?: 0, w, h, kind = kind)
        write(dashboard.copy(widgets = dashboard.widgets + widget))
        return widget
    }

    /** Renames the Widget [widget] to [title], trimmed; its id never changes. */
    @Synchronized
    fun renameWidget(id: String, widget: String, title: String): Widget = replaceWidget(id, widget) { it.copy(title = title.trim()) }

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
    fun post(address: WidgetAddress, data: Any?, run: String, task: String) {
        val (widget, kept) = accepted(address, data)
        keep(address.dashboard, widget.id, widget.type.held(posts(address.dashboard, widget), Post(kept, run, task, clock.instant())))
    }

    /** Checks [data] as a Post to the Widget at [address], as [post] does, and stores nothing: a test run's Post. */
    fun check(address: WidgetAddress, data: Any?) {
        accepted(address, data)
    }

    /** The Widget at [address] and what it keeps of [data], a Post it accepts; else [PostRefused]. */
    private fun accepted(address: WidgetAddress, data: Any?): Pair<Widget, Map<*, *>> {
        val widget = widget(address)
            ?: throw PostRefused("No Widget $address: the Widget or its Dashboard was deleted, or the address is wrong. A Widget's address is <dashboard id>/<widget id>, as its Dashboard's Posting panel lists it.")
        val named = "The ${widget.type.label} Widget $address (${widget.title}) takes ${widget.type.shape}"
        val size = json.writeValueAsBytes(data).size
        if (size > LIMIT) throw PostRefused("$named in Posts of up to $LIMIT_SHOWN, but this one is ${(size + 1023) / 1024} KB.")
        val problems = if (data is Map<*, *>) widget.type.problems(data) else listOf("the data is not an object")
        if (problems.isNotEmpty()) throw PostRefused("$named, but ${problems.joinToString("; ")}.")
        return widget to widget.type.kept(data as Map<*, *>)
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
    fun keepFor(id: String, widget: String, days: Int): Widget = replaceWidget(id, widget) { kept ->
        if (!kept.type.keeps) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A ${kept.type.label} Widget keeps only its latest Post")
        if (days !in 1..MAX_KEEP) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A Widget keeps its Posts for 1 to $MAX_KEEP days")
        kept.copy(keep = days)
    }

    /** Switches the chart [widget] to draw as [kind]; the points it holds and takes stay the same. */
    @Synchronized
    fun drawAs(id: String, widget: String, kind: ChartKind): Widget = replaceWidget(id, widget) { chart ->
        if (chart.type != WidgetType.CHART) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A ${chart.type.label} Widget has no chart kind")
        chart.copy(kind = kind)
    }

    /** Puts what [change] makes of the Widget [widget] of the Dashboard [id] in its place, and gives it. */
    private fun replaceWidget(id: String, widget: String, change: (Widget) -> Widget): Widget {
        val dashboard = get(id)
        val changed = change(widget(dashboard, widget))
        write(dashboard.copy(widgets = dashboard.widgets.map { if (it.id == widget) changed else it }))
        return changed
    }

    /** The Widget at [address], or null when it or its Dashboard is gone. */
    fun widget(address: WidgetAddress): Widget? = find(address.dashboard)?.widget(address.widget)

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
                        .also { if (widget.type == WidgetType.CHART) it["kind"] = widget.kind.label }
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

        /** The largest Post, in KB of its JSON, as a sender reads it. */
        private const val LIMIT_KB = 256
        const val LIMIT_SHOWN = "$LIMIT_KB KB"

        /** The largest Post, in bytes of its JSON. */
        private const val LIMIT = LIMIT_KB * 1024
        private const val DASHBOARD_ID = 'd'
        private const val WIDGET_ID = 'w'
        private const val WIDTH = 3
        private const val HEIGHT = 2
        private const val CHART_WIDTH = 6
        private const val CHART_HEIGHT = 4
        private val ID = Regex("d-[a-z0-9]{6}")
        private val READABLE = Regex("[a-z][a-z0-9-]{0,31}")
    }
}

