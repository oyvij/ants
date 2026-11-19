package ants

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The user's Schedules in a Setup: under `schedules` in `setup.yaml`, by their ids, each its Process, cron and values.
 * Each lands paused, after the Processes, so an old and a new Ants never both run it; one whose Process is neither here
 * nor in the Setup is refused.
 */
@Component
@Order(40)
class SchedulesInSetup(private val schedules: Schedules) : SetupKind<Map<*, *>> {
    override val noun = "Schedule"
    override val folder = "schedules"

    override fun note(item: SetupItem<Map<*, *>>) = "Paused, so this Ants and the one it came from don't both run it: resume it on its Process's page."

    override fun exportSettings(ticked: (String) -> Boolean) = schedules.exported().filterKeys(ticked).takeIf { it.isNotEmpty() }?.let { mapOf(folder to it) }.orEmpty()

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>) = (settings[folder] as? Map<*, *>).orEmpty().map { (id, schedule) ->
        val given = schedule as? Map<*, *>
        val name = given?.let { "${it["process"]} · ${inWords(it["cron"]?.toString().orEmpty())}" } ?: "$id"
        SetupItem(this, "$folder/$id", "$id", name, emptyMap(), if (given == null) "it is no Schedule." else null, given)
    }

    /** Its Process, so ticking a Schedule on the Export page ticks the Process it starts. */
    override fun uses(item: SetupItem<Map<*, *>>) = item.setting?.get("process")?.toString()?.takeIf { it.isNotBlank() }
        ?.let { listOf(SetupUse(ProcessesInSetup.FOLDER, it)) }.orEmpty()

    /** A Schedule's id, a UUID. */
    override fun isId(id: String) = Regex("[0-9a-f-]{36}").matches(id)

    override fun has(id: String) = schedules.has(id)

    override fun land(item: SetupItem<Map<*, *>>, name: String) = schedules.land(item.id, checkNotNull(item.setting))
}
