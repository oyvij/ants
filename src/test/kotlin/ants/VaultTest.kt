package ants

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The shipped knowledge Skills, run against fake Agents and a temporary Vault: Update the Vault and Set up Vault. */
class VaultTest : AntsTest() {
    private val browser by lazy { browser().also { it.login() } }
    private val claude = FakeClaude()

    /** A fresh, empty Vault, the default one. */
    private val vault: Path = Files.createTempDirectory("vault")

    private fun settings() = home.resolve("settings.yaml").also { Files.writeString(it, "claude:\n  command: [${claude.command}]\nvaults:\n  notes: $vault\n") }

    /** Every file in the Vault, relative to it. */
    private fun notes() = vault.walk().map { vault.relativize(it).toString() }.sorted().toList()

    private fun result(output: String) =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"${'$'}SESSION","structured_output":{"outcome":"done","summary":"Done.","output":$output}}"""

    private fun row(run: String) = Regex("""<tr[^>]*>((?:(?!</tr>).)*?/runs/$run"(?:(?!</tr>).)*)</tr>""", RegexOption.DOT_MATCHES_ALL)
        .find(browser.get("/notifications").body)?.groupValues?.get(1)

    private fun entry(run: String): String {
        var path = ""
        eventually { path = Regex("""href="(/notifications/[^"]+)"""").find(assertNotNull(row(run)))!!.groupValues[1] }
        return path
    }

    private fun page(run: String) = browser.get("/runs/$run").body

    /** Imports the shipped [file], the Process [key], and starts a Run of it. */
    private fun start(file: String, key: String): String {
        assertFalse("role=\"alert\"" in browser.import(file).body)
        browser.get("/processes/$key")
        return browser.post("/processes/$key/runs", emptyMap()).path.removePrefix("/runs/")
    }

    @Test
    fun `Update the Vault writes nothing until the Notes are approved`() {
        settings()
        claude.session(
            "update-knowledge", 1,
            "write vault/proposal.md - **add** `Billing/Decisions/Monthly invoices`\\n",
            result("""{"proposal":"vault/proposal.md","written":false}"""),
        )
        claude.session("update-knowledge", 2, "write $vault/Billing/Decisions/Monthly-invoices.md Invoices go out monthly.\\n", result("""{"written":true}"""))
        val run = start("shipped/templates/update-the-vault.bpmn", "update_the_vault") // a Template runs as a Process of its own

        val approve = entry(run)
        for (cell in listOf("<i>✓</i>[approve]", "Approve the Notes")) assertContains(row(run)!!, cell)
        assertContains(browser.get(approve).body, "<strong>add</strong> <code>Billing/Decisions/Monthly invoices</code>")
        assertEquals(emptyList(), notes())
        val first = claude.launches("update-knowledge").single()
        assertContains(first.argv.zipWithNext(), "--add-dir" to "$vault")
        assertContains(first.stdin, """\"approval\":null""")
        // Ants holds the write back too: Write and Edit on the Vault are denied until the launch after Approve.
        assertEquals(listOf("Write(/$vault/**)", "Edit(/$vault/**)"), first.flag("--disallowedTools")?.split(","))

        browser.post(approve, mapOf("action" to "approve", "comment" to ""))
        eventually { assertContains(page(run), "State: Completed") }
        assertEquals(listOf("Billing/Decisions/Monthly-invoices.md"), notes())
        val second = claude.launches("update-knowledge")[1]
        assertEquals(first.flag("--session-id"), second.flag("--resume"))
        assertContains(second.stdin, """\"approval\":{\"approved\":true""")
        assertEquals(null, second.flag("--disallowedTools"))
    }

    @Test
    fun `Set up Vault asks its Round, then leaves the rule Notes in the Vault once they are approved`() {
        settings()
        val question = """{"id":"readers","title":"Readers","ask":"Who reads the Vault?","context":"Sets how much context a Note carries.","recommendation":"The user alone.","why":"It is personal."}"""
        claude.session("init-vault", 1, result("""{"questions":[$question],"written":false}"""))
        claude.session("init-vault", 2, "write vault/proposal.md # Vault rules\\n\\nFor the user alone.\\n", result("""{"questions":[],"proposal":"vault/proposal.md","written":false}"""))
        claude.session(
            "init-vault", 3,
            "sh mkdir -p '$vault/Vault/Guidelines' && echo '[[Writing knowledge Notes]]' > '$vault/Vault/Vault.md' && echo Readers > '$vault/Vault/Guidelines/Writing knowledge Notes.md'",
            result("""{"questions":[],"written":true}"""),
        )
        val run = start("shipped/processes/set-up-vault.bpmn", "set_up_vault")

        val round = entry(run)
        assertContains(row(run)!!, "<i>?</i>[questions]")
        browser.post("$round/answers", mapOf("answer.readers" to "The user alone."))
        val approve = entry(run)
        assertContains(row(run)!!, "Approve the rule Notes")
        assertContains(browser.get(approve).body, "<h1>Vault rules</h1>")
        assertEquals(emptyList(), notes())

        browser.post(approve, mapOf("action" to "approve", "comment" to ""))
        eventually { assertContains(page(run), "State: Completed") }
        assertEquals(listOf("Vault/Guidelines/Writing knowledge Notes.md", "Vault/Vault.md"), notes())
        val (first, answered, approved) = claude.launches("init-vault")
        assertContains(first.stdin, """{\"approval\":null}""")
        assertContains(answered.stdin, """"content":"{\"answers\":[{\"id\":\"readers\",\"answer\":\"The user alone.\",\"source\":\"accepted\"}]}"""")
        assertContains(approved.stdin, """\"approval\":{\"approved\":true""")
        assertEquals(setOf(first.flag("--session-id")), setOf(answered.flag("--resume"), approved.flag("--resume")))
    }

    @Test
    fun `the shipped knowledge Skills keep Varde's MIT notice`() {
        val skills = home.resolve("shipped/skills")
        assertContains(skills.resolve("search-knowledge/LICENSE").readText(), "MIT License\n\nCopyright (c) 2026 Øyvind Johannessen")
        for (file in listOf("search-knowledge/SKILL.md", "search-knowledge/vault-format.md", "search-knowledge/leak-check.sh", "update-knowledge/SKILL.md", "init-vault/SKILL.md")) {
            assertContains(skills.resolve(file).readText(), "Copyright (c) 2026 Øyvind Johannessen. MIT License", message = file)
        }
    }
}
