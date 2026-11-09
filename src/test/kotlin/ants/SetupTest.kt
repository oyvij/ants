package ants

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Setup (ADR 0034): Export writes what the user made into one zip laid out like ANTS_HOME, and Import reads one into
 * a preview of every item as New, Replaces or Clashes, then lands each confirmed item on its own, keeping its id.
 */
class SetupTest : AntsTest() {
    @Autowired
    lateinit var setup: Setup

    @Autowired
    lateinit var scripts: Scripts

    @Autowired
    lateinit var catalogue: Catalogue

    @Autowired
    lateinit var settings: Settings

    /** No Settings of the user's, so a test's Setup holds only what it makes. */
    @BeforeEach
    fun noSettings() = home.resolve("settings.yaml").writeText("")

    private fun agent(name: String, id: String, body: String = "Write plainly.") =
        home.resolve("agents/$name.md").writeText("---\nid: $id\nname: $name\ndescription: Writes.\n---\n$body\n")

    private fun skill(name: String, id: String, references: Map<String, String> = emptyMap()) {
        val folder = home.resolve("skills/$name").createDirectories()
        folder.resolve("ants.yaml").writeText("id: $id\n")
        for ((path, text) in references) folder.resolve(path).also { it.parent.createDirectories() }.writeText(text)
        folder.resolve("SKILL.md").writeText("---\nname: $name\ndescription: Styles.\n---\nBe brief.\n")
    }

    private fun script(name: String, id: String, own: Map<String, String>? = null) =
        scripts.save(Script(id, name, "Posts."), "console.log('$name')\n", own)

    private fun entries(zip: ByteArray): Map<String, String> = ZipInputStream(ByteArrayInputStream(zip)).use { input ->
        generateSequence { input.nextEntry }.filterNot { it.isDirectory }.associate { it.name to input.readBytes().decodeToString() }
    }

