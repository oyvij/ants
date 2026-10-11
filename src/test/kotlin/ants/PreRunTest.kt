package ants

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PreRunTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()
    private val vault = Files.createTempDirectory("vault")

    private fun settings(extra: String = "", vaults: String = "vaults:\n  notes: $vault\n") =
        home.resolve("settings.yaml").writeText("claude:\n  command: [${claude.command}]\n$vaults$extra")

    /** The Agent `writer` listing the Skill `draft`, whose output has a `text`; [frontmatter] is added to the Agent's. */
    private fun catalogue(frontmatter: String = "") {
        home.resolve("agents").createDirectories().resolve("writer.md").writeText("---\nskills: draft\n$frontmatter---\nWrite plainly.\n")
        val skill = home.resolve("skills/draft").createDirectories()
        skill.resolve("SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\n---\nDraft the text.\n")
        skill.resolve("result.schema.json").writeText("""{"type":"object","properties":{"text":{"type":"string"}}}""")
    }

    /** The Process page's pre-Run check: Ready, or Can't start and every reason. */
    private fun status(key: String = "agent-task") =
        browser.get("/processes/$key").body.substringAfter("""<section id="pre-run">""").substringBefore("</section>").replace("&#39;", "'").replace("&#34;", "\"")

    private fun startable(key: String = "agent-task") = """<button disabled>Start</button>""" !in browser.get("/processes/$key").body

    private fun ready() {
        settings()
        catalogue()
        assertFalse("role=\"alert\"" in browser.import("agent-task.bpmn").body)
        assertContains(status(), "Ready")
    }

    @Test
    fun `a Process with everything in place is Ready, and Start is enabled`() {
        ready()
        assertFalse("Can't start" in status())
        assertContains(browser.get("/processes/agent-task").body, "<button>Start</button>")
    }

    @Test
    fun `a missing Vault shows only Configure a Vault, linking to settings, and Start is disabled and refused`() {
        ready()
        home.resolve("agents/writer.md").writeText("---\nskills: draft\n---\n")
        settings(vaults = "")
        claude.command.toFile().delete() // a second reason, which the missing Vault hides
        val status = status()
        assertContains(status, "Can't start")
        assertContains(status, """<li><a href="/settings">Configure a Vault</a></li>""")
        assertEquals(1, Regex("<li>").findAll(status).count(), status)
        assertFalse(startable())

        val refused = browser.post("/processes/agent-task/runs", mapOf("prompt" to "Say hello"))
        assertEquals(400, refused.status)
        assertContains(refused.body, "Configure a Vault")
        assertFalse("/runs/" in refused.path)
    }

    /** After [change], the page shows Can't start with [reason], and Start is disabled. */
    private fun cantStart(reason: String, key: String = "agent-task", change: () -> Unit) {
        change()
        val status = status(key)
        assertContains(status, "Can't start")
        assertContains(status, reason)
        assertFalse(startable(key))
    }

    @Test
    fun `an Agent or Skill name that no longer resolves`() {
        ready()
        cantStart("Draft (draft): No Agent named writer.") { home.resolve("agents/writer.md").deleteIfExists() }
    }

    @Test
    fun `a called Process is checked too, since it runs inside the caller's Run`() {
        ready()
        val caller = """<?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                              id="definitions" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="caller" name="Caller" isExecutable="true" operaton:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:extensionElements><operaton:formData><operaton:formField id="prompt" label="Prompt" type="string" /></operaton:formData></bpmn:extensionElements>
                </bpmn:startEvent>
                <bpmn:callActivity id="call" calledElement="agent-task">
                  <bpmn:extensionElements><operaton:in sourceExpression="${'$'}{prompt}" target="prompt" /></bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:endEvent id="end" />
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="call" />
                <bpmn:sequenceFlow id="f2" sourceRef="call" targetRef="end" />
              </bpmn:process>
            </bpmn:definitions>"""
        browser.get("/processes")
        assertEquals("/processes/caller", browser.post("/processes/deploy", mapOf("bpmn" to caller)).path)
        assertContains(status("caller"), "Ready")
        cantStart("Agent Task: Draft (draft): No Agent named writer.", key = "caller") { home.resolve("agents/writer.md").deleteIfExists() }
        cantStart("Ants' Claude login is missing or expired.", key = "caller") {
            catalogue()
            home.resolve("claude/auth-status.json").deleteIfExists()
        }
    }

    @Test
    fun `a Skill's result schema that became invalid`() {
        ready()
        cantStart("The Skill draft's result.schema.json is invalid") { home.resolve("skills/draft/result.schema.json").writeText("""{"type":"thing"}""") }
    }

    @Test
    fun `a reference to a field the Result no longer has`() {
        settings()
        catalogue()
        val bpmn = javaClass.getResource("/agent-task.bpmn")!!.readText().replace(">dry<", ">\${draft.output.text}<")
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", "agent-task.bpmn", bpmn.toByteArray()).body)
        cantStart("reads draft.output.text, which draft's output schema doesn't have.") {
            home.resolve("skills/draft/result.schema.json").writeText("""{"type":"object","properties":{"body":{"type":"string"}}}""")
        }
    }

    @Test
    fun `a used Skill that no longer resolves`() {
        ready()
        cantStart("No Skill named gone.") {
            home.resolve("skills/draft/SKILL.md").writeText("---\nname: draft\ndescription: Drafts a text.\nmetadata:\n  ants-uses: gone\n---\nDraft.\n")
        }
    }

    @Test
    fun `an Agent's CLI command that isn't there`() {
        ready()
        cantStart("claude: ${claude.command} isn't an executable.") { claude.command.toFile().delete() }
    }

    @Test
    fun `a Vault path that isn't there`() {
        ready()
        cantStart("The Vault notes isn't there: /nowhere/vault.") { settings(vaults = "vaults:\n  notes: /nowhere/vault\n") }
    }

    @Test
    fun `a Source map path that isn't there`() {
        ready()
        cantStart("The Source map work isn't there: /nowhere/map.md.") { settings("source-maps:\n  work: /nowhere/map.md\n") }
    }

    /** The Process `repos` with a Repos field whose stored value is [stored], and a Source map with the repo `api` on [remote]. */
    private fun repos(stored: String, remote: String = bare().toString()) {
        val map = Files.createTempDirectory("source-map").resolve("work.md").also { it.writeText("- api: /code/api ($remote)\n") }
        settings("source-maps:\n  work: $map\n")
        val bpmn = javaClass.getResource("/start-form.bpmn")!!.readText().replace("\"start-form\"", "\"repos\"")
            .replace("""<camunda:property id="ants.type" value="repos" />""", """<camunda:property id="ants.type" value="repos" /><camunda:property id="ants.stored" value="$stored" />""")
        browser.get("/processes")
        assertFalse("role=\"alert\"" in browser.upload("/processes", "file", "repos.bpmn", bpmn.toByteArray()).body)
    }

    @Test
    fun `a Source map name the Source map doesn't have, stored or posted`() {
        repos("api")
        assertContains(status("repos"), "Ready")
        cantStart("The Source map work has no repo gone.", key = "repos") { repos("api gone") }

        repos("api")
        browser.get("/processes/repos")
        val refused = browser.post("/processes/repos/runs", listOf("prompt" to "Fix it", "repos" to "gone"))
        assertEquals(400, refused.status)
        assertContains(refused.body.replace("&#39;", "'"), "The Source map work has no repo gone.")
    }

    @Test
    fun `a repo whose remote git ls-remote can't reach`() {
        cantStart("git ls-remote can't reach api's remote /nowhere/api.git.", key = "repos") { repos("api", remote = "/nowhere/api.git") }
    }

    @Test
    fun `Ants' Claude login missing, or an API key in use`() {
        ready()
        val status = home.resolve("claude/auth-status.json")
        cantStart("Ants' Claude login is missing or expired. Log in again (Settings).") { status.deleteIfExists() }
        cantStart("An API key is in use (apiKeySource: apiKeyHelper).") {
            status.writeText("""{"loggedIn":true,"authMethod":"api_key","apiKeySource":"apiKeyHelper"}""")
        }
    }

    @Test
    fun `gh not logged in, whatever tools the Agents have`() {
        ready() // the tests' own gh is logged in
        val gh = Files.createTempDirectory("fake-gh").resolve("gh")
        gh.writeText("#!/bin/sh\n[ \"$1 $2\" = \"auth status\" ] && exit 1\nexit 0\n")
        gh.toFile().setExecutable(true)
        cantStart("gh isn't logged in: run gh auth login.") { settings("gh:\n  path: $gh\n") }
        cantStart("gh isn't logged in: run gh auth login.") { catalogue("tools: Read\n") }
        cantStart("gh: /nowhere/gh isn't an executable.") { settings("gh:\n  path: /nowhere/gh\n") }
    }

    @Test
    fun `a custom model ID claude refuses, tried once`() {
        ready()
        claude.session("so-far", "stderr There's an issue with the selected model (claude-made-up).", "exit 1")
        cantStart("The Agent writer's model claude-made-up doesn't work: There's an issue with the selected model (claude-made-up).") {
            catalogue("model: claude-made-up\n")
        }
        status()
        assertEquals(1, claude.launches("so-far").size)
        assertContains(claude.launches("so-far").single().argv, "claude-made-up")
    }
}

/** A bare repo, as a remote. */
fun bare(): Path = Files.createTempDirectory("remote").also { ProcessBuilder("git", "init", "--bare", "-q", "$it").start().waitFor() }
