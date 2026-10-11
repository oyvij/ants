package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

class CloneReposTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var runtime: RuntimeService

    /** A repo in the Source map: a bare remote with one commit on `main`, and a local checkout of it. */
    private class Repo(val name: String, val remote: Path, val local: Path)

    private fun repo(name: String): Repo {
        val remote = Files.createTempDirectory("remote").resolve("$name.git")
        git(remote.parent, "init", "-q", "--bare", "-b", "main", "$remote")
        val local = Files.createTempDirectory("local").resolve(name)
        git(local.parent, "clone", "-q", "$remote", "$local")
        git(local, "commit", "-q", "--allow-empty", "-m", "First")
        git(local, "push", "-q", "origin", "HEAD:main")
        return Repo(name, remote, local)
    }

    /** `settings.yaml` with a Source map of [repos]. */
    private fun sourceMap(vararg repos: Repo) {
        val map = Files.createTempDirectory("source-map").resolve("work.md")
        map.writeText(repos.joinToString("") { "- ${it.name}: ${it.local} (${it.remote})\n" })
        settings(null, "source-maps:\n  work: $map\n")
    }

    /** Starts a Run of `clone-repos.bpmn` with [repos] and [branch], and waits until both its Clone repos Tasks are done. */
    private fun start(repos: List<String>, branch: String = ""): String {
        browser.import("clone-repos.bpmn")
        browser.get("/processes/clone-repos")
        val run = browser.post("/processes/clone-repos/runs", repos.map { "repos" to it } + ("branch" to branch)).path.removePrefix("/runs/")
        eventually { assertNotNull(runtime.getVariable(run, "again"), browser.get("/runs/$run").body) }
        return run
    }

    private fun clone(run: String, name: String): Path = home.runWorkspace(run).resolve("repos/$name")

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
    fun `running it again with the same repos does nothing, and nor does an empty list`() {
        val api = repo("api")
        sourceMap(api)
        val run = start(listOf("api"))
        val clones = runtime.getVariable(run, "clones")
        assertEquals(emptyList<Any>(), runtime.getVariable(run, "again"))
        assertEquals(clones, runtime.getVariable(run, "clones"))
        assertEquals(1, (clones as List<*>).size)
        assertEquals("ants/$run", git(clone(run, "api"), "branch", "--show-current")) // no branch given

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

/** Runs git in [dir] and gives its output, trimmed; fails with the output when git does. */
fun git(dir: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-c", "user.name=Ants", "-c", "user.email=ants@example.com") + args)
        .directory(dir.toFile()).redirectErrorStream(true).start()
    val output = process.inputReader().readText().trim()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $output" }
    return output
}
