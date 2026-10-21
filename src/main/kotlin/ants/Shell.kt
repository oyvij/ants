package ants

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.operaton.bpm.engine.HistoryService
import org.springframework.boot.info.BuildProperties
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * What the sidebar shows on every page, read only when a page renders it: the Notification list count, the Runs line
 * ("✻ 2 working" while Agent Tasks run, else "3 live"), the running version, and a toast left by an action that moved
 * on to another page. The page's stream ([Live]) keeps the count and the Runs line live, and brings the pause and
 * update notices.
 */
class Shell(val csrf: CsrfToken?, val version: String, waiting: () -> Int, working: () -> Int, live: () -> Long, session: HttpSession?) {
    val waiting by lazy(waiting)

    /** Agent Tasks running now, across all Runs. */
    val working by lazy(working)

    /** Live Runs. */
    val live by lazy(live)

    /** Shown once, on the first page rendered after it was left. */
    val toast: Toast? by lazy { (session?.getAttribute(TOAST) as Toast?)?.also { session?.removeAttribute(TOAST) } }

    companion object {
        private const val TOAST = "ants.toast"

        /** Leaves a toast for the next page, for an action that leaves the page it was pressed on, such as a Push. */
        fun toast(request: HttpServletRequest, toast: Toast) = request.session.setAttribute(TOAST, toast)
    }
}

/** How many Runs are live. */
fun HistoryService.liveRuns() = createHistoricProcessInstanceQuery().rootProcessInstances().active().count()

/** A toast: its [title] in bold, then its [text]. */
data class Toast(val title: String, val text: String, val kind: Kind) : java.io.Serializable {
    enum class Kind { SUCCESS, INFO, WARNING, ERROR }
}

@ControllerAdvice
class ShellAdvice(
    private val notifications: Notifications,
    private val launcher: AgentLauncher,
    private val history: HistoryService,
    private val build: BuildProperties,
) {
    @ModelAttribute("shell")
    fun shell(request: HttpServletRequest) = Shell(
        request.getAttribute(CsrfToken::class.java.name) as CsrfToken?,
        build.version.orEmpty(),
        { notifications.entries().size },
        { launcher.running },
        { history.liveRuns() },
        request.getSession(false),
    )
}

/** The login page, the sidebar's Light mode and Dark mode, and a markdown field's Preview. */
@Controller
class ShellPages(private val settings: Settings) {
    /** Spring Security sends a wrong password back here with `?error`, and Log out with `?logout`. */
    @GetMapping("/login")
    fun login(@RequestParam params: Map<String, String>, model: Model): String {
        model.addAttribute("error", "error" in params)
        model.addAttribute("loggedOut", "logout" in params)
        return "login"
    }

    /** Writes the theme setting, then goes back to the page it was pressed on. */
    @PostMapping("/theme")
    fun theme(@RequestParam theme: String, @RequestHeader("Referer", required = false) from: String?): String {
        require(theme in listOf("light", "dark")) { "No theme $theme" }
        settings.save { it["theme"] = theme }
        return back(from)
    }

    /** A markdown field's Preview and Expand (markdown.js): [text] rendered as the Approve page and the Report render it. */
    @PostMapping("/markdown")
    @ResponseBody
    fun markdown(@RequestParam text: String) = Transcript.markdown(text)
}
