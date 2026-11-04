package ants

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
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** A Dashboard (ADR 0032): its fixed [id], which its page address uses, and its [title], its name. */
data class Dashboard(val id: String, val title: String, val created: Instant)

/**
 * The user's Dashboards, each in its own folder in ANTS_HOME, `dashboards/<id>/`, its title in `dashboard.yaml`.
 * Every change is kept as it is made, with no Draft; nothing goes in the engine's tables.
 */
@Component
class Dashboards(@Value("\${ants.home}") home: Path, private val clock: Clock) {
    private val folder = home.resolve("dashboards")

    /** Every Dashboard, oldest first. */
    fun all(): List<Dashboard> =
        if (!folder.isDirectory()) emptyList()
        else folder.listDirectoryEntries().filter { ID.matches(it.name) }.mapNotNull { find(it.name) }.sortedBy { it.created }

    fun find(id: String): Dashboard? {
        val file = file(id)?.takeIf { it.exists() } ?: return null
        val yaml = Yaml().load<Any?>(file.readText()) as? Map<*, *> ?: emptyMap<String, Any?>()
        return Dashboard(id, title(yaml["title"]?.toString()), runCatching { Instant.parse(yaml["created"].toString()) }.getOrDefault(Instant.EPOCH))
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

    private fun write(dashboard: Dashboard) =
        file(dashboard.id)!!.writeAtomically(yaml.dump(mapOf("title" to dashboard.title, "created" to dashboard.created.toString())))

    /** `dashboard.yaml` of [id], or null for a string that is no Dashboard id, so no path leaves the folder. */
    private fun file(id: String) = if (ID.matches(id)) folder.resolve(id).resolve("dashboard.yaml") else null

    private fun title(title: String?) = title?.trim().takeUnless { it.isNullOrEmpty() } ?: UNTITLED

    companion object {
        const val UNTITLED = "Untitled dashboard"
        private const val DASHBOARD_ID = 'd'
        private val ID = Regex("d-[a-z0-9]{6}")
    }
}

/**
 * The Dashboards pages: **+ Add Dashboard** in the menu, a Dashboard's page with its title saved as typed, and Delete.
 * `/dashboards` opens the Dashboard viewed last, kept in the `dashboard` cookie.
 */
@Controller
class DashboardsPage(private val dashboards: Dashboards) {
    @GetMapping("/dashboards")
    fun dashboards(@CookieValue(LAST, required = false) last: String?, model: Model): String {
        val open = last?.let(dashboards::find) ?: dashboards.all().firstOrNull()
        if (open != null) return "redirect:/dashboards/${open.id}"
        model.addAttribute("dashboard", null)
        return "dashboards"
    }

    @GetMapping("/dashboards/{id}")
    fun dashboard(@PathVariable id: String, model: Model, response: HttpServletResponse): String {
        model.addAttribute("dashboard", dashboards.get(id))
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

    companion object {
        /** The cookie naming the Dashboard viewed last. */
        const val LAST = "dashboard"

        /** The cookie keeping the menu's Dashboards toggle open (`open`) or closed, so every page draws the menu as it was. */
        const val MENU = "dashboards-menu"

        private const val YEAR = 365 * 24 * 3600
    }
}
