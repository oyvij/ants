package ants

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.system.exitProcess

@SpringBootApplication
class AntsApplication {
    /** Where Ants reads the time, such as pause-gate deadlines; tests move it forward instead of waiting. */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}

/** Logs each request Ants took more than 100 ms to answer, so `ants logs` names a page that is slow. */
@Component
class SlowRequests : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val start = System.nanoTime()
        chain.doFilter(request, response)
        val took = Duration.ofNanos(System.nanoTime() - start).toMillis()
        if (took > 100) log.info("${request.method} ${request.requestURI} took $took ms")
    }
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
