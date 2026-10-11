package ants

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import java.time.Clock

@Controller
class Home(private val launcher: AgentLauncher, private val clock: Clock) {
    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("pauses", launcher.pauses().mapValues { clock.local(it.value) })
        return "home"
    }

    /** **Try now**: retries a CLI the Usage limit paused, before its reset. */
    @PostMapping("/pauses/{cli}")
    fun tryNow(@PathVariable cli: String): String {
        launcher.tryNow(cli)
        return "redirect:/"
    }
}
