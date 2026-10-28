package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ExternalTaskService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class CloneReposTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var runtime: RuntimeService

    @Autowired
    lateinit var externalTasks: ExternalTaskService

    /** `settings.yaml` with a Source map of [repos]. */
    private fun sourceMap(vararg repos: Remote) = settings(null, sourceMapLines(*repos))

    /** Starts a Run of `clone-repos.bpmn` named [name] with [repos] and [branch], and waits until both its Clone repos Tasks are done. */
    private fun start(repos: List<String>, branch: String = "", name: String = ""): String {
        browser.import("clone-repos.bpmn")
        browser.get("/processes/clone-repos")
        val run = browser.post("/processes/clone-repos/runs", repos.map { "repos" to it } + ("branch" to branch) + (NAME to name)).path.removePrefix("/runs/")
        eventually { assertNotNull(runtime.getVariable(run, "again"), browser.get("/runs/$run").body) }
        return run
    }

    private fun clone(run: String, name: String): Path = workspace(run).resolve("repos/$name")

    @Test
    fun `Clone repos clones every named repo on the work branch, and gives its Result and clones`() {
        val api = repo("api")
        val web = repo("web")
        sourceMap(api, web)
        val run = start(listOf("api", "web"), "feature-x")
        val cloned = listOf(api, web).map { repo ->
            assertEquals("feature-x", git(clone(run, repo.name), "branch", "--show-current"))
            mapOf("name" to repo.name, "path" to "repos/${repo.name}", "commit" to git(repo.remote, "rev-parse", "main"))
        }
        assertEquals(cloned, runtime.getVariable(run, "clone"))
        assertEquals(cloned, runtime.getVariable(run, "clones"))
    }

    @Test
    fun `a local checkout git can't borrow from is skipped, and the repo is cloned from its remote alone`() {
        val api = repo("api")
        // Any borrowing clone that fails, as one from a checkout with a stale commit-graph does: here, a shallow one, which git won't borrow from.
        api.local.resolve(".git/shallow").writeText(git(api.local, "rev-parse", "HEAD") + "\n")
        sourceMap(api)
        browser.import("clone-repos.bpmn")
        // Started with the repos as the scout names them: picked on the start form, they would come without their local checkout.
        val run = runtime.startProcessInstanceByKey("clone-repos", mapOf("repos" to listOf("api"), "branch" to "feature-x")).id
        eventually { assertNotNull(runtime.getVariable(run, "again")) }
        assertEquals(git(api.remote, "rev-parse", "main"), git(clone(run, "api"), "rev-parse", "HEAD"))
        assertEquals("", git(clone(run, "api"), "status", "--porcelain"))
    }

    @Test
    fun `a clone already on disk from an earlier, failed attempt is still in clones`() {
        val api = repo("api")
        val web = repo("web")
        sourceMap(api, web)
        val moved = Files.move(web.remote, web.remote.resolveSibling("gone.git"))
        browser.import("clone-repos.bpmn")
        val run = runtime.startProcessInstanceByKey("clone-repos", mapOf("repos" to listOf("api", "web"), "branch" to "feature-x")).id
        // The first attempt clones api, then fails on web, whose remote is gone, so it completes nothing.
        fun failed() = externalTasks.createExternalTaskQuery().processInstanceId(run).noRetriesLeft().singleResult()
        eventually { assertNotNull(failed()) }
        assertEquals(listOf("api"), clones(workspace(run)).map { it.fileName.toString() })
        Files.move(moved, web.remote)
        externalTasks.setRetries(failed().id, 1)
        eventually { assertNotNull(runtime.getVariable(run, "again")) }
        val clones = listOf(api, web).map { mapOf("name" to it.name, "path" to "repos/${it.name}", "commit" to git(it.remote, "rev-parse", "main")) }
        assertEquals(clones.drop(1), runtime.getVariable(run, "clone")) // this attempt's Result: only web
        assertEquals(clones.reversed(), runtime.getVariable(run, "clones"))
    }

    @Test
    fun `a failed Clone repos offers Retry and Cancel the Run, not an Agent's session, and Retry runs it again with the same inputs`() {
        val api = repo("api")
        val web = repo("web")
        sourceMap(api)
        browser.import("clone-repos.bpmn")
        val run = runtime.startProcessInstanceByKey("clone-repos", mapOf("repos" to listOf("api", "web"), "branch" to "feature-x")).id
        eventually { assertNotNull(runtime.createIncidentQuery().processInstanceId(run).singleResult()) }
        val entry = "/notifications/" + runtime.createIncidentQuery().processInstanceId(run).singleResult().id
        val page = browser.get(entry).body
        assertContains(page, "The Source map work has no repo web")
        assertContains(page, """value="retry" data-pending="Retrying…">Retry</button>""")
        assertContains(page, """value="cancel" data-pending="Cancelling…">Cancel the Run</button>""")
        listOf("The session so far", "Your instruction", "Continue the session", "Start fresh").forEach { assertFalse(it in page, it) }
        val drawer = assertNotNull(browser.events("run=$run&drawer=clone", "drawer").poll(10, TimeUnit.SECONDS))
        assertContains(drawer, """value="retry" data-pending="Retrying…">Retry</button>""")
        assertFalse("Your instruction" in drawer)
        listOf("continue", "fresh", "push").forEach { assertEquals(400, browser.post(entry, mapOf("action" to it, "instruction" to "Go on")).status, it) }

        sourceMap(api, web)
        browser.post(entry, mapOf("action" to "retry", "instruction" to ""))
        eventually { assertNotNull(runtime.getVariable(run, "again")) }
        assertEquals(listOf("api", "web"), clones(workspace(run)).map { it.fileName.toString() }.sorted())
    }

    @Test
    fun `running it again with the same repos does nothing, and nor does an empty list`() {
        val api = repo("api")
        sourceMap(api)
        val run = start(listOf("api"), name = "Tiny todo app")
        val clones = runtime.getVariable(run, "clones")
        assertEquals(emptyList<Any>(), runtime.getVariable(run, "again"))
        assertEquals(clones, runtime.getVariable(run, "clones"))
        assertEquals(1, (clones as List<*>).size)
        assertEquals("ants/tiny-todo-app", git(clone(run, "api"), "branch", "--show-current")) // no branch given

        val none = start(emptyList())
        assertEquals(emptyList<Any>(), runtime.getVariable(none, "clone"))
        assertEquals(emptyList<Any>(), runtime.getVariable(none, "clones"))
    }

    @Test
    fun `a branch held by another live Run gets a counter suffix`() {
        val api = repo("api")
        sourceMap(api)
        val first = start(listOf("api"), "shared")
        val second = start(listOf("api"), "shared")
        val third = start(listOf("api"), "shared")
        assertEquals(listOf("shared", "shared-2", "shared-3"), listOf(first, second, third).map { git(clone(it, "api"), "branch", "--show-current") })
        val named = List(2) { start(listOf("api"), name = "Same name") }
        assertEquals(listOf("ants/same-name", "ants/same-name-2"), named.map { git(clone(it, "api"), "branch", "--show-current") })
    }

    @Test
    fun `an existing remote work branch is checked out, not recreated`() {
        val api = repo("api")
        git(api.local, "switch", "-q", "-c", "started")
        git(api.local, "commit", "-q", "--allow-empty", "-m", "Begun elsewhere")
        git(api.local, "push", "-q", "origin", "started")
        sourceMap(api)
        val run = start(listOf("api"), "started")
        val clone = clone(run, "api")
        assertEquals("started", git(clone, "branch", "--show-current"))
        assertEquals(git(api.remote, "rev-parse", "started"), git(clone, "rev-parse", "HEAD"))
        assertEquals("origin/started", git(clone, "rev-parse", "--abbrev-ref", "started@{upstream}"))
        assertEquals(git(api.remote, "rev-parse", "started"), (runtime.getVariable(run, "clone") as List<*>).map { (it as Map<*, *>)["commit"] }.single())
    }

    @Test
    fun `a default work branch any of the Run's remotes already has gets a counter suffix`() {
        val api = repo("api")
        val web = repo("web")
        git(web.local, "push", "-q", "origin", "HEAD:ants/same-name")
        sourceMap(api, web)
        val run = start(listOf("api", "web"), name = "Same name")
        assertEquals(listOf("ants/same-name-2", "ants/same-name-2"), listOf("api", "web").map { git(clone(run, it), "branch", "--show-current") })
    }

    @Test
    fun `git push from a clone fails`() {
        val api = repo("api")
        sourceMap(api)
        val clone = clone(start(listOf("api"), "fix"), "api")
        git(clone, "commit", "-q", "--allow-empty", "-m", "Mine")
        assertFails { git(clone, "push", "origin", "fix") }
        assertFails { git(clone, "push") }
        assertEquals("", git(api.remote, "branch", "--list", "fix"))
    }
}

