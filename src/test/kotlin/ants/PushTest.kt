package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.HistoryService
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PushTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var history: HistoryService

    /**
     * Starts a Run of `push.bpmn` with a Source map of [repos] on the work branch `feature-x`, commits "Mine in <repo>"
     * in each clone once Push waits, and gives the Run.
     */
    private fun start(vararg repos: Remote): String {
        settings(null, sourceMapLines(*repos))
        browser.import("push.bpmn")
        browser.get("/processes/ship")
        val run = browser.post("/processes/ship/runs", repos.map { "repos" to it.name } + ("branch" to "feature-x")).path.removePrefix("/runs/")
        entry(run)
        for (repo in repos) git(clone(run, repo.name), "commit", "-q", "--allow-empty", "-m", "Mine in ${repo.name}")
        return run
    }

    private fun clone(run: String, name: String): Path = home.runWorkspace(run).resolve("repos/$name")

    /** The Run's row on the Notification list, or null. */
    private fun row(run: String) = Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    /** The path of the Run's entry, once it is on the Notification list. */
    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    private fun push(run: String) = browser.post(entry(run), mapOf("action" to "push", "instruction" to ""))

    private fun completed(run: String) = eventually { assertContains(browser.get("/runs/$run").body, "State: Completed") }

    /** The Push Result of the ended Run [run]. */
    private fun result(run: String) = history.createHistoricVariableInstanceQuery().processInstanceId(run).variableName("push").singleResult().value as List<*>

    private fun refs(repo: Remote) = git(repo.remote, "for-each-ref", "--format=%(refname) %(objectname)").lines().toSet()

    @Test
    fun `Push waits on its own page with each repo's remote, branch, base and commits, and fast-forwards only the work branch`() {
        val api = repo("api")
        val web = repo("web")
        val before = listOf(api, web).associateWith(::refs)
        val run = start(api, web)
        for (cell in listOf("↑ [push]", "Push", "Push feature-x to api, web")) assertContains(row(run)!!, cell)
        val page = browser.get(entry(run)).body
        for (text in listOf("${api.remote}", "${web.remote}", "feature-x", "main", "Mine in api", "Mine in web", "Push", "Cancel the Run")) assertContains(page, text)
        assertFalse("Skip" in page)

        val heads = listOf(api, web).associateWith { git(clone(run, it.name), "rev-parse", "HEAD") }
        val landed = push(run)
        assertEquals("/notifications", landed.path)
        assertContains(landed.body, """<div class="toast" role="status"><b>Pushing</b> Push feature-x to api, web. The Run goes on once it is pushed.""")
        assertFalse("class=\"toast\"" in browser.get("/notifications").body) // shown once
        completed(run)
        for (repo in listOf(api, web)) assertEquals(before.getValue(repo) + "refs/heads/feature-x ${heads[repo]}", refs(repo))
        assertEquals(
            listOf(api, web).map { repo ->
                mapOf(
                    "repo" to repo.name, "remote" to "${repo.remote}", "branch" to "feature-x", "base" to "main", "isNew" to true,
                    "commits" to listOf(mapOf("sha" to heads[repo], "subject" to "Mine in ${repo.name}")), "pushed" to true, "error" to null, "compareUrl" to null,
                )
            },
            result(run),
        )
    }

    @Test
    fun `a non-fast-forward rejection fails before anything is pushed, and Continue after a push by hand completes the Task`() {
        val api = repo("api")
        val web = repo("web")
        git(web.local, "switch", "-q", "-c", "feature-x")
        git(web.local, "push", "-q", "origin", "feature-x")
        val run = start(api, web)
        git(web.local, "commit", "-q", "--allow-empty", "-m", "Theirs")
        git(web.local, "push", "-q", "origin", "feature-x")

        push(run)
        eventually { assertContains(assertNotNull(row(run)), "✕ [failed]") }
        assertContains(row(run)!!, "Pushed: none. Not pushed: api, web.")
        val page = browser.get(entry(run)).body
        assertContains(page, "web: git push failed: To ${web.remote}\n ! [rejected]        feature-x -&gt; feature-x (fetch first)") // git's own words
        assertContains(page, "git -C ${clone(run, "web")} push ${web.remote} feature-x")
        assertFalse("""value="push"""" in page)
        assertEquals("", git(api.remote, "branch", "--list", "feature-x")) // the dry runs come first

        git(clone(run, "web"), "pull", "-q", "--rebase", "${web.remote}", "feature-x")
        for (repo in listOf(api, web)) git(clone(run, repo.name), "push", "-q", "${repo.remote}", "feature-x")
        val heads = listOf(api, web).associateWith { git(clone(run, it.name), "rev-parse", "HEAD") }
        browser.post(entry(run), mapOf("action" to "continue", "instruction" to ""))
        completed(run)
        val result = result(run).map { it as Map<*, *> }
        assertEquals(listOf(true, true), result.map { it["pushed"] })
        assertEquals(listOf(true, false), result.map { it["isNew"] })
        for (repo in listOf(api, web)) assertEquals(heads[repo], git(repo.remote, "rev-parse", "feature-x"))
    }

    @Test
    fun `a rejection after the dry runs names the repos that pushed and those that didn't, with git's message`() {
        val api = repo("api")
        val web = repo("web")
        web.remote.resolve("hooks/pre-receive").run {
            writeText("#!/bin/sh\necho 'No pushes on Sundays' >&2\nexit 1\n")
            toFile().setExecutable(true)
        }
        val run = start(api, web)
        val head = git(clone(run, "api"), "rev-parse", "HEAD")

        push(run)
        eventually { assertContains(assertNotNull(row(run)), "✕ [failed]") }
        assertContains(row(run)!!, "Pushed: api. Not pushed: web.")
        assertContains(browser.get(entry(run)).body, "No pushes on Sundays")
        assertEquals(head, git(api.remote, "rev-parse", "feature-x"))
        assertEquals("", git(web.remote, "branch", "--list", "feature-x"))
    }

    @Test
    fun `a clone off the work branch is refused, and nothing is pushed`() {
        val api = repo("api")
        val web = repo("web")
        val run = start(api, web)
        git(clone(run, "web"), "switch", "-q", "-c", "elsewhere")

        push(run)
        eventually { assertContains(assertNotNull(row(run)), "✕ [failed]") }
        assertContains(row(run)!!, "Pushed: none. Not pushed: api, web.")
        assertContains(browser.get(entry(run)).body, "web: The clone is on elsewhere, not the work branch feature-x.")
        for (repo in listOf(api, web)) assertEquals("", git(repo.remote, "branch", "--list", "feature-x", "elsewhere"))
    }

    @Test
    fun `a waiting Push takes no Continue, since there is no Skip`() {
        val api = repo("api")
        val run = start(api)
        val refused = browser.post(entry(run), mapOf("action" to "continue", "instruction" to ""))
        assertEquals(400, refused.status)
        assertContains(assertNotNull(row(run)), "↑ [push]")
        assertEquals("", git(api.remote, "branch", "--list", "feature-x"))
    }

    @Test
    fun `Cancel the Run pushes nothing and keeps the Run workspace`() {
        val api = repo("api")
        val run = start(api)
        val drawer = assertNotNull(browser.events("run=$run&drawer=push", "drawer").poll(10, TimeUnit.SECONDS))
        assertContains(drawer, """<a href="${entry(run)}">Push or cancel the Run</a>""")
        assertFalse("Continue the session" in drawer)
        val cancelled = browser.post(entry(run), mapOf("action" to "cancel", "instruction" to ""))
        assertContains(cancelled.body, "State: Cancelled")
        assertTrue(clone(run, "api").resolve(".git").toFile().isDirectory)
        assertEquals("", git(api.remote, "branch", "--list", "feature-x"))
    }

    @Test
    fun `the Report lists the Push Result, with a compare link for a GitHub remote`() {
        val api = repo("api")
        val web = repo("web")
        web.remote.resolve("hooks/pre-receive").run {
            writeText("#!/bin/sh\necho 'No pushes on Sundays' >&2\nexit 1\n")
            toFile().setExecutable(true)
        }
        val run = start(api, web)
        // The clone's remote reads as GitHub, while git pushes to the bare repo.
        git(clone(run, "api"), "remote", "set-url", "origin", "https://github.com/oyvij/api.git")
        git(clone(run, "api"), "config", "url.${api.remote}.insteadOf", "https://github.com/oyvij/api.git")
        push(run)
        eventually { assertContains(assertNotNull(row(run)), "✕ [failed]") }
        browser.post(entry(run), mapOf("action" to "continue", "instruction" to "")) // web stays unpushed
        completed(run)

        val report = browser.get("/runs/$run/report").body
        assertContains(report, "<h2>Push</h2>")
        assertContains(
            report,
            """<li><strong>api</strong> · pushed <code>feature-x</code> to <code>https://github.com/oyvij/api.git</code> · new branch off <code>main</code> · 1 commit · """ +
                """<a rel="nofollow" href="https://github.com/oyvij/api/compare/main...feature-x">Compare</a></li>""",
        )
        assertContains(report, "<li><strong>web</strong> · not pushed: The remote's feature-x isn't where this clone has it.</li>")
    }
}
