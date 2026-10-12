package ants

import org.junit.jupiter.api.BeforeEach
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
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
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/** Boots Ants on a random port with a temporary ANTS_HOME (and so a temporary H2), driven over HTTP. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(AntsTest.Time::class)
abstract class AntsTest {
    @LocalServerPort
    var port = 0

    fun browser() = Browser("http://127.0.0.1:$port")

    @Autowired
    private lateinit var repository: RepositoryService

    @Autowired
    private lateinit var runtime: RuntimeService

    @Autowired
    private lateinit var launcher: AgentLauncher

    @Autowired
    private lateinit var schedules: Schedules

    @Autowired
    private lateinit var catalogue: Catalogue

    /**
     * Every test starts on a clean Ants, whatever ran before it in this JVM: no Processes, Runs or Schedules, no live
     * CLI holding a launch slot, no Agents, Skills or Templates of the user's, the clock where it belongs and every CLI's
     * gate open. A test writes its own `settings.yaml`.
     */
    @BeforeEach
    fun cleanSlate() {
        clock.offset = Duration.ZERO
        val runs = runtime.createProcessInstanceQuery().rootProcessInstances().list().map { it.id }
        // Deleting a deployment deletes its Runs and their history too; Schedules read the deployed Processes.
        for (deployment in repository.createDeploymentQuery().list()) {
            // A worker may be completing one of its Tasks at the same time. One that never deletes would leave its Runs
            // live for every later test, so it fails this one instead.
            for (attempt in 1..10) {
                val deleted = runCatching { repository.deleteDeployment(deployment.id, true, true) }
                if (deleted.isSuccess) break
                if (attempt == 10) throw AssertionError("The last test's Runs can't be deleted", deleted.exceptionOrNull())
                Thread.sleep(50)
            }
        }
        // Only once their Runs are gone, so no worker launches the Task again; each slot frees as its CLI ends.
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (launcher.running > 0 && System.nanoTime() < deadline) {
            runs.forEach(launcher::cancel)
            Thread.sleep(50)
        }
        Settings.AI_CLIS.forEach { launcher.tryNow(it) }
        for (folder in listOf("agents", "skills", "templates")) home.resolve(folder).toFile().run { deleteRecursively(); mkdirs() }
        // Under the Schedule loop's lock: a Run it was starting for the last test, at that test's clock, writes its state
        // once started, which would bring back that test's last start and leave this test's first time not yet due.
        synchronized(schedules) { home.resolve("schedules.yaml").toFile().delete() }
    }

    /**
     * What a Run of an Agent Task needs, in one place: the Agent `writer` listing the [skills] (with [agent] lines in its
     * frontmatter), each Skill with its result schema, none when null (and [metadata] lines in its frontmatter), and
     * [settings] with [claude] and [extra]. gh is the fake on the tests' PATH, logged in; a [FakeClaude] logs claude in.
     * A Skill named like a shipped one fails at once: the shipped Skill wins its name, so the test's would never launch.
     */
    fun agentTask(
        claude: FakeClaude?,
        skills: Map<String, String?> = mapOf("draft" to null),
        agent: String = "",
        metadata: String = "",
        extra: String = "",
        env: List<String>? = null,
    ) {
        val shipped = catalogue.skills().filter(catalogue::shipped).map { it.name }
        for (name in skills.keys) require(name !in shipped) { "$name is hidden by the shipped Skill of that name; give the test's Skill another name." }
        settings(claude, extra, env)
        home.resolve("agents/writer.md").writeText("---\n${agent}skills: ${skills.keys.joinToString(", ")}\n---\nWrite plainly.\n")
        for ((name, schema) in skills) {
            val folder = home.resolve("skills/$name").createDirectories()
            folder.resolve("SKILL.md").writeText("---\nname: $name\ndescription: Does $name.\n$metadata---\nDo it.\n")
            folder.resolve("result.schema.json").run { if (schema != null) writeText(schema) else deleteIfExists() }
        }
    }

    /** `settings.yaml`: a Vault, which the pre-Run check needs, [claude]'s command and [env] allow-list when given, and [extra]. */
    fun settings(claude: FakeClaude?, extra: String = "", env: List<String>? = null) = home.resolve("settings.yaml").writeText(
        (if (claude == null) "" else "claude:\n  command: [${claude.command}]\n" + (env?.let { "  env: [${it.joinToString(", ")}]\n" } ?: "")) + vault + extra,
    )

    companion object {
        /** Shared by every test in the JVM: a test that writes a file into it resets that file. */
        val home: Path = tempHome()

        /** What only a login shell finds: Ants' login shell is a fake that puts this folder on PATH only with `-l`. */
        val loginBin: Path = Files.createDirectories(Files.createTempDirectory("fake-shell").resolve("bin"))

        /**
         * The fake login shell. Only `-l` reads the "profile", the way only a login shell reads the user's, and puts
         * [loginBin] before the system folders alone, so Detect never finds a real claude or nono.
         */
        val shell: Path = loginBin.resolveSibling("sh").also {
            Files.writeString(it, "#!/bin/sh\n[ \"$1\" = -l ] && PATH=\"$loginBin:/usr/bin:/bin\" && shift\n[ \"$1\" = -c ] && exec /bin/sh -c \"$2\"\n")
            it.toFile().setExecutable(true)
        }

        val vaultPath: Path = Files.createTempDirectory("vault")

        /** `settings.yaml` lines for a Vault at [vaultPath], which the pre-Run check needs before any Run starts. */
        val vault = "vaults:\n  notes: $vaultPath\n"

        /** A temporary ANTS_HOME that doesn't exist yet. */
        fun tempHome(): Path = Files.createTempDirectory("ants-test").resolve("ants")

        /**
         * Boots and closes a second Ants on [home]. Operaton keeps engines and Cockpit's runtime JVM-wide, so it runs
         * without the webapp and with an engine of its own, or closing it would break the shared Ants' Cockpit.
         */
        fun bootAnother(home: Path, vararg args: String) = startAnother(home, *args).close()

        /** Starts a second Ants on [home], as [bootAnother] does, and leaves it running. */
        fun startAnother(home: Path, vararg args: String) = SpringApplicationBuilder(AntsApplication::class.java).run(
            "--ants.home=$home",
            "--ants.shell=$shell",
            "--server.port=0",
            "--ants.releases=${releases.toUri()}",
            "--ants.models=${models.toUri()}",
            "--operaton.bpm.process-engine-name=another",
            "--spring.autoconfigure.exclude=org.operaton.bpm.spring.boot.starter.webapp.OperatonBpmWebappAutoConfiguration",
            *args,
        )

        /** SHA256SUMS naming the tarball of a release of [version]. */
        fun sums(version: String) = "${"0".repeat(64)}  ${tarball(version)}\n"

        /** The release folder the update check reads, in place of GitHub's; its release is older than this Ants. */
        val releases: Path = Files.createTempDirectory("releases").also { it.resolve("SHA256SUMS").writeText(sums("0.0.1")) }

        /** Anthropic's model status table as the update check reads it; empty, so the bundled table stays. */
        val models: Path = Files.createTempDirectory("models").resolve("model-deprecations.md").also { it.writeText("") }

        /** Ants' clock, shared like [home]: a test that moves it puts it back. */
        val clock = MovableClock()

        @JvmStatic
        @DynamicPropertySource
        fun antsHome(registry: DynamicPropertyRegistry) {
            registry.add("ants.home") { home.toString() }
            registry.add("ants.shell") { shell.toString() }
            // Engine timers fire on the job executor, which otherwise looks for due ones every 5 s or more.
            registry.add("operaton.bpm.job-execution.wait-time-in-millis") { 200 }
            registry.add("operaton.bpm.job-execution.max-wait") { 500 }
            registry.add("ants.schedule-every") { "200ms" }
            registry.add("ants.update-every") { "200ms" }
            registry.add("ants.releases") { releases.toUri().toString() }
            registry.add("ants.models") { models.toUri().toString() }
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
    class Page(val status: Int, val path: String, val body: String, val headers: java.net.http.HttpHeaders? = null)

    private val client = HttpClient.newBuilder()
        .cookieHandler(CookieManager())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private var csrf: String? = null

    fun get(path: String) = send(HttpRequest.newBuilder(URI("$base$path")).GET())

    fun post(path: String, form: Map<String, String>): Page = post(path, form.toList())

    /** Posts a form whose fields may repeat, such as a multi-select's. */
    fun post(path: String, form: List<Pair<String, String>>): Page {
        val body = (form + listOfNotNull(csrf?.let { "_csrf" to it }))
            .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
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
        return Page(response.statusCode(), response.uri().path, body, response.headers())
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
