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
import tools.jackson.databind.json.JsonMapper
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

    fun browser() = Browser("http://127.0.0.1:$port", ::promising)

    /** The Result schema the Tasks listing each of [agentTask]'s Skills promise, by Skill. */
    protected val promises = mutableMapOf<String, String>()

    /**
     * [bpmn] with each Agent Task that lists one Skill [agentTask] gave a schema promising that schema
     * (`ants.result-schema`), unless it promises its own: since ADR 0024 the Task holds its schema, and a test's Skill
     * stands for the job it is drawn to do. [browser] sends every diagram through it.
     */
    fun promising(bpmn: String): String = Regex("""<((?:\w+:)?)serviceTask\b.*?</\1serviceTask>""", RegexOption.DOT_MATCHES_ALL).replace(bpmn) { task ->
        val prefix = Regex("""<((?:\w+:)?)property name="ants\.skills" value="([^"]*)"\s*/>""").find(task.value)
        val schema = prefix?.let { promises[it.groupValues[2].trim()] }
        if (schema == null || "ants.result-schema" in task.value) task.value
        else task.value.replace(prefix.value, prefix.value + """<${prefix.groupValues[1]}property name="ants.result-schema" value="${schema.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")}" />""")
    }

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

    @Autowired
    private lateinit var names: RunNames

    /** The Run workspace of [run], wherever its name put it. */
    fun workspace(run: String): Path = names.runWorkspace(run)

    /**
     * Every test starts on a clean Ants, whatever ran before it in this JVM: no Processes, Runs or Schedules, no live
     * CLI holding a launch slot, no Agents, Skills, Templates or Drafts of the user's, the clock where it belongs and every CLI's
     * gate open. A test writes its own `settings.yaml`.
     */
    @BeforeEach
    fun cleanSlate() {
        clock.offset = Duration.ZERO
        promises.clear()
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
        for (folder in listOf("agents", "skills", "templates", "scripts", "drafts")) home.resolve(folder).toFile().run { deleteRecursively(); mkdirs() }
        home.resolve("profiles").toFile().deleteRecursively()
        listOf("agent-ids.yaml", "skill-ids.yaml").forEach { home.resolve(it).deleteIfExists() }
        // Under the Schedule loop's lock: a Run it was starting for the last test, at that test's clock, writes its state
        // once started, which would bring back that test's last start and leave this test's first time not yet due.
        synchronized(schedules) { home.resolve("schedules.yaml").toFile().delete() }
    }

    fun instruct(profile: String, text: String) = home.resolve("profiles/$profile").createDirectories().resolve("AGENTS.md").writeText(text)

    fun excludingAbove(workspace: Path): String = JsonMapper.shared().writeValueAsString(mapOf(
        "disableAllHooks" to true,
        "attribution" to mapOf("commit" to "", "pr" to "🐜 Created with [Ants](https://github.com/oyvij/ants)"),
        "claudeMdExcludes" to generateSequence(workspace.parent) { it.parent }
            .flatMap { listOf(it.resolve("CLAUDE.md"), it.resolve("CLAUDE.local.md"), it.resolve(".claude/CLAUDE.md")) }.map(Path::toString).toList(),
    ))

    /**
     * What a Run of an Agent Task needs, in one place: the Agent `writer` listing the [skills] (with [agent] lines in its
     * frontmatter), each Skill with the result schema the Tasks listing it promise ([promising]), none when null (and [metadata] lines in its frontmatter), and
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
        retries: Int = 0,
    ) {
        val shipped = catalogue.skills(withHidden = true).filter(catalogue::shipped).map { it.name }
        for (name in skills.keys) require(name !in shipped) { "$name is hidden by the shipped Skill of that name; give the test's Skill another name." }
        settings(claude, extra, env, retries)
        promises.clear()
        skills.forEach { (name, schema) -> if (schema != null) promises[name] = schema }
        home.resolve("agents/writer.md").writeText("---\n${agent}skills: ${skills.keys.joinToString(", ")}\n---\nWrite plainly.\n")
        for ((name, schema) in skills) {
            val folder = home.resolve("skills/$name").createDirectories()
            folder.resolve("SKILL.md").writeText("---\nname: $name\ndescription: Does $name.\n$metadata---\nDo it.\n")
        }
    }

    /** `settings.yaml`: a Vault, which the pre-Run check needs, [claude]'s command and [env] allow-list when given, and [extra]. */
    fun settings(claude: FakeClaude?, extra: String = "", env: List<String>? = null, retries: Int = 0) = home.resolve("settings.yaml").writeAtomically(
        (if (claude == null) "" else "claude:\n  command: [${claude.command}]\n" + (env?.let { "  env: [${it.joinToString(", ")}]\n" } ?: "")) + vault + "retries: $retries\n" + extra,
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
            "--ants.prereleases=${releases.resolve("releases.json").toUri()}",
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
            registry.add("ants.prereleases") { releases.resolve("releases.json").toUri().toString() }
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
class Browser(private val base: String, private val diagram: (String) -> String = { it }) {
    class Page(val status: Int, val path: String, val body: String, val headers: java.net.http.HttpHeaders? = null, val query: String? = null)

    private val client = HttpClient.newBuilder()
        .cookieHandler(CookieManager())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private var csrf: String? = null

    fun get(path: String) = send(HttpRequest.newBuilder(URI("$base$path")).GET())

    fun close() = client.shutdownNow()

    /**
     * The Report of [run], once Ants has written it. It writes it just after the Run's end commits, so a test that has
     * seen the Run end may still be early.
     */
    fun report(run: String): String {
        var page = get("/runs/$run/report")
        eventually {
            page = get("/runs/$run/report")
            kotlin.test.assertEquals(200, page.status, "no Report for $run yet")
        }
        return page.body
    }

    fun post(path: String, form: Map<String, String>): Page = post(path, form.toList())

    /** Posts a form whose fields may repeat, such as a multi-select's. */
    fun post(path: String, form: List<Pair<String, String>>): Page {
        val body = (form.map { (k, v) -> k to if (k == "bpmn") diagram(v) else v } + listOfNotNull(csrf?.let { "_csrf" to it }))
            .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return send(
            HttpRequest.newBuilder(URI("$base$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    }

    /** Posts one file the way a form with an `<input type="file">` does. */
    fun upload(path: String, field: String, filename: String, content: ByteArray): Page = upload(path, field, listOf(filename to content))

    /** Posts several files under one field, as an `<input type="file" multiple>` or a folder pick does, each by its path. */
    fun upload(path: String, field: String, files: List<Pair<String, ByteArray>>): Page {
        val boundary = "ants-${System.nanoTime()}"
        val csrfPart = csrf?.let { "--$boundary\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n$it\r\n" }.orEmpty()
        val parts = files.map { (name, content) -> name to if (name.endsWith(".bpmn")) diagram(content.decodeToString()).toByteArray() else content }
            .fold(csrfPart.toByteArray()) { body, (filename, content) ->
            body + ("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray() + content + "\r\n".toByteArray()
        }
        val body = parts + "--$boundary--\r\n".toByteArray()
        return send(
            HttpRequest.newBuilder(URI("$base$path"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)),
        )
    }

    /** Posts [content] as the request's whole body, its CSRF token in a header, as a page script's `fetch` does. */
    fun postBytes(path: String, content: ByteArray) = send(
        HttpRequest.newBuilder(URI("$base$path")).apply { csrf?.let { header("X-CSRF-TOKEN", it) } }.POST(HttpRequest.BodyPublishers.ofByteArray(content)),
    )

    /** Imports a `.bpmn` from the test resources on the Processes page. */
    fun import(file: String): Page {
        get("/processes")
        return upload("/processes", "file", file, javaClass.getResource("/$file")!!.readBytes())
    }

    /**
     * Opens a page's SSE stream, `/live?[query]`, the way its `EventSource` does; the data of each event named [name]
     * arrives on the queue.
     */
    fun events(query: String, name: String): BlockingQueue<String> {
        val events = LinkedBlockingQueue<String>()
        var event = ""
        client.sendAsync(HttpRequest.newBuilder(URI("$base/live?$query")).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response ->
                response.body().forEach {
                    if (it.startsWith("event:")) event = it.removePrefix("event:")
                    if (it.startsWith("data:") && event == name) events += it.removePrefix("data:")
                }
            }
        return events
    }

    /**
     * The first event named [name] on a page's SSE stream, `/live?[query]`, which sends each part's state on connect,
     * then closes the stream, as a page left does. A test polling the nav uses this, not [events]: each stream left open
     * holds one of the JVM's shared pool threads for its minute, and a poll every 100 ms used them all up.
     */
    fun event(query: String, name: String): String =
        client.send(HttpRequest.newBuilder(URI("$base/live?$query")).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofLines())
            .body().use { lines ->
                var event = ""
                lines.iterator().asSequence()
                    .onEach { if (it.startsWith("event:")) event = it.removePrefix("event:") }
                    .first { it.startsWith("data:") && event == name }
                    .removePrefix("data:")
            }

    fun login(password: String = "admin"): Page {
        get("/login")
        return post("/login", mapOf("username" to "admin", "password" to password))
    }

    private fun send(request: HttpRequest.Builder): Page {
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        csrf = Regex("""name="_csrf"[^>]*value="([^"]+)"""").find(body)?.groupValues?.get(1) ?: csrf
        return Page(response.statusCode(), response.uri().path, body, response.headers(), response.uri().query)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/** The version of the Draft a page loaded, which its autosave carries: `0` when the page shows no Draft. */
fun draftVersion(page: String): String = Regex("""data-version="(\d+)"""").find(page)?.groupValues?.get(1) ?: error("The page has no autosave")

/** A page's autosave, as `draft.js` posts it: [form] as the Draft of the item at [page], against the [version] it loaded. */
fun Browser.autosave(page: String, form: List<Pair<String, String>>, version: String): Browser.Page = post("$page/draft", form + ("version" to version))

fun Browser.autosave(page: String, form: Map<String, String>, version: String) = autosave(page, form.toList(), version)

/**
 * **New** on the list at [list] (`/agents`, `/skills` or `/scripts`): a Draft-only item, and the path of its page,
 * which [Browser.publishNew] publishes.
 */
fun Browser.newDraft(list: String): String {
    get(list)
    return post("$list/new/draft", emptyMap()).path
}

/**
 * A new Agent, Skill or Script published as its pages make one: **New** on the list at [list], then the page's
 * **Publish** of [form]; a Script's own Secret values in it are saved on its Secrets card first, as the page has them
 * saved. The page Publish answers with. A refused Publish deletes the Draft-only item again.
 */
fun Browser.publishNew(list: String, form: List<Pair<String, String>>): Browser.Page {
    val page = newDraft(list)
    if (form.any { (field, value) -> field.matches(Regex("""secrets\[\d+]\.value""")) && value.isNotEmpty() }) post("$page/secrets", form)
    val published = post(page, form)
    if ("draft-mark p-draft" in published.body) post("$page/delete", mapOf("confirm" to "on"))
    return published
}

fun Browser.publishNew(list: String, form: Map<String, String>) = publishNew(list, form.toList())

/** An autosave's answer: `version`, `saved` (what the header says), `path`, `marker` and `label`; or `conflict` with a 409. */
fun answer(page: Browser.Page): Map<*, *> = tools.jackson.databind.json.JsonMapper.shared().readValue(page.body, Map::class.java)