    private fun zip(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> for ((path, text) in files) out.putNextEntry(ZipEntry(path)).also { out.write(text.toByteArray()) } }
    }.toByteArray()

    /** Every item of [preview] ticked, as the preview starts. */
    private fun all(preview: Setup.Preview) = preview.items.associate { it.key to Setup.Choice() }

    @Test
    fun `Export then Import into another Ants brings Agents, Skills with References and Scripts with their ids`() {
        agent("writer", "a-writer1")
        skill("style", "k-style1", mapOf("references/tone.md" to "Warm.\n"))
        script("post", "s-post01")
        val zip = setup.export()
        assertEquals(
            setOf("setup.yaml", "agents/writer.md", "skills/style/SKILL.md", "skills/style/ants.yaml", "skills/style/references/tone.md", "scripts/s-post01/script.yaml", "scripts/s-post01/script.mjs"),
            entries(zip).keys,
        )

        val other = tempHome()
        startAnother(other).use { ants ->
            val there = ants.getBean(Setup::class.java)
            val preview = there.preview(zip)
            assertEquals(listOf(Setup.Status.NEW), preview.items.map { it.status }.distinct())
            val report = there.import(zip, all(preview))
            assertEquals(listOf(Setup.Outcome.ADDED), report.map { it.outcome }.distinct(), report.toString())

            val catalogue = ants.getBean(Catalogue::class.java)
            assertEquals("a-writer1", catalogue.agent("writer").id)
            assertEquals("k-style1", catalogue.skill("style").id)
            assertEquals("Warm.\n", catalogue.skill("style").folder.resolve("references/tone.md").readText())
            assertEquals("post", ants.getBean(Scripts::class.java).withId("s-post01")?.name)
        }
    }

    @Test
    fun `the preview shows New, Replaces and Clashes, a Clash lands only renamed and keeps its id, and an unticked item stays out`() {
        agent("writer", "a-writer1", "Write plainly.")
        agent("reviewer", "a-review1")
        skill("style", "k-style1")
        script("post", "s-post01")
        val zip = setup.export()

        agent("writer", "a-writer1", "Write in verse.") // the same id: the Setup's replaces it
        home.resolve("agents/reviewer.md").deleteIfExists()
        agent("reviewer", "a-review2") // the same name, another id: a Clash
        home.resolve("skills/style").toFile().deleteRecursively() // gone: New
        scripts.delete("s-post01")
        script("post", "s-post02") // a Clash, not renamed

        val preview = setup.preview(zip)
        val status = preview.items.associate { it.key to it.status }
        assertEquals(
            mapOf("agents/writer.md" to Setup.Status.REPLACES, "agents/reviewer.md" to Setup.Status.CLASHES, "skills/style" to Setup.Status.NEW, "scripts/s-post01" to Setup.Status.CLASHES),
            status,
        )

        val report = setup.import(zip, mapOf("agents/writer.md" to Setup.Choice(), "agents/reviewer.md" to Setup.Choice(rename = "reviewer-2"), "scripts/s-post01" to Setup.Choice()))
        assertEquals(
            listOf(
                Setup.Landed("Agent", "reviewer-2", Setup.Outcome.ADDED),
                Setup.Landed("Agent", "writer", Setup.Outcome.REPLACED),
                Setup.Landed("Skill", "style", Setup.Outcome.SKIPPED, "Unticked."),
                Setup.Landed("Script", "post", Setup.Outcome.SKIPPED, "Another Script is named post: rename it to import it."),
            ),
            report,
        )
        assertContains(catalogue.agent("writer").instructions, "Write plainly.")
        assertEquals("a-review1", catalogue.agent("reviewer-2").id)
        assertEquals("a-review2", catalogue.agent("reviewer").id)
        assertFalse(home.resolve("skills/style").exists())
        assertEquals("s-post02", scripts.named("post")?.id)
        assertEquals(null, scripts.withId("s-post01"))
    }

    @Test
    fun `each item lands or fails on its own, and the report names every refusal`() {
        val zip = zip(mapOf(
            "setup.yaml" to "ants: 0.1.0\n",
            "agents/broken.md" to "---\nid: a-broken1\nname: [broken\n---\nHi.\n",
            "agents/nameless.md" to "---\nname: nameless\n---\nHi.\n",
            "agents/codexer.md" to "---\nid: a-codex01\ncli: gemini\n---\nHi.\n",
            "skills/style/SKILL.md" to "---\nname: style\n---\nNo description.\n",
            "scripts/s-post01/script.yaml" to "id: s-post01\nname: post\ndescription: Posts.\n",
            "scripts/s-post01/script.mjs" to "console.log('post')\n",
        ))
        val preview = setup.preview(zip)
        val report = setup.import(zip, all(preview)).associate { it.name to it }
        assertEquals(Setup.Outcome.ADDED, report.getValue("post").outcome)
        for (refused in listOf("broken", "nameless", "codexer", "style")) assertEquals(Setup.Outcome.REFUSED, report.getValue(refused).outcome, refused)
        assertContains(report.getValue("nameless").reason!!, "no id")
        assertContains(report.getValue("codexer").reason!!, "gemini")
        assertContains(report.getValue("style").reason!!, "description")
        assertEquals("console.log('post')\n", scripts.code(scripts.withId("s-post01")!!))
        assertFalse(home.resolve("agents/codexer.md").exists())
    }

    @Test
    fun `a Setup holds no Draft or Secret value, and one from a newer Ants or with a path outside itself is refused whole`() {
        script("post", "s-post01", own = mapOf("TOKEN" to "s3cret-token"))
        home.resolve("drafts/agents").createDirectories().resolve("a-draft01.yaml").writeText("name: drafted\n")
        val zip = setup.export()
        assertTrue(entries(zip).keys.all { it == "setup.yaml" || it.startsWith("scripts/") }, entries(zip).keys.toString())
        assertFalse("s3cret-token" in zip.decodeToString())
        assertFalse(entries(zip).values.any { "s3cret-token" in it })

        val newer = assertFailsWith<Setup.Refused> { setup.preview(zip(mapOf("setup.yaml" to "ants: 999.0.0\n"))) }
        assertContains(newer.message!!, "update Ants first")
        assertFailsWith<Setup.Refused> { setup.preview(zip(mapOf("setup.yaml" to "ants: 0.1.0\n", "skills/../../evil/SKILL.md" to "x"))) }
        assertFailsWith<Setup.Refused> { setup.preview("not a zip".toByteArray()) }
    }

    @Test
    fun `a round trip brings the portable Settings and Profiles, and no machine-specific Setting is in the zip`() {
        home.resolve("settings.yaml").writeText(
            """
            claude:
              path: /opt/claude
              env: [PATH, HOME]
            password: hunter2-pass
            vaults:
              notes: /home/me/notes
            source-maps:
              code: /home/me/source-map.md
            default-vault: notes
            agent-folders: [/home/me/agents]
            skill-folders: [/home/me/skills]
            profiles:
              work: {vault: notes, source-map: code, verbosity: brief}
            secrets:
              npm: [NPM_TOKEN]
              op-login: [OP_SERVICE_ACCOUNT_TOKEN]
            lookups:
              npm: op://Dev/npm/token
            secret-providers:
              op: {command: [op, read, "{ref}"], secrets: [op-login]}
            protected-commands:
              yarn publish: [npm]
            agent-tasks: 5
            subagent-depth: 2
            retries: 1
            so-far: 5m
            theme: dark
            """.trimIndent() + "\n",
        )
        instruct("work", "Use British English.\n")
        val zip = setup.export()
        val files = entries(zip)
        assertEquals(setOf("setup.yaml", "profiles/work/AGENTS.md"), files.keys)
        for (machine in listOf("/opt/claude", "hunter2-pass", "/home/me", "default-vault")) assertFalse(machine in files.getValue("setup.yaml"), machine)

        val other = tempHome().createDirectories()
        other.resolve("settings.yaml").writeText("vaults:\n  notes: $vaultPath\n")
        startAnother(other).use { ants ->
            val there = ants.getBean(Setup::class.java)
            val report = there.import(zip, all(there.preview(zip)))
            assertEquals(listOf(Setup.Outcome.ADDED), report.map { it.outcome }.distinct(), report.toString())
            assertContains(report.single { it.noun == "Profile" }.reason.orEmpty(), "Source map code")

            val settings = ants.getBean(Settings::class.java)
            assertEquals(mapOf("npm" to listOf("NPM_TOKEN"), "op-login" to listOf("OP_SERVICE_ACCOUNT_TOKEN")), settings.secrets())
            assertEquals(mapOf("npm" to "op://Dev/npm/token"), settings.lookups())
            assertEquals(mapOf("op" to SecretProvider(listOf("op", "read", "{ref}"), listOf("op-login"))), settings.secretProviders())
            assertEquals(mapOf("yarn publish" to listOf("npm")), settings.protectedCommands())
            assertEquals(listOf(5, 2, 1), listOf(settings.agentTasks(), settings.subagentDepth(), settings.retries()))
            assertEquals(java.time.Duration.ofMinutes(5), settings.soFar())
            assertEquals("dark", settings.colorScheme())
            assertEquals(Profile("notes", "code", Verbosity.BRIEF), settings.profiles()["work"])
            assertEquals("Use British English.\n", settings.instructionsText("work"))
            assertEquals(null, settings.read()["password"])
            assertEquals("$vaultPath", settings.vaults()["notes"])
            assertEquals(setOf(Settings.ANTHILL), settings.sourceMaps().keys)
        }
    }

    @Test
    fun `Import merges Settings by name, so the Ants' own Protected Commands, Secrets and Profiles stay, and the Setup's are added or replace theirs`() {
        home.resolve("settings.yaml").writeText(
            """
            secrets:
              npm: [NPM_TOKEN]
              maven: [MAVEN_PASSWORD]
            protected-commands:
              yarn publish: [npm]
              mvn deploy: [maven]
            profiles:
              work: {vault: notes, source-map: code}
            theme: dark
            """.trimIndent() + "\n",
        )
        val zip = setup.export()
        home.resolve("settings.yaml").writeText(
            """
            claude:
              path: /opt/claude
            vaults:
              notes: $vaultPath
            secrets:
              npm: [OLD_TOKEN]
              github: [GH_TOKEN]
            protected-commands:
              yarn publish: [github]
              gh release: [github]
            profiles:
              private: {vault: notes}
            theme: light
            agent-tasks: 7
            """.trimIndent() + "\n",
        )

        val preview = setup.preview(zip).items.associate { it.key to it.status }
        assertEquals(Setup.Status.REPLACES, preview["protected-commands/yarn publish"])
        assertEquals(Setup.Status.NEW, preview["protected-commands/mvn deploy"])
        assertEquals(Setup.Status.REPLACES, preview["secrets/npm"])
        val report = setup.import(zip, preview.keys.associateWith { Setup.Choice() })
        assertEquals(Setup.Landed("Protected Command", "yarn publish", Setup.Outcome.REPLACED), report.single { it.name == "yarn publish" })

        assertEquals(mapOf("yarn publish" to listOf("npm"), "gh release" to listOf("github"), "mvn deploy" to listOf("maven")), settings.protectedCommands())
        assertEquals(mapOf("npm" to listOf("NPM_TOKEN"), "github" to listOf("GH_TOKEN"), "maven" to listOf("MAVEN_PASSWORD")), settings.secrets())
        assertEquals(setOf("private", "work"), settings.profiles().keys)
        assertEquals("dark", settings.colorScheme())
        assertEquals(7, settings.agentTasks())
        assertEquals("/opt/claude", settings.path("claude"))
    }

    @Test
    fun `a Protected Command whose Secret is neither here nor imported is refused, and the rest lands`() {
        home.resolve("settings.yaml").writeText("secrets:\n  npm: [NPM_TOKEN]\nprotected-commands:\n  yarn publish: [npm]\n")
        val zip = setup.export()
        home.resolve("settings.yaml").writeText("")
        val report = setup.import(zip, mapOf("protected-commands/yarn publish" to Setup.Choice()))
        assertEquals(Setup.Outcome.SKIPPED, report.single { it.noun == "Secret" }.outcome)
        val refused = report.single { it.noun == "Protected Command" }
        assertEquals(Setup.Outcome.REFUSED, refused.outcome)
        assertContains(refused.reason.orEmpty(), "npm")
        assertEquals(emptyMap(), settings.protectedCommands())
    }

    @Test
    fun `Your Skills and Your tools of a shipped Agent round-trip`() {
        val lead = catalogue.agent("lead")
        skill("style", "k-style1")
        catalogue.yours.publishSkills(lead, listOf("style"))
        catalogue.yours.publishTools(lead, listOf("WebFetch"))
        val zip = setup.export()
        assertContains(entries(zip).getValue("setup.yaml"), "a-lead")

        startAnother(tempHome()).use { ants ->
            val there = ants.getBean(Setup::class.java)
            val preview = there.preview(zip)
            assertEquals(Setup.Status.NEW, preview.items.single { it.noun == "Your Skills and tools" }.status)
            val report = there.import(zip, all(preview))
            assertEquals(listOf(Setup.Outcome.ADDED), report.map { it.outcome }.distinct(), report.toString())
            val catalogue = ants.getBean(Catalogue::class.java)
            val lead = catalogue.agent("lead")
            assertEquals(listOf("style"), catalogue.yours.skills(lead))
            assertEquals(listOf("WebFetch"), catalogue.yours.tools(lead))
        }
    }
}
