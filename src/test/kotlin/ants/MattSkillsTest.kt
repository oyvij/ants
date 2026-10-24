package ants

import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.assertEquals

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
        val files = skills.flatMap { skill -> shipped.resolve(skill).walk().filter { it.isRegularFile() && it.fileName.toString() != "LICENSE" } }
        assertEquals(pinned.keys.sorted(), files.map { "${it.relativeTo(shipped)}" }.sorted())
        for (file in files) {
            val bytes = if (file.fileName.toString() == "SKILL.md") withoutLicence(file.readText()).toByteArray() else file.readBytes()
            assertEquals(pinned["${file.relativeTo(shipped)}"], sha256(bytes), "${file.relativeTo(shipped)} differs from his release")
        }
    }

    private fun withoutLicence(text: String) = text.replaceFirst(Regex("""(?s)(\A---\n.*?\n---\n)<!-- Matt Pocock's .*? -->\n"""), "$1")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
