package ants

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The user's Dashboards in a Setup: `dashboards/<id>/dashboard.yaml`, each its layout with its Widget ids, so a Script
 * Posting to a Widget keeps working, and never its Posts. A Dashboard's title is no name to clash on.
 */
@Component
@Order(5)
class DashboardsInSetup(private val dashboards: Dashboards) : SetupKind<Nothing> {
    override val noun = "Dashboard"
    override val folder = "dashboards"

    override fun export() = dashboards.layouts().mapKeys { "$folder/${it.key}/$LAYOUT" }

    override fun read(files: Map<String, ByteArray>, settings: Map<*, *>) = files.filterKeys { Regex("$folder/[^/]+/$LAYOUT").matches(it) }.map { (path, bytes) ->
        val root = path.removeSuffix("/$LAYOUT")
        val id = root.substringAfter('/')
        readOr(root, mapOf(path to bytes), id, "its $LAYOUT is broken", id) {
            SetupItem(this, root, id, (yaml.load<Any?>(bytes.decodeToString()) as Map<*, *>)["title"]?.toString() ?: Dashboards.UNTITLED, mapOf(path to bytes))
        }
    }

    override fun isId(id: String) = Dashboards.ID.matches(id)

    override fun has(id: String) = dashboards.find(id) != null

    override fun land(item: SetupItem<Nothing>, name: String) = dashboards.land(item.id, item.files.values.single().decodeToString(), name)

    private companion object {
        const val LAYOUT = "dashboard.yaml"
    }
}
