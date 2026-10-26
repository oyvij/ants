package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RepositoryService
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelerTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }

    @Autowired
    private lateinit var repository: RepositoryService

    @Autowired
    private lateinit var processes: Processes

    /** Opens the modeler at [path] and fetches the diagram it loads, as its script does. */
    private fun open(path: String): Pair<Browser.Page, String> {
        val modeler = browser.get(path)
        assertEquals(200, modeler.status)
        val src = Regex("""id="canvas" data-src="([^"]+)"""").find(modeler.body)!!.groupValues[1].replace("&amp;", "&")
        return modeler to browser.get(src).body
    }

    /** The markers a page shows: Draft, Published, or Draft · changes not published. */
    private fun markers(page: String) = Regex("""class="pill draft-mark[^"]*">([^<]+)<""").findAll(page).map { it.groupValues[1] }.toList()

    /** **New Process**, as the list's modal posts it: the modeler on a Draft-only Process, and the diagram it loads. */
    private fun newProcess(name: String, from: String = ""): Pair<Browser.Page, String> {
        browser.get("/processes")
        val modeler = browser.post("/processes/new", mapOf("name" to name, "from" to from))
        assertEquals(200, modeler.status)
        return open(modeler.path)
    }

    /** The Processes list's row for the Process [name]. */
    private fun row(name: String): String {
        val list = browser.get("/processes").body
        return Regex("""<tr\b(?:(?!</tr>).)*?<b>$name</b>(?:(?!</tr>).)*</tr>""", RegexOption.DOT_MATCHES_ALL).find(list)?.value
            ?: throw AssertionError("No row for $name in $list")
    }

    /** Deletes the Draft-only Process [key], as its Delete does once confirmed. */
    private fun deleteDraft(key: String) = assertEquals("/processes", browser.post("/processes/$key/delete", mapOf("confirm" to "on")).path)

    @Test
    fun `a Process drawn from scratch is published and started`() {
        val (modeler, blank) = newProcess("Drawn from scratch")
        val script = Regex("""<script src="(/vendor/camunda-bpmn-js/[^"]+\.js)"""").find(modeler.body)!!.groupValues[1]
        assertContains(browser.get(script).body, "BpmnModeler")
        assertContains(blank, """id="drawn-from-scratch"""")
        assertContains(blank, """name="Drawn from scratch"""")

        // What the user draws: the start event flows into a User Task.
        val drawn = blank.replace(
            "</bpmn:process>",
            """<bpmn:userTask id="review" /><bpmn:sequenceFlow id="f" sourceRef="start" targetRef="review" /></bpmn:process>""",
        )
        val process = browser.post("/processes/drawn-from-scratch/publish", mapOf("bpmn" to drawn))
        assertEquals("/processes/drawn-from-scratch", process.path)
        assertContains(process.body, "<h1>Drawn from scratch</h1>")

        home.resolve("settings.yaml").writeAtomically(vault)
        val run = browser.post("/processes/drawn-from-scratch/runs", mapOf("prompt" to "Draw"))
        assertContains(run.body, "Running")
    }

    @Test
    fun `Publish writes operaton only`() {
        agentTask(null)
        val (_, blank) = newProcess("Topic in the modeler")
        // camunda-bpmn-js writes camunda:, such as an Agent Task's topic.
        val drawn = blank.replace("<bpmn:definitions ", """<bpmn:definitions xmlns:camunda="http://camunda.org/schema/1.0/bpmn" """)
            .replace("</bpmn:process>", """<bpmn:serviceTask id="draft" camunda:type="external" camunda:topic="agent"><bpmn:extensionElements><camunda:properties>""" +
                """<camunda:property name="ants.agent" value="writer" /><camunda:property name="ants.skills" value="draft" /></camunda:properties></bpmn:extensionElements></bpmn:serviceTask></bpmn:process>""")
        browser.post("/processes/topic-in-the-modeler/publish", mapOf("bpmn" to drawn))
        val diagram = browser.get("/processes/topic-in-the-modeler/diagram.bpmn").body
        assertContains(diagram, """operaton:topic="agent"""")
        assertEquals(false, "camunda" in diagram)
    }

    @Test
    fun `Publish refuses a diagram the engine can't parse, with the reason, and keeps it as the Draft`() {
        val (_, blank) = newProcess("Broken")
        val broken = blank.replace("</bpmn:process>", """<bpmn:sequenceFlow id="f" sourceRef="start" targetRef="nowhere" /></bpmn:process>""")
        val refused = browser.post("/processes/broken/publish", mapOf("bpmn" to broken))
        assertEquals(400, refused.status)
        assertContains(refused.body, "nowhere")
        val (modeler, kept) = open("/processes/broken/edit")
        assertContains(kept, "targetRef=\"nowhere\"")
        assertEquals(listOf("Draft"), markers(modeler.body))
        deleteDraft("broken")
    }

    @Test
    fun `New Process as a copy opens the copy in the modeler, under a key of its own`() {
        browser.import("start-end.bpmn")
        assertContains(browser.get("/processes").body, """<option value="start-end">Start to end</option>""")
        val (modeler, copy) = newProcess("", from = "start-end")
        assertContains(modeler.body, "<h1>Start to end copy</h1>")
        assertEquals(listOf("Draft"), markers(modeler.body))
        assertContains(copy, """id="start-to-end-copy"""")
        assertContains(copy, """name="Start to end copy"""")
        assertContains(copy, """<bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end"/>""")

        assertEquals("/processes/start-to-end-copy", browser.post("/processes/start-to-end-copy/publish", mapOf("bpmn" to copy)).path)
        val (_, again) = newProcess("", from = "start-end")
        assertContains(again, """id="start-to-end-copy-2"""")
        deleteDraft("start-to-end-copy-2")
    }

    @Test
    fun `a deployed Process opens in the modeler`() {
        browser.import("start-end.bpmn")
        val (_, diagram) = open("/processes/start-end/edit")
        assertContains(diagram, """id="start-end"""")
        assertContains(browser.get("/processes/start-end").body, """href="/processes/start-end/edit"""")
    }

    @Test
    fun `the modeler gets the Built-in Tasks, field types and Items are Tickets from the server`() {
        val (modeler, _) = newProcess("Facts")
        deleteDraft("facts")
        val facts = Regex("""data-facts="([^"]+)"""").find(modeler.body)!!.groupValues[1].replace("&#34;", "\"").replace("&#39;", "'")
        val read = JsonMapper.shared().readValue(facts, Map::class.java)
        assertEquals(builtIns, read["builtIns"])
        assertEquals(mapOf("text" to "Text", "markdown" to "Markdown", "repos" to "Repos"), read["fieldTypes"])
        assertEquals("ants.items-are-tickets", (read["itemsAreTickets"] as Map<*, *>)["property"])
        assertContains((read["itemsAreTickets"] as Map<*, *>)["help"].toString(), "Each item names a Ticket (tickets/issues/01-hello.md, or 01).")
        assertEquals(mapOf("name" to "Parallel gateway", "explain" to "Run branches at the same time, then wait for all of them to join."), (read["kinds"] as Map<*, *>)["parallel"])
        for (fact in listOf("Clone repos", "ready-for-human", "ants.items-are-tickets", "Answer a Round", "ants.send-back", "Parallel gateway", "Where a Run begins")) assertEquals(false, fact in browser.get("/modeler.js").body, fact)
    }

    @Test
    fun `the modeler gets each User Task kind's fields and Result from the server`() {
        val (modeler, _) = newProcess("Facts")
        deleteDraft("facts")
        val facts = Regex("""data-facts="([^"]+)"""").find(modeler.body)!!.groupValues[1].replace("&#34;", "\"").replace("&#39;", "'")
        val kinds = (JsonMapper.shared().readValue(facts, Map::class.java)["userTasks"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("Answer a Round", "Approve", "Review changes"), kinds.map { it["name"] })
        assertEquals(listOf("answer-round", "", "review-changes"), kinds.map { it["value"] })
        assertEquals(listOf("approved", "comment"), kinds[1]["result"])
        val approve = (kinds[1]["fields"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("Title", "Show", "Send back resumes"), approve.map { it["label"] })
        assertEquals(listOf("title", "show", null), approve.map { it["input"] })
        assertEquals("ants.send-back", approve[2]["property"])
        assertEquals(listOf("ants.asker"), (kinds[0]["fields"] as List<*>).map { (it as Map<*, *>)["property"] })
        assertEquals(listOf("ants.walkthrough", "ants.send-back"), (kinds[2]["fields"] as List<*>).map { (it as Map<*, *>)["property"] })
    }

    @Test
    fun `the modeler's header leads back to the Process page`() {
        browser.import("start-end.bpmn")
        val modeler = browser.get("/processes/start-end/edit").body
        assertContains(modeler, """<a href="/processes/start-end">Start to end</a>""")
    }

    @Test
    fun `New Process makes a Draft-only Process, listed with the marker, that can't be started, scheduled or Ready`() {
        val (modeler, blank) = newProcess("Only drafted")
        assertEquals("/processes/only-drafted/edit", modeler.path)
        assertEquals(listOf("Draft"), markers(modeler.body))
        assertContains(modeler.body, ">Publish</button>")
        assertContains(blank, """id="only-drafted"""")
        assertContains(blank, """name="Only drafted"""")

        val row = row("Only drafted")
        assertContains(row, """<a href="/processes/only-drafted/edit"><b>Only drafted</b></a><span class="pill draft-mark p-draft">Draft</span>""")
        assertFalse("Ready" in row || "Can't start" in row, row)
        assertEquals("/processes/only-drafted/edit", browser.get("/processes/only-drafted").path)
        assertEquals(404, browser.post("/processes/only-drafted/runs", emptyMap()).status)
        assertEquals(404, browser.post("/processes/only-drafted/schedule", mapOf("cron" to "0 0 0 * * *")).status)

        val (_, again) = newProcess("Only drafted")
        assertContains(again, """id="only-drafted-2"""") // a Draft's key is taken
        deleteDraft("only-drafted-2")
        deleteDraft("only-drafted")
        assertEquals(404, browser.get("/processes/only-drafted/edit").status)
    }

    /** A Process [key] named [name] whose start event flows into [task], a User Task. */
    private fun waiting(key: String, name: String, task: String) = """
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="d" targetNamespace="t">
          <bpmn:process id="$key" name="$name" isExecutable="true" camunda:historyTimeToLive="180">
            <bpmn:startEvent id="start" /><bpmn:sequenceFlow id="f" sourceRef="start" targetRef="$task" /><bpmn:userTask id="$task" />
          </bpmn:process>
        </bpmn:definitions>
    """.trimIndent()

    @Test
    fun `an edit in the modeler is in the Draft, which reopens after a new login and a restart, and Publish deploys it as the next version`() {
        browser.get("/processes")
        browser.post("/processes/deploy", mapOf("bpmn" to waiting("drafted", "Drafted", "first")))
        val (modeler, _) = open("/processes/drafted/edit")
        assertEquals("0", draftVersion(modeler.body))
        assertContains(modeler.body, """data-autosave="/processes/drafted/draft"""")
        assertEquals(listOf("Published"), markers(modeler.body))

        val saved = answer(browser.autosave("/processes/drafted", listOf("bpmn" to waiting("drafted", "Drafted again", "second")), draftVersion(modeler.body)))
        assertEquals("Draft · changes not published", saved["label"])
        assertEquals("/processes/drafted", saved["path"])
        assertContains(browser.get("/processes/drafted/diagram.bpmn").body, """id="first"""") // the deployed version is as it was
        assertContains(row("Drafted"), """<span class="pill draft-mark p-changed">Draft · changes not published</span>""")
        assertContains(browser.get("/processes/drafted").body, "Draft · changes not published")

        val again = browser().also { it.login() }
        val reopened = again.get("/processes/drafted/edit").body
        assertEquals(saved["version"].toString(), draftVersion(reopened))
        assertEquals(listOf("Draft · changes not published"), markers(reopened))
        assertContains(reopened, "<h1>Drafted again</h1>")
        assertContains(again.get("/processes/drafted/edit.bpmn").body, """id="second"""")
        assertContains(Drafts(home).get(DraftKind.PROCESS, "drafted")!!.content, """id="second"""") // as a restarted Ants reads it

        val stale = browser.autosave("/processes/drafted", listOf("bpmn" to waiting("drafted", "Drafted", "stale")), draftVersion(modeler.body))
        assertEquals(409, stale.status)
        assertEquals("This Process's Draft was changed in another tab or window.", answer(stale)["conflict"])

        val published = browser.post("/processes/drafted/publish", mapOf("bpmn" to waiting("drafted", "Drafted again", "second")))
        assertEquals("/processes/drafted", published.path)
        assertContains(browser.get("/processes/drafted/diagram.bpmn").body, """id="second"""")
        assertEquals(2, repository.createProcessDefinitionQuery().processDefinitionKey("drafted").latestVersion().singleResult().version)
        val after = browser.get("/processes/drafted/edit").body
        assertEquals("0", draftVersion(after))
        assertFalse("Draft saved" in after)
        assertFalse("draft-mark" in row("Drafted again"))
    }

    @Test
    fun `Publish of a diagram whose Process id was changed removes the Draft the modeler was opened on`() {
        newProcess("Rekeyed draft")
        val published = browser.post("/processes/rekeyed-draft/publish", mapOf("bpmn" to waiting("rekeyed-elsewhere", "Rekeyed draft", "only")))
        assertEquals("/processes/rekeyed-elsewhere", published.path)
        assertEquals(404, browser.get("/processes/rekeyed-draft/edit").status)
        assertFalse("rekeyed-draft" in browser.get("/processes").body)

        browser.post("/processes/deploy", mapOf("bpmn" to waiting("rekeyed", "Rekeyed", "first")))
        browser.autosave("/processes/rekeyed", listOf("bpmn" to waiting("rekeyed", "Rekeyed", "second")), "0")
        assertEquals("/processes/rekeyed-too", browser.post("/processes/rekeyed/publish", mapOf("bpmn" to waiting("rekeyed-too", "Rekeyed too", "third"))).path)
        assertEquals("0", draftVersion(browser.get("/processes/rekeyed/edit").body))
        assertFalse("draft-mark" in row("Rekeyed"))
    }

    @Test
    fun `Discard Draft confirms and reopens the deployed version, and Delete of a Draft-only Process confirms`() {
        browser.get("/processes")
        browser.post("/processes/deploy", mapOf("bpmn" to waiting("discarded", "Discarded", "kept")))
        browser.autosave("/processes/discarded", listOf("bpmn" to waiting("discarded", "Discarded", "unwanted")), "0")
        val modeler = browser.get("/processes/discarded/edit").body
        assertContains(modeler, """action="/processes/discarded/discard" data-leaves-draft><input type="hidden" name="_csrf"""")
        assertContains(browser.post("/processes/discarded/discard", emptyMap()).body, "Discard the Draft of Discarded?")
        assertContains(browser.get("/processes/discarded/edit.bpmn").body, """id="unwanted"""")
        assertEquals("/processes/discarded/edit", browser.post("/processes/discarded/discard", mapOf("confirm" to "on")).path)
        assertContains(browser.get("/processes/discarded/edit.bpmn").body, """id="kept"""")
        assertFalse("Discard Draft" in browser.get("/processes/discarded/edit").body)

        val (draft, _) = newProcess("Abandoned")
        assertContains(draft.body, """action="/processes/abandoned/delete" data-leaves-draft>""")
        assertContains(browser.post("/processes/abandoned/delete", emptyMap()).body, "Delete the Draft Abandoned?")
        deleteDraft("abandoned")
        assertFalse("Abandoned" in browser.get("/processes").body)
    }

    @Test
    fun `editing a shipped Process makes a Draft of the user's own, and the shipped one is deployed until Publish`() {
        val shipped = home.resolve("shipped/processes/shipped-drafted.bpmn").also { it.parent.toFile().mkdirs() }
        try {
            shipped.writeText(waiting("shipped-drafted", "Shipped drafted", "shipped"))
            processes.deployShipped()
            assertContains(row("Shipped drafted"), ">shipped<")
            assertContains(browser.get("/processes/shipped-drafted/edit").body, "Shipped: publishing makes it your own")
            browser.autosave("/processes/shipped-drafted", listOf("bpmn" to waiting("shipped-drafted", "Shipped drafted", "mine")), "0")
            assertContains(row("Shipped drafted"), ">shipped<")
            assertContains(row("Shipped drafted"), "Draft · changes not published")
            assertContains(browser.get("/processes/shipped-drafted/diagram.bpmn").body, """id="shipped"""")

            browser.post("/processes/shipped-drafted/publish", mapOf("bpmn" to waiting("shipped-drafted", "Shipped drafted", "mine")))
            assertContains(row("Shipped drafted"), ">you<")
            assertContains(browser.get("/processes/shipped-drafted/diagram.bpmn").body, """id="mine"""")
        } finally {
            shipped.toFile().delete()
        }
    }
}
