package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.TaskService
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A Run with repos picked on its start form: the scout takes them as given, and the Run clones exactly them, against bare remotes. */
class PickedReposTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    lateinit var runtime: RuntimeService

    @Autowired
    lateinit var tasks: TaskService
    private val claude = FakeClaude()
    private val api = repo("api")
    private val web = repo("web")

    /** A Vault holding one Note. */
    private val notes: Path = Files.createTempDirectory("vault").also { it.resolve("Billing.md").writeText("Invoices go out monthly.\n") }

    /** Where the fake gh keeps the repos it creates, `<owner>/<name>.git`, and the log of its calls. */
    private val github: Path = Files.createTempDirectory("github")
    private val ghLog: Path = github.resolve("calls.log")

    /**
     * A fake gh, logged in, that knows the repos under [github]: `repo view` finds one or fails, `repo create` makes one
     * with a README on `main`, and every call is logged.
     */
    private val gh: Path = Files.createTempDirectory("fake-gh").resolve("gh").also {
        it.writeText(
            """
            #!/bin/sh
            echo "$*" >> "$ghLog"
            case "$1 $2" in
              "repo view") [ -d "$github/$3.git" ] || { echo "GraphQL: Could not resolve to a Repository with the name '$3'." >&2; exit 1; }
                printf '{"url":"%s","sshUrl":"%s"}\n' "$github/$3.git" "$github/$3.git" ;;
              "repo create") git init -q --bare -b main "$github/$3.git" && tmp=$(mktemp -d) && git clone -q "$github/$3.git" "${'$'}tmp/r" 2>/dev/null &&
                echo "# $3" > "${'$'}tmp/r/README.md" && git -C "${'$'}tmp/r" add README.md &&
                git -C "${'$'}tmp/r" -c user.name=gh -c user.email=gh@example.com -c commit.gpgsign=false commit -q -m "Initial commit" &&
                git -C "${'$'}tmp/r" push -q origin HEAD:main ;;
              "config get") echo https ;;
            esac
            """.trimIndent() + "\n",
        )
        it.toFile().setExecutable(true)
    }

    private fun calls() = if (ghLog.exists()) ghLog.readText().lines().filter(String::isNotEmpty) else emptyList()

    private fun result(output: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    /** `settings.yaml` with the fake claude and gh, a Source map of api and web, and the Vault [notes] when [vault]. */
    private fun settings(vault: Boolean = true) = home.resolve("settings.yaml").writeAtomically(
        "claude:\n  command: [${claude.command}]\ngh:\n  path: $gh\n" + sourceMapLines(api, web) + (if (vault) "vaults:\n  notes: $notes\n" else ""),
    )

    /** Starts Ship small with [repos] picked, named "Hello api"; the page it lands on. */
    private fun start(vararg repos: String): Browser.Page {
        browser.import("shipped/processes/ship-small.bpmn")
        browser.get("/processes/ship_small")
        return browser.post("/processes/ship_small/runs", listOf(NAME to "Hello api", "prompt" to "Add a hello endpoint", "branch" to "") + repos.map { "repos" to it })
    }

    /**
     * Plays Ship small with [repos] picked: the scout gives them back as they are, the proposal keeps the brief it read,
     * the implementer commits in [repo], the user pushes and approves the Notes Update the Vault proposes.
     */
    private fun shipSmall(repo: String, vararg repos: String): String {
        val picked = repos.joinToString(",") { "\"$it\"" }
        claude.session("scout", "write scout/brief.md Add a hello endpoint to $repo.\\n", result("""{"repos":[$picked],"branch":"","brief":"scout/brief.md"}"""))
        claude.session("propose-solution", "sh cp scout/brief.md \$launch/brief", "write proposal/proposal.md # Hello\\n", result("""{"proposal":"proposal/proposal.md"}"""))
        claude.session("implement", "sh git -C repos/$repo -c user.name=Ants -c user.email=ants@example.com -c commit.gpgsign=false commit -q --allow-empty -m 'Add hello'", result("""{"review":"No findings."}"""))
        claude.session("pr", result("""{"pullRequests":[]}"""))
        claude.session("update-knowledge", 1, "write vault/proposal.md One Note.\\n", result("""{"proposal":"vault/proposal.md","written":false}"""))
        claude.session("update-knowledge", 2, result("""{"written":true}"""))
        val page = start(*repos)
        assertEquals(200, page.status, page.body)
        val run = page.path.removePrefix("/runs/")
        val push = entry(run)
        assertContains(row(run)!!, "<i>↑</i>[push]")
        browser.post(push, mapOf("action" to "push", "instruction" to ""))
        eventually(20) { assertContains(assertNotNull(row(run)), "<i>✓</i>[approve]") } // the Notes
        browser.post(entry(run), mapOf("action" to "approve", "comment" to ""))
        eventually(20) { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        return run
    }

    @Test
    fun `Ship small with a repo picked gives the scout that repo and the Vault, clones only it, pushes its branch and updates the Vault`() {
        settings()
        val run = shipSmall("api", "api")

        val scout = claude.launches("scout").single()
        assertContains(scout.stdin, "\\\"repos\\\":[\\\"api\\\"]")
        assertContains(scout.stdin, "The Vault is $notes")
        assertContains(scout.argv.joinToString(" "), "--add-dir $notes")
        assertContains(scout.stdin, "\\\"sourceMap\\\":[{")
        assertEquals(2, claude.launches("update-knowledge").size)
        assertEquals("Add hello", git(api.remote, "log", "-1", "--format=%s", "ants/hello-api"))
        assertEquals("", git(web.remote, "branch", "--list", "ants/*"))
        assertContains(claude.launches("propose-solution").single().file("brief"), "Add a hello endpoint to api")
        val page = browser.get("/runs/$run").body.replace("&#34;", "\"")
        assertContains(page, """<dt>clones</dt><dd><code>[{"name":"api",""")
        assertFalse("\"name\":\"web\"" in page.substringAfter("<dt>clones</dt>").substringBefore("</dd>"))
        assertContains(page, "Repos picked: <code>${api.remote}</code>")
        assertFalse("No scout" in page || "no scout" in page)
        val report = browser.get("/runs/$run/report").body
        assertContains(report, "Repos picked: <code>${api.remote}</code>")
        assertFalse("No scout" in report)
    }

    @Test
    fun `a Run with a new repo picked creates it through gh, clones it, builds on the work branch and pushes it`() {
        settings()
        val run = shipSmall("app", "you/app")

        assertContains(claude.launches("scout").single().stdin, "\\\"repos\\\":[\\\"you/app\\\"]")
        assertContains(calls(), "repo create you/app --private --add-readme")
        assertEquals("Add hello", git(github.resolve("you/app.git"), "log", "-1", "--format=%s", "ants/hello-api"))
        assertEquals("Initial commit", git(github.resolve("you/app.git"), "log", "-1", "--format=%s", "main"))
        assertContains(browser.get("/runs/$run").body, "Repos picked: <code>you/app</code>")
    }

    @Test
    fun `a Run with a new repo picked, cancelled before Clone repos, creates nothing`() {
        settings()
        browser.import("wait-then-clone.bpmn")
        browser.get("/processes/wait-then-clone")
        val run = browser.post("/processes/wait-then-clone/runs", listOf("repos" to "you/app")).path.removePrefix("/runs/")
        entry(run)
        browser.post("/runs/$run/cancel", emptyMap())
        assertContains(browser.get("/runs/$run").body, """id="state">Cancelled<""")
        assertFalse(calls().any { it.startsWith("repo create") }, calls().toString())
        assertFalse(github.resolve("you").exists())
    }

    @Test
    fun `a Run with repos picked starts without a Vault, and the pre-Run check refuses a repo it can't clone and a new repo whose name is taken`() {
        settings(vault = false)
        browser.import("wait-then-clone.bpmn")
        val page = browser.get("/processes/wait-then-clone").body
        assertContains(page, "Configure a Vault")
        assertFalse("disabled" in page.substringAfter("""<section id="pre-run">""").substringBefore("</form>"))
        assertEquals(400, browser.post("/processes/wait-then-clone/runs", emptyMap()).status) // with no repos picked, the scout needs the Vault
        assertTrue(browser.post("/processes/wait-then-clone/runs", listOf("repos" to "api", "repos" to "you/app")).path.startsWith("/runs/"))

        Files.createDirectories(github.resolve("you/taken.git"))
        val refused = browser.post("/processes/wait-then-clone/runs", listOf("repos" to "/nowhere/gone.git you/taken"))
        assertEquals(400, refused.status)
        assertContains(refused.body, "git ls-remote can't reach /nowhere/gone.git.")
        assertContains(refused.body, "The repo you/taken already exists on GitHub.")
        assertFalse("Configure a Vault" in refused.body.substringAfter("""<section id="pre-run">""").substringBefore("</section>"))
    }

    @Test
    fun `a Run started before repos stopped scoping, with its scope and no Profile, still clones exactly its scope and completes`() {
        settings()
        browser.import("wait-then-clone.bpmn")
        val run = runtime.startProcessInstanceByKey("wait-then-clone", mapOf("repos" to arrayListOf<String>(), SCOPE to arrayListOf("${api.remote}"))).id
        tasks.complete(tasks.createTaskQuery().processInstanceId(run).singleResult().id)
        eventually(20) { assertContains(browser.get("/runs/$run").body, """id="state">Completed<""") }
        val page = browser.get("/runs/$run").body.replace("&#34;", "\"")
        assertContains(page, """<dt>clones</dt><dd><code>[{"name":"api",""")
        assertFalse("\"name\":\"web\"" in page.substringAfter("<dt>clones</dt>").substringBefore("</dd>"))
        assertContains(page, "Repos picked: <code>${api.remote}</code>")
    }

    @Test
    fun `each default Process's start form has an optional Repos field`() {
        settings()
        for ((file, key) in listOf("ship-small" to "ship_small", "ship-with-me" to "ship_with_me", "ship-by-proxy" to "ship_by_proxy", "ship-as-proposed" to "ship_as_proposed", "ship-overnight" to "ship_overnight")) {
            browser.import("shipped/processes/$file.bpmn")
            val form = browser.get("/processes/$key").body.substringAfter("action=\"/processes/$key/runs\"").substringBefore("</form>")
            assertContains(form, """<div class="row picks">""", message = key)
            assertContains(form, """<input type="checkbox" name="repos" value="api">""", message = key)
            assertContains(form, """<input name="repos" """, message = key)
        }
    }
}
