package ants

import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/** Boots Ants on a random port with a temporary ANTS_HOME (and so a temporary H2), driven over HTTP. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(AntsTest.Time::class)
abstract class AntsTest {
    @LocalServerPort
    var port = 0

    fun browser() = Browser("http://127.0.0.1:$port")

    companion object {
        /** Shared by every test in the JVM: a test that writes a file into it resets that file. */
        val home: Path = tempHome()

        /** What only a login shell finds: Ants' login shell is a fake that puts this folder on PATH only with `-l`. */
        val loginBin: Path = Files.createDirectories(Files.createTempDirectory("fake-shell").resolve("bin"))

        /** The fake login shell. Only `-l` reads the "profile", the way only a login shell reads the user's. */
        val shell: Path = loginBin.resolveSibling("sh").also {
            Files.writeString(it, "#!/bin/sh\n[ \"$1\" = -l ] && PATH=\"$loginBin:\$PATH\" && shift\n[ \"$1\" = -c ] && exec /bin/sh -c \"$2\"\n")
            it.toFile().setExecutable(true)
        }

        /** A temporary ANTS_HOME that doesn't exist yet. */
        fun tempHome(): Path = Files.createTempDirectory("ants-test").resolve("ants")

        /**
         * Boots and closes a second Ants on [home]. Operaton keeps engines and Cockpit's runtime JVM-wide, so it runs
         * without the webapp and with an engine of its own, or closing it would break the shared Ants' Cockpit.
         */
        fun bootAnother(home: Path, vararg args: String) = SpringApplicationBuilder(AntsApplication::class.java).run(
            "--ants.home=$home",
            "--ants.shell=$shell",
            "--server.port=0",
            "--operaton.bpm.process-engine-name=another",
            "--spring.autoconfigure.exclude=org.operaton.bpm.spring.boot.starter.webapp.OperatonBpmWebappAutoConfiguration",
            *args,
        ).close()

        /** Ants' clock, shared like [home]: a test that moves it puts it back. */
        val clock = MovableClock()

        @JvmStatic
        @DynamicPropertySource
        fun antsHome(registry: DynamicPropertyRegistry) {
            registry.add("ants.home") { home.toString() }
            registry.add("ants.shell") { shell.toString() }
        }
    }

    @TestConfiguration
    class Time {
        @Bean
        @Primary
        fun movableClock(): Clock = clock
    }
}

/** The system clock, moved forward by [offset], so tests reach a time without waiting for it. */
class MovableClock(@Volatile var offset: Duration = Duration.ZERO) : Clock() {
    override fun instant(): Instant = Instant.now() + offset
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId) = throw UnsupportedOperationException()
}

/** A user with a browser: keeps cookies, follows redirects and posts forms with their CSRF token. */
class Browser(private val base: String) {
    class Page(val status: Int, val path: String, val body: String)

    private val client = HttpClient.newBuilder()
        .cookieHandler(CookieManager())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private var csrf: String? = null

    fun get(path: String) = send(HttpRequest.newBuilder(URI("$base$path")).GET())

    fun post(path: String, form: Map<String, String>): Page {
        val body = (form + listOfNotNull(csrf?.let { "_csrf" to it }))
            .entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return send(
            HttpRequest.newBuilder(URI("$base$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    }

    /** Posts one file the way a form with an `<input type="file">` does. */
    fun upload(path: String, field: String, filename: String, content: ByteArray): Page {
        val boundary = "ants-${System.nanoTime()}"
        val csrfPart = csrf?.let { "--$boundary\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n$it\r\n" }.orEmpty()
        val head = "$csrfPart--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray() + content + "\r\n--$boundary--\r\n".toByteArray()
        return send(
            HttpRequest.newBuilder(URI("$base$path"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)),
        )
    }

    /** Imports a `.bpmn` from the test resources on the Processes page. */
    fun import(file: String): Page {
        get("/processes")
        return upload("/processes", "file", file, javaClass.getResource("/$file")!!.readBytes())
    }

    /** Opens an SSE stream the way a page's `EventSource` does; each event's data arrives on the queue. */
    fun events(path: String): BlockingQueue<String> {
        val events = LinkedBlockingQueue<String>()
        client.sendAsync(HttpRequest.newBuilder(URI("$base$path")).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response -> response.body().forEach { if (it.startsWith("data:")) events += it.removePrefix("data:") } }
        return events
    }

    fun login(password: String = "admin"): Page {
        get("/login")
        return post("/login", mapOf("username" to "admin", "password" to password))
    }

    private fun send(request: HttpRequest.Builder): Page {
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        csrf = Regex("""name="_csrf"[^>]*value="([^"]+)"""").find(body)?.groupValues?.get(1) ?: csrf
        return Page(response.statusCode(), response.uri().path, body)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
