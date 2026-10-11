package ants

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class Home {
    @GetMapping("/")
    fun home() = "home"
}
