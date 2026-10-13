package ants

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Profiles, each a Vault and a Source map, picked per Process and per Run, against two Vaults and two Source maps. */
class ProfileTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    /** A repo named `api` in each Source map, each its own bare remote with its own first commit. */
    private val workApi = repo("api")
    private val privateApi = repo("api").also {
        git(it.local, "commit", "-q", "--allow-empty", "-m", "Private")
        git(it.local, "push", "-q", "origin", "HEAD:main")
    }

    private val workVault: Path = Files.createTempDirectory("work-vault")
    private val privateVault: Path = Files.createTempDirectory("private-vault")

    private fun map(name: String, repo: Remote) = Files.createTempDirectory("source-map").resolve("$name.md")
        .also { it.writeText("- api: ${repo.local} (${repo.remote})\n") }

    private val workMap = map("work", workApi)
    private val privateMap = map("private", privateApi)

    /** Two Profiles, `work` (the default) and `private`, each pairing its own Vault and Source map. */
    private val profiles = """
        vaults:
          work: $workVault
          private: $privateVault
        source-maps:
          work: $workMap
          private: $privateMap
        profiles:
          work:
            vault: work
            source-map: work
          private:
            vault: private
            source-map: private
        default-profile: work

    """.trimIndent()

    /** The writer runs `draft`, which leaves a Note in whatever Vault its hand-off line names. */
    private fun setUp(settings: String = profiles) {
        agentTask(claude)
        home.resolve("settings.yaml").writeAtomically("claude:\n  command: [${claude.command}]\n$settings")
        claude.session(
            "draft",
            """sh v=$(sed -n 's/.*The Vault is \(.*\) and the Source map is.*/\1/p' "${'$'}launch/stdin"); [ -n "${'$'}v" ] && echo note > "${'$'}v/Note.md"""",
            """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Noted.","output":{}}}""",
        )
        browser.import("profile.bpmn")
    }

    private fun page(run: String) = browser.get("/runs/$run").body.replace("&#34;", "\"")

    private fun start(vararg values: Pair<String, String>): String {
        val page = browser.post("/processes/profile/runs", values.toList())
        assertTrue(page.path.startsWith("/runs/"), page.body)
        val run = page.path.removePrefix("/runs/")
        eventually(20) { assertContains(page(run), """id="state">Completed<""") }
        return run
    }

    @Test
    fun `two Runs of one Process, one per Profile, each clone from and write to their own Profile's Source map and Vault`() {
        setUp()
        val form = browser.get("/processes/profile").body
        assertContains(form, """<select name="$PROFILE"""")
        assertContains(form, """<option selected>work</option>""")
        // The sub line says where the Profile came from: the default here, picked in the URL below.
        assertContains(form, "Profile <b>work</b>, the default")
        assertContains(browser.get("/processes/profile?$PROFILE=private").body, "Profile <b>private</b>, picked here")

        val work = start(PROFILE to "work")
        val private = start(PROFILE to "private")

        val (workLaunch, privateLaunch) = claude.launches("draft")
        assertContains(workLaunch.stdin, "The Vault is $workVault and the Source map is $workMap.")
        assertContains(workLaunch.argv.zipWithNext(), "--add-dir" to "$workVault")
        assertContains(privateLaunch.stdin, "The Vault is $privateVault and the Source map is $privateMap.")
        assertContains(privateLaunch.argv.zipWithNext(), "--add-dir" to "$privateVault")
        assertFalse(workLaunch.argv.any { "$privateVault" in it } || privateLaunch.argv.any { "$workVault" in it })
        assertTrue(workVault.resolve("Note.md").exists())
        assertTrue(privateVault.resolve("Note.md").exists())

        val workPage = page(work)
        assertContains(workPage, "<dt>sourceMap</dt><dd><code>[{\"name\":\"api\",\"remote\":\"${workApi.remote}\"}]</code></dd>")
        assertContains(workPage, "\"commit\":\"${git(workApi.remote, "rev-parse", "main")}\"")
        assertContains(workPage, """<span id="profile">Profile work</span>""")
        val privatePage = page(private)
        assertContains(privatePage, "<dt>sourceMap</dt><dd><code>[{\"name\":\"api\",\"remote\":\"${privateApi.remote}\"}]</code></dd>")
        assertContains(privatePage, "\"commit\":\"${git(privateApi.remote, "rev-parse", "main")}\"")
        assertContains(privatePage, """<span id="profile">Profile private</span>""")
    }

    /** Deploys [xml] as the file [name], refusing nothing. */
    private fun deploy(name: String, xml: String) {
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", name, xml.toByteArray()).body)
    }

    /** profile.bpmn naming its Profile [profile]. */
    private fun naming(profile: String) = deploy(
        "profile.bpmn",
        javaClass.getResource("/profile.bpmn")!!.readText().replace(
            """operaton:historyTimeToLive="180">""",
            """operaton:historyTimeToLive="180"><bpmn:extensionElements><operaton:properties><operaton:property name="ants.profile" value="$profile" /></operaton:properties></bpmn:extensionElements>""",
        ),
    )

    @Test
    fun `a Process that names its Profile prefills the start form with it and runs in it unless the Run picks another`() {
        setUp()
        naming("private")
        val form = browser.get("/processes/profile").body
        assertContains(form, """<option selected>private</option>""")
        assertContains(form, """<span class="pill p-ready">Ready</span>""")
        assertContains(form, "Profile <b>private</b>, set on the diagram")
        // As a Schedule's Run does, posting no Profile.
        assertContains(page(start()), """<span id="profile">Profile private</span>""")
        assertContains(claude.launches("draft").single().stdin, "The Vault is $privateVault")
        assertContains(page(start(PROFILE to "work")), """<span id="profile">Profile work</span>""")

        naming("gone")
        assertContains(browser.get("/processes/profile").body, "The Profile gone isn't in the settings.")
        assertEquals(400, browser.post("/processes/profile/runs", emptyList()).status)
    }

    @Test
    fun `a named, scoped Run gets its folder from its name and no Vault, even when its Profile has one`() {
        setUp()
        naming("private")
        val run = start(NAME to "Tiny app", "repos" to "${workApi.remote}")
        assertEquals(home.resolve("runs/tiny-app-$run"), workspace(run))
        assertTrue(workspace(run).resolve("tasks").exists())
        val launch = claude.launches("draft").single()
        assertFalse("Vault" in launch.stdin || "Source map" in launch.stdin, launch.stdin)
        assertFalse(launch.argv.any { "$privateVault" in it || "$workVault" in it })
        assertFalse(privateVault.resolve("Note.md").exists())
        val page = page(run)
        assertContains(page, "<dt>sourceMap</dt><dd><code>[]</code></dd>")
        assertFalse("id=\"profile\"" in page)
    }

    @Test
    fun `with one Profile, or none, the start form offers no Profile and the Run uses its Vault`() {
        setUp(
            """
            vaults:
              work: $workVault
            source-maps:
              work: $workMap
            profiles:
              work:
                vault: work
                source-map: work

            """.trimIndent(),
        )
        assertFalse(PROFILE in browser.get("/processes/profile").body)
        assertContains(page(start()), """<span id="profile">Profile work</span>""")

        setUp("vaults:\n  work: $workVault\nsource-maps:\n  work: $workMap\n")
        assertFalse(PROFILE in browser.get("/processes/profile").body)
        val run = start()
        assertFalse("id=\"profile\"" in page(run))
        assertContains(claude.launches("draft").last().stdin, "The Vault is $workVault and the Source map is $workMap.")
    }

    @Test
    fun `a called Process uses its top Run's Profile`() {
        setUp()
        deploy(
            "caller.bpmn",
            """<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="caller" name="Caller" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start" />
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="call" />
                <bpmn:callActivity id="call" calledElement="profile" />
                <bpmn:sequenceFlow id="f2" sourceRef="call" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>""",
        )
        browser.get("/processes/caller")
        val run = browser.post("/processes/caller/runs", listOf(PROFILE to "private")).path.removePrefix("/runs/")
        eventually(20) { assertContains(page(run), """id="state">Completed<""") }
        assertContains(claude.launches("draft").single().stdin, "The Vault is $privateVault and the Source map is $privateMap.")
        assertTrue(privateVault.resolve("Note.md").exists())
        assertFalse(workVault.resolve("Note.md").exists())
    }

    /** Deploys [key], a Process whose only Task calls [called], with the start form's Repos field when [repos]. */
    private fun caller(key: String, called: String, repos: Boolean = false) = deploy(
        "$key.bpmn",
        """<?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="$key" name="$key" isExecutable="true" operaton:historyTimeToLive="180">
            <bpmn:startEvent id="start">${if (!repos) "" else """<bpmn:extensionElements><operaton:formData><operaton:formField id="repos" label="Repos" type="string"><operaton:properties><operaton:property id="ants.type" value="repos" /></operaton:properties></operaton:formField></operaton:formData></bpmn:extensionElements>"""}</bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="call" />
            <bpmn:callActivity id="call" calledElement="$called" />
            <bpmn:sequenceFlow id="f2" sourceRef="call" targetRef="end" />
            <bpmn:endEvent id="end" />
          </bpmn:process>
        </bpmn:definitions>""",
    )

    @Test
    fun `a Process called two levels down uses its top Run's Profile, and in a scoped Run its scope`() {
        setUp()
        caller("middle", "profile")
        caller("top", "middle", repos = true)
        browser.get("/processes/top")
        val run = browser.post("/processes/top/runs", listOf(PROFILE to "private")).path.removePrefix("/runs/")
        eventually(20) { assertContains(page(run), """id="state">Completed<""") }
        val launch = claude.launches("draft").single().stdin.replace("\\\"", "\"")
        assertContains(launch, "The Vault is $privateVault and the Source map is $privateMap.")
        assertContains(launch, "\"sourceMap\":[{\"name\":\"api\",\"remote\":\"${privateApi.remote}\"}]")

        val scoped = browser.post("/processes/top/runs", listOf("repos" to "${workApi.remote}")).path.removePrefix("/runs/")
        eventually(20) { assertContains(page(scoped), """id="state">Completed<""") }
        val scopedLaunch = claude.launches("draft").last().stdin.replace("\\\"", "\"")
        assertFalse("Vault" in scopedLaunch || "Source map" in scopedLaunch, scopedLaunch)
        assertContains(scopedLaunch, "\"sourceMap\":[]")
    }
}
