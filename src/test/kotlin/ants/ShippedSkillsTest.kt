package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.assertEquals

/** The shipped Skills are plain (ADR 0024): what ties one to a Run is the Assignment of the shipped Tasks that use it. */
@OptIn(ExperimentalPathApi::class)
class ShippedSkillsTest {
    private val shipped = Path.of("src/main/resources/shipped")

    // ponytail: Matt Pocock's Skills become plain in #227; drop this set when it lands.
    private val matts = setOf("grill", "to-spec", "to-tickets", "implement", "tdd", "code-review", "pr", "codebase-design")

    private val coupling = Regex("""\b(Runs?|Tasks?|Rounds?|Results?|Ants)\b|ants-|varde-vault""")

    @Test
    fun `no shipped Skill mentions Runs, Tasks, Rounds, Results or Ants, nor has ants- metadata or a result schema`() {
        val found = shipped.resolve("skills").listDirectoryEntries().filter { it.name !in matts }.flatMap { folder ->
            (if (folder.resolve("result.schema.json").exists()) listOf("${folder.name}/result.schema.json") else emptyList()) +
                folder.walk().filter { it.isRegularFile() }.flatMap { file ->
                    file.readText().lines().withIndex().mapNotNull { (n, line) -> coupling.find(line)?.let { "${folder.name}/${file.name}:${n + 1}: ${it.value}" } }
                }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `every shipped Agent Task has an Assignment or lists exactly one Skill`() {
        val files = shipped.resolve("processes").listDirectoryEntries("*.bpmn") + shipped.resolve("templates").listDirectoryEntries("*.bpmn")
        val found = files.flatMap { file ->
            Bpmn.readModelFromFile(file.toFile()).getModelElementsByType(ServiceTask::class.java).filter { it.operatonTopic == AgentLauncher.TOPIC }
                .filterNot { task ->
                    !task.property(ASSIGNMENT).isNullOrBlank() || task.property(SKILLS)?.let(::words)?.size == 1 || task.property(SKILL) in matts
                }.map { "${file.name}: ${it.id}" }
        }
        assertEquals(emptyList(), found)
    }
}
