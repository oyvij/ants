package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Each instance of a parallel multi-instance works in a worktree of its own, and lands on the work branch when done. */
class LandingTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    @Autowired
    lateinit var runtime: RuntimeService

    private val init = """{"type":"system","subtype":"init","session_id":"${'$'}SESSION","apiKeySource":"none"}"""
    private val done = """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION",""" +
        """"structured_output":{"outcome":"done","summary":"Done.","output":{}}}"""

    /** The fake's lines for Stop: it reads the interrupt, then ends the way claude does. */
    private val stopped = arrayOf(
        "read",
        """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"${'$'}SESSION","terminal_reason":"aborted_tools"}""",
        "exit 1",
    )

    /** The Agent `writer` with `plan` and `implement`, and a Source map of [repos]; `plan` gives [items]. */
    private fun setUp(vararg repos: Remote, items: Int = 2, agentTasks: Int = 3) {
        agentTask(
            claude,
            mapOf("plan" to """{"type":"object","properties":{"items":{"type":"array"}}}""", "implement" to null),
            extra = "agent-tasks: $agentTasks\n" + sourceMapLines(*repos),
            env = listOf("PATH", "HOME"),
        )
        val list = (1..items).joinToString(",") { "\"$it\"" }
        claude.session("plan", """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":{"items":[$list]}}}""")
    }

    /** A session line that commits [file], holding [text], in the clone of [repo] the Agent works in. */
    private fun commit(repo: String, file: String, text: String = file) =
        "sh cd repos/$repo && printf '$text' > $file && git add $file && git -c user.name=Agent -c user.email=agent@example.com -c commit.gpgsign=false commit -qm $file"

    /** Starts a Run that clones [repos], plans, implements each item in a parallel multi-instance, then waits. */
    private fun start(vararg repos: String): String {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="landing" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:extensionElements>
                    <operaton:formData>
                      <operaton:formField id="repos" label="Repos" type="string">
                        <operaton:properties><operaton:property id="ants.type" value="repos" /></operaton:properties>
                      </operaton:formField>
                    </operaton:formData>
                  </bpmn:extensionElements>
                </bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="clone" />
                <bpmn:serviceTask id="clone" operaton:type="external" operaton:topic="clone-repos">
                  <bpmn:extensionElements>
                    <operaton:inputOutput><operaton:inputParameter name="repos">${'$'}{repos}</operaton:inputParameter></operaton:inputOutput>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f2" sourceRef="clone" targetRef="plan" />
                ${agentTask("plan", "plan")}
                <bpmn:sequenceFlow id="f3" sourceRef="plan" targetRef="implement" />
                ${agentTask("implement", "implement", """<bpmn:multiInstanceLoopCharacteristics isSequential="false" operaton:collection="${'$'}{plan.output.items}" />""")}
                <bpmn:sequenceFlow id="f4" sourceRef="implement" targetRef="wait" />
                <bpmn:userTask id="wait" name="Wait" />
                <bpmn:sequenceFlow id="f5" sourceRef="wait" targetRef="end" />
                <bpmn:endEvent id="end" />
              </bpmn:process>
            </bpmn:definitions>"""
        browser.get("/processes")
        browser.upload("/processes", "file", "landing.bpmn", xml.toByteArray())
        browser.get("/processes/landing")
        return browser.post("/processes/landing/runs", repos.map { "repos" to it }).path.removePrefix("/runs/")
    }

    private fun agentTask(id: String, skill: String, loop: String = "") = """
        <bpmn:serviceTask id="$id" operaton:type="external" operaton:topic="agent">
          <bpmn:extensionElements>
            <operaton:properties>
              <operaton:property name="ants.agent" value="writer" />
              <operaton:property name="ants.skill" value="$skill" />
            </operaton:properties>
          </bpmn:extensionElements>
          $loop
        </bpmn:serviceTask>"""

    private fun workspace(run: String): Path = home.runWorkspace(run).toRealPath()

    private fun clone(run: String, repo: String) = workspace(run).resolve("repos/$repo")

    /** Waits until every instance of `implement` has landed and the Run waits after it. */
    private fun landed(run: String) = eventually { assertNotNull(runtime.getVariable(run, "implement"), browser.get("/runs/$run").body) }

    /** The files on the work branch of [run]'s clone of [repo]. */
    private fun files(run: String, repo: String) = git(clone(run, repo), "ls-tree", "--name-only", "HEAD").lines().filter(String::isNotEmpty).toSet()

    /** What `sh ls … > "$launch/seen"` saw in the [n]th launch of `implement`. */
    private fun seen(n: Int) = claude.dir.resolve("launches/implement.$n/seen").readText().lines().filter(String::isNotEmpty).toSet()

    /** The worktrees and instance branches are gone: each clone has its own worktree and no instance branch. */
    private fun cleanedUp(run: String, vararg repos: String) {
        for (repo in repos) {
            assertEquals(1, git(clone(run, repo), "worktree", "list").lines().size)
            assertEquals("", git(clone(run, repo), "branch", "--list", "*--*"))
        }
        assertTrue(workspace(run).resolve("instances").let { !it.exists() || it.listDirectoryEntries().isEmpty() })
    }

    /** The Notification list entry of [run], once there is one. */
    private fun entry(run: String): String {
        var entry = ""
        eventually {
            val row = assertNotNull(Regex("""<tr>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
                .find(browser.get("/notifications").body)).groupValues[1]
            entry = Regex("""href="(/notifications/[^"]+)"""").find(row)!!.groupValues[1]
        }
        return entry
    }

    @Test
    fun `two parallel instances committing to different files both land, with linear history on the work branch`() {
        val api = repo("api")
        setUp(api)
        claude.session("implement", 1, init, "wait go", commit("api", "one.txt"), done)
        claude.session("implement", 2, init, "wait go", commit("api", "two.txt"), done)
        val run = start("api")

        eventually { assertEquals(2, claude.launches("implement").size) }
        // Each works in its own folder, a worktree of each clone beside links to the rest of the Run workspace.
        val instances = workspace(run).resolve("instances")
        eventually { // a launch just started may not have written its cwd and stdin yet
            assertEquals(setOf("implement-1", "implement-2").map { "${instances.resolve(it)}" }.toSet(), claude.launches("implement").map { it.cwd }.toSet())
            for (launch in claude.launches("implement")) {
                assertEquals("${home.runWorkspace(run)}", launch.argv.zipWithNext().filter { it.first == "--add-dir" }.map { it.second }.first { "skills" !in it })
                assertContains(launch.stdin, "for the workspace at ${home.runWorkspace(run)}/instances/${launch.cwd.substringAfterLast('/')}.")
            }
        }
        val one = instances.resolve("implement-1")
        assertEquals("ants/$run--implement-1", git(one.resolve("repos/api"), "branch", "--show-current"))
        assertEquals(workspace(run).resolve("tasks"), one.resolve("tasks").toRealPath())
        assertFalse(one.resolve("instances").exists())

        claude.release("go")
        landed(run)
        assertEquals(setOf("one.txt", "two.txt"), files(run, "api"))
        assertEquals("", git(clone(run, "api"), "rev-list", "--merges", "HEAD"))
        assertEquals("3", git(clone(run, "api"), "rev-list", "--count", "HEAD"))
        assertEquals("", git(clone(run, "api"), "status", "--porcelain"))
        cleanedUp(run, "api")
    }

    @Test
    fun `an instance launched after a landing sees the landed commits`() {
        val api = repo("api")
        setUp(api, agentTasks = 1)
        claude.session("implement", 1, init, commit("api", "one.txt"), done)
        claude.session("implement", 2, init, """sh ls repos/api > "${'$'}launch/seen"""", done)
        val run = start("api")

        landed(run)
        assertEquals(setOf("one.txt"), seen(2))
    }

    @Test
    fun `a landing touching two repos lands in both or neither`() {
        val api = repo("api")
        val web = repo("web")
        setUp(api, web)
        claude.session("implement", 1, init, "wait go-1", commit("web", "shared.txt", "first"), done)
        claude.session("implement", 2, init, "wait go-2", commit("api", "two.txt"), commit("web", "shared.txt", "second"), done)
        val run = start("api", "web")

        eventually { assertEquals(2, claude.launches("implement").size) }
        claude.release("go-1")
        eventually { assertEquals(setOf("shared.txt"), files(run, "web")) }
        claude.release("go-2")
        val entry = browser.get(entry(run)).body
        assertContains(entry, "[failed]")
        assertContains(entry, "web")
        // api would have landed, but web conflicts: neither moves.
        assertEquals(emptySet(), files(run, "api"))
        assertEquals("1", git(clone(run, "api"), "rev-list", "--count", "HEAD"))
        assertEquals("first", clone(run, "web").resolve("shared.txt").readText())
        for (repo in listOf("api", "web")) assertEquals("", git(clone(run, repo), "status", "--porcelain"))
    }

    @Test
    fun `Continue after Stop reuses the worktree, and Start fresh branches anew`() {
        val api = repo("api")
        setUp(api, items = 1)
        val transcript = "/runs/%s/tasks/implement-1/transcript"
        claude.session("implement", 1, init, "sh cd repos/api && printf wip > wip.txt", *stopped)
        claude.session("implement", 2, init, """sh ls repos/api > "${'$'}launch/seen"""", *stopped)
        claude.session("implement", 3, init, """sh ls repos/api > "${'$'}launch/seen"""", commit("api", "three.txt"), done)
        val run = start("api")
        val page = transcript.format(run)

        fun stop(launches: Int) {
            eventually { assertEquals(launches, claude.launches("implement").size) }
            eventually { assertContains(browser.get(page).body, "<button>Send</button>") }
            browser.post("$page/stop", emptyMap())
        }

        stop(1)
        browser.post(entry(run), mapOf("action" to "continue", "instruction" to "Carry on."))
        stop(2)
        assertEquals(setOf("wip.txt"), seen(2))
        assertEquals(claude.launches("implement")[0].cwd, claude.launches("implement")[1].cwd)

        browser.post(entry(run), mapOf("action" to "fresh", "instruction" to ""))
        landed(run)
        assertEquals(emptySet(), seen(3))
        assertEquals(setOf("three.txt"), files(run, "api"))
        cleanedUp(run, "api")
    }
}
