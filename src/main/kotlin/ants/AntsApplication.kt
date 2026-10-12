package ants

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess

@SpringBootApplication
class AntsApplication {
    /** Where Ants reads the time, such as pause-gate deadlines; tests move it forward instead of waiting. */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}

/**
 * Ants' properties as Spring reads them, for what runs before Spring starts: the command line, then the environment
 * (ANTS_HOME sets `ants.home`), then application.yaml.
 */
// ponytail: application.yaml in the jar only, none of Spring Boot's other config locations, which Ants doesn't use
fun environment(args: Array<String>): Environment = StandardEnvironment().apply {
    propertySources.addFirst(SimpleCommandLinePropertySource(*args))
    YamlPropertySourceLoader().load("application.yaml", ClassPathResource("application.yaml")).forEach(propertySources::addLast)
}

fun main(args: Array<String>) {
    val environment = environment(args)
    if (args.toList() == listOf("password")) exitProcess(changePassword(environment))
    LockFile.take(Path.of(environment.getRequiredProperty("ants.home")))?.let { holder ->
        System.err.println(holder)
        exitProcess(1)
    }
    runApplication<AntsApplication>(*args)
}
