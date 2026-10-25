package ants

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Matt Pocock's Skills ship word for word (ADR 0024): each file equals his release, a SKILL.md apart from the licence
 * comment `scripts/matt-skills` puts under its frontmatter. CI has no copy of his release, so the script pins each
 * file's SHA-256 in `matt-skills.sha256`.
 */
class MattSkillsTest {
    private val shipped = Path.of("src/main/resources/shipped/skills")
    private val pinned = Path.of("src/test/resources/matt-skills.sha256").readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }

    @Test
    fun `each of his shipped Skills equals his release, apart from the licence comment`() {
        val skills = pinned.keys.map { it.substringBefore('/') }.toSet()
        assertEquals(setOf("grilling", "to-spec", "to-tickets", "implement", "tdd", "code-review", "pr", "codebase-design"), skills)
        val files = skills.flatMap { skill -> shipped.resolve(skill).walk().filter { it.isRegularFile() && it.fileName.toString() !in setOf("LICENSE", "ants.yaml") } }
        assertEquals(pinned.keys.sorted(), files.map { "${it.relativeTo(shipped)}" }.sorted())
        for (file in files) {
            val bytes = if (file.fileName.toString() == "SKILL.md") withoutLicence(file.readText()).toByteArray() else file.readBytes()
            assertEquals(pinned["${file.relativeTo(shipped)}"], sha256(bytes), "${file.relativeTo(shipped)} differs from his release")
        }
    }

    @Test
    fun `scripts matt-skills keeps each shipped Skill's ants yaml, and gives a new one its readable id`() {
        assumeTrue(runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false), "python3 runs scripts/matt-skills")
        val root = Files.createTempDirectory("matt-skills")
        Path.of("scripts/matt-skills").copyTo(root.resolve("scripts").createDirectories().resolve("matt-skills"))
        val ours = root.resolve("src/main/resources/shipped/skills").createDirectories()
        ours.resolve("tdd").createDirectories().resolve("SKILL.md").writeText("---\nname: tdd\n---\nOld.\n")
        ours.resolve("tdd/ants.yaml").writeText("id: k-tdd\n")
        root.resolve("src/test/resources").createDirectories()
        val plugin = Files.createTempDirectory("plugin")
        plugin.resolve(".claude-plugin").createDirectories().resolve("plugin.json").writeText("""{"version": "9.9.9"}""")
        plugin.resolve("LICENSE").writeText("MIT\n")
        for (skill in listOf("productivity/grilling", "engineering/to-spec", "engineering/to-tickets", "engineering/implement", "engineering/tdd", "engineering/code-review", "engineering/pr", "engineering/codebase-design")) {
            plugin.resolve("skills/$skill").createDirectories().resolve("SKILL.md").writeText("---\nname: ${skill.substringAfter('/')}\n---\nNew.\n")
        }

        val run = ProcessBuilder("python3", root.resolve("scripts/matt-skills").toString(), plugin.toString()).redirectErrorStream(true).start()
        val output = run.inputStream.readAllBytes().decodeToString()
        assertEquals(0, run.waitFor(), output)

        assertEquals("id: k-tdd\n", ours.resolve("tdd/ants.yaml").readText())
        assertContains(ours.resolve("tdd/SKILL.md").readText(), "New.")
        assertEquals("id: k-grilling\n", ours.resolve("grilling/ants.yaml").readText())
        assertFalse("ants.yaml" in output, output)
    }

    private fun withoutLicence(text: String) = text.replaceFirst(Regex("""(?s)(\A---\n.*?\n---\n)<!-- Matt Pocock's .*? -->\n"""), "$1")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
