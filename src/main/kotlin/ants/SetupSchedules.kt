package ants

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

// The kinds of a Setup that run or show what the others make: Dashboards, which Scripts Post to, and Schedules, which
// start Processes.

/**
 * The user's Dashboards in a Setup: `dashboards/<id>/dashboard.yaml`, each its layout with its Widget ids, so a Script
 * Posting to a Widget keeps working, and never its Posts. A Dashboard's title is no name to clash on.
 */
@Component
@Order(5)
class DashboardsInSetup(private val dashboards: Dashboards) : SetupKind {
    override val noun = "Dashboard"
    override val folder = "dashboards"

    override fun export() = dashboards.layouts().mapKeys { "$folder/${it.key}/$LAYOUT" }

    override fun read(files: Map<String, ByteArray>) = files.filterKeys { Regex("$folder/[^/]+/$LAYOUT").matches(it) }.map { (path, bytes) ->
        val root = path.removeSuffix("/$LAYOUT")
        val id = root.substringAfter('/')
        runCatching { (yaml.load<Any?>(bytes.decodeToString()) as Map<*, *>)["title"]?.toString() ?: Dashboards.UNTITLED }.fold(
            { SetupItem(this, root, id, it, mapOf(path to bytes)) },
            { SetupItem(this, root, id, id, mapOf(path to bytes), "its $LAYOUT is broken: ${it.message}") },
        )
    }

    override fun isId(id: String) = Dashboards.ID.matches(id)

    override fun has(id: String) = dashboards.find(id) != null

    override fun taken(name: String, id: String) = false

    override fun land(item: SetupItem, name: String) = dashboards.land(item.id, item.files.values.single().decodeToString(), name)

    private companion object {
        const val LAYOUT = "dashboard.yaml"
    }
}

/**
 * The user's Schedules in a Setup: under `schedules` in `setup.yaml`, by their ids, each its Process, cron and values.
 * Each lands paused, after the Processes, so an old and a new Ants never both run it.
 */
@Component
@Order(40)
class SchedulesInSetup(private val schedules: Schedules) : SetupKind {
    override val noun = "Schedule"
    override val folder = "schedules"

    override fun note(item: SetupItem) = "Paused, so this Ants and the one it came from don't both run it: resume it on its Process's page."

    override fun export() = emptyMap<String, ByteArray>()

    override fun exportSettings() = schedules.exported().takeIf { it.isNotEmpty() }?.let { mapOf(folder to it) }.orEmpty()

    override fun read(files: Map<String, ByteArray>) = read(files, emptyMap<String, Any>())

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>) = (settings[folder] as? Map<*, *>).orEmpty().map { (id, schedule) ->
        val given = schedule as? Map<*, *>
        val name = given?.let { "${it["process"]} · ${inWords(it["cron"]?.toString().orEmpty())}" } ?: "$id"
        SetupItem(this, "$folder/$id", "$id", name, emptyMap(), if (given == null) "it is no Schedule." else null, given)
    }

    /** Its Process, so ticking a Schedule on the Export page ticks the Process it starts. */
    override fun uses(item: SetupItem) = (item.setting as? Map<*, *>)?.get("process")?.toString()?.takeIf { it.isNotBlank() }
        ?.let { listOf(SetupUse("processes", it)) }.orEmpty()

    /** A Schedule's id, a UUID. */
    override fun isId(id: String) = Regex("[0-9a-f-]{36}").matches(id)

    override fun has(id: String) = schedules.has(id)

    override fun taken(name: String, id: String) = false

    override fun land(item: SetupItem, name: String) = schedules.land(item.id, item.setting as Map<*, *>)
}
