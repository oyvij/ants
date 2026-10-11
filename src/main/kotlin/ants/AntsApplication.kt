package ants

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
class AntsApplication {
    /** Where Ants reads the time, such as pause-gate deadlines; tests move it forward instead of waiting. */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}

fun main(args: Array<String>) {
    runApplication<AntsApplication>(*args)
}
