package ants

/** How a chart Widget draws its points: every kind takes the same ones, so the user switches freely. */
enum class ChartKind(val title: String) {
    LINE("Line"), AREA("Area"), BAR("Bar"), STACKED("Stacked bar"), SCATTER("Scatter"), PIE("Pie"), DONUT("Donut");

    /** The name a page and a sender use, such as `stacked`. */
    val label get() = name.lowercase()

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label }
    }
}

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
    } + (data.keys.map { "$it" } - fields.map { it.key }.toSet()).map { if (where.isEmpty()) "it takes no $it" else "${where.removeSuffix("'s ")} takes no $it" }

/** A chart's point: its x, a number, a date or text, its number y and its series, if any. */
private val POINT = listOf(
    PostField("x", true) { "x is not a number, a date or text".takeIf { _ -> it !is Number && it !is String } },
    number("y"),
    text("series", false),
)

/** The point or points of a chart Post, which the chart's type has accepted. */
private fun points(data: Map<*, *>): List<Map<*, *>> = ((data["point"]?.let(::listOf) ?: data["points"]) as List<*>).filterIsInstance<Map<*, *>>()

/** What tells a chart's points apart: a point with the same series and x as a stored one replaces it. */
private fun pointKey(point: Map<*, *>): Pair<String, String> {
    val x = point["x"]
    return "${point["series"] ?: ""}" to if (x is Number) "n" + java.math.BigDecimal(x.toString()).stripTrailingZeros().toPlainString() else "t$x"
}

/** The path of the first `url` key in [value], a custom chart's spec, as `data.url`; null when it names none. */
private fun url(value: Any?, path: String): String? = when (value) {
    is Map<*, *> -> value.entries.firstNotNullOfOrNull { (key, inner) -> if (key == "url") "$path.url" else url(inner, "$path.$key") }
    is List<*> -> value.withIndex().firstNotNullOfOrNull { (index, inner) -> url(inner, "$path[$index]") }
    else -> null
}

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
    },

    /** A chart: it holds points, each its own Post, and draws them as its [ChartKind] says. */
    CHART("{point: {x: number | date | text, y: number, series?: string}} or {points: [{x, y, series?}, …]}", "", keeps = true) {
        override fun problems(data: Map<*, *>): List<String> {
            val point = data["point"]
            val points = data["points"]
            return when {
                point != null && points != null -> listOf("it takes point or points, not both")
                point != null -> if (point is Map<*, *>) problems(point, POINT, "point's ") else listOf("point is not an object")
                points is List<*> -> points.withIndex().flatMap { (index, it) ->
                    if (it is Map<*, *>) problems(it, POINT, "point ${index + 1}'s ") else listOf("point ${index + 1} is not an object")
                }
                points != null -> listOf("points is not a list")
                else -> listOf("point or points is missing")
            } + (data.keys.map { "$it" } - setOf("point", "points")).map { "it takes no $it" }
        }

        /** Each point of [post] as its own Post; one with the same series and x as one [earlier] replaces it. */
        override fun held(earlier: List<Post>, post: Post): List<Post> {
            val added = points(post.data).map { post.copy(data = it) }.associateBy { pointKey(it.data) }
            return earlier.filter { pointKey(it.data) !in added } + added.values
        }
    },

    /** A chart an agent describes in Vega-Lite, its data inside; it may name no URL, so it never loads data from outside. */
    CUSTOM("{spec: Vega-Lite with its data in data.values}", "Any Vega-Lite chart, for agents") {
        override val label get() = "custom chart"

        override fun problems(data: Map<*, *>): List<String> {
            val spec = data["spec"]
            return when (spec) {
                null -> listOf("spec is missing")
                !is Map<*, *> -> listOf("spec is not an object")
                else -> listOfNotNull(url(spec, "spec")?.let { "$it names a URL, and a custom chart loads nothing from outside: put its data in data.values" })
            } + (data.keys.map { "$it" } - "spec").map { "it takes no $it" }
        }
    };

    /** What is wrong with [data] as a Post to this type; nothing for a Post it accepts. */
    abstract fun problems(data: Map<*, *>): List<String>

    /** What the Widget keeps of [data], a Post it accepted. */
    open fun kept(data: Map<*, *>): Map<*, *> = data

    /**
     * The Posts the Widget holds after [post], whose data it [kept], given those it held [earlier]: for a type that
     * [keeps] them, all of them; for the others the latest, after the one before it.
     */
    open fun held(earlier: List<Post>, post: Post): List<Post> = if (keeps) earlier + post else listOfNotNull(earlier.lastOrNull(), post)

    /** [data] with its newest [Dashboards.ITEMS] items, the last ones, so a long list is trimmed rather than refused. */
    protected fun newest(data: Map<*, *>) = data + ("items" to (data["items"] as List<*>).takeLast(Dashboards.ITEMS))

    /** The name the user and a sender read, such as `number`. */
    open val label get() = name.lowercase()

    /** A chart or a custom chart, which **+ Widget** lists under Charts. */
    val chart get() = this == CHART || this == CUSTOM

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label }

        val STATES = listOf("ok", "warn", "fail", "running")
        val LEVELS = listOf("info", "warn", "error")
    }
}