/** A repo for the Source map: a bare remote with one commit on `main`, and a local checkout of it. */
class Remote(val name: String, val remote: Path, val local: Path)

fun repo(name: String): Remote {
    val remote = Files.createTempDirectory("remote").resolve("$name.git")
    git(remote.parent, "init", "-q", "--bare", "-b", "main", "$remote")
    val local = Files.createTempDirectory("local").resolve(name)
    git(local.parent, "clone", "-q", "$remote", "$local")
    git(local, "commit", "-q", "--allow-empty", "-m", "First")
    git(local, "push", "-q", "origin", "HEAD:main")
    return Remote(name, remote, local)
}

/** `settings.yaml` lines for a Source map `work` of [repos]. */
fun sourceMapLines(vararg repos: Remote): String {
    val map = Files.createTempDirectory("source-map").resolve("work.md")
    map.writeText(repos.joinToString("") { "- ${it.name}: ${it.local} (${it.remote})\n" })
    return "source-maps:\n  work: $map\n"
}

/** Runs git in [dir] and gives its output, trimmed; fails with the output when git does. */
fun git(dir: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-c", "user.name=Ants", "-c", "user.email=ants@example.com") + args)
        .directory(dir.toFile()).redirectErrorStream(true).start()
    val output = process.inputReader().readText().trim()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $output" }
    return output
}
