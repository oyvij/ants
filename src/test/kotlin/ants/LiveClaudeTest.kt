package ants

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Runs the real `claude` on your login, outside the fake's seam, because only the API knows which schemas it accepts
 * (ADR 0003). Kept out of `test`; run it with `./gradlew liveTest` after a claude update.
 */
@Tag("live")
class LiveClaudeTest {
    @Test
    fun `the real claude accepts the Result envelope and answers within it`() {
        val schema = ResultContract.schema(
            mapOf("type" to "object", "properties" to mapOf("file" to mapOf("type" to "string", "format" to "path")), "required" to listOf("file")),
        )
        val workspace = Files.createTempDirectory("live-claude")
        val claude = ProcessBuilder(
            "claude", "-p", "--model", "haiku", "--output-format", "json", "--json-schema", json.writeValueAsString(schema),
            "You are done. Report output.file as notes/a.md. Do nothing else.",
        ).directory(workspace.toFile()).redirectInput(File("/dev/null")).start()
        val out = claude.inputReader().readText().also { claude.waitFor() }
        val result = json.readValue(out, Map::class.java)
        assertEquals(false, result["is_error"], out)
        assertEquals(emptyList(), ResultContract.errors(result["structured_output"], schema, workspace))
    }

    private val json = JsonMapper.shared()
}
