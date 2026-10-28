package ants

import org.junit.jupiter.api.Test
import org.operaton.bpm.model.bpmn.Bpmn
import org.operaton.bpm.model.bpmn.instance.ServiceTask
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.assertEquals

/** The shipped Skills are plain (ADR 0024): what ties one to a Run is the Assignment of the shipped Tasks that use it. */
@OptIn(ExperimentalPathApi::class)
class ShippedSkillsTest {
    private val shipped = Path.of("src/main/resources/shipped")

    // Matt Pocock's Skills ship word for word (MattSkillsTest), so his text may well use these words.
    private val matts = setOf("grilling", "to-spec", "to-tickets", "implement", "tdd", "code-review", "pr", "codebase-design")

    private val coupling = Regex("""\b(Runs?|Tasks?|Rounds?|Results?|Ants)\b|ants-|varde-vault""")

    @Test
    fun `no shipped Skill mentions Runs, Tasks, Rounds, Results or Ants, nor has ants- metadata or a result schema`() {
        val found = shipped.resolve("skills").listDirectoryEntries().flatMap { folder ->
            (if (folder.resolve("result.schema.json").exists()) listOf("${folder.name}/result.schema.json") else emptyList()) +
                if (folder.name in matts) emptySequence() else folder.walk().filter { it.isRegularFile() }.flatMap { file ->
                    file.readText().lines().withIndex().mapNotNull { (n, line) -> coupling.find(line)?.let { "${folder.name}/${file.name}:${n + 1}: ${it.value}" } }
                }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun `every shipped Agent Task has an Assignment or lists exactly one Skill, and none is on ants_skill`() {
        val files = shipped.resolve("processes").listDirectoryEntries("*.bpmn") + shipped.resolve("templates").listDirectoryEntries("*.bpmn")
        val found = files.flatMap { file ->
            Bpmn.readModelFromFile(file.toFile()).getModelElementsByType(ServiceTask::class.java).filter { it.operatonTopic == AgentLauncher.TOPIC }
                .filter { task ->
                    task.property("ants.skill") != null || task.property(ASSIGNMENT).isNullOrBlank() && task.property(SKILLS)?.let(::words)?.size != 1
                }.map { "${file.name}: ${it.id}" }
        }
        assertEquals(emptyList(), found)
    }
}

class ShippedIdsTest {
    private val shipped = Path.of("src/main/resources/shipped")

    @Test
    fun `every shipped Agent and Skill has a fixed, readable id of its own`() {
        val agents = shipped.resolve("agents").listDirectoryEntries("*.md").associate { file -> file.nameWithoutExtension to Agent(file).id }
        val skills = shipped.resolve("skills").listDirectoryEntries().associate { folder -> folder.name to Yaml().load<Map<String, Any?>>(folder.resolve("ants.yaml").readText())["id"] }
        assertEquals(agents.keys.associateWith { "a-$it" }, agents)
        assertEquals(skills.keys.associateWith { "k-$it" }, skills)
        assertEquals((agents.values + skills.values).size, (agents.values + skills.values).toSet().size)
    }

    @Test
    fun `every shipped Agent starts on a level from 1 to 4, leaving 5 for escalation`() {
        val levels: Map<String, Any?> = shipped.resolve("agents").listDirectoryEntries("*.md").associate { file -> file.nameWithoutExtension to frontmatter(file.readText()).first["level"] }
        assertEquals(emptyMap(), levels.filterValues { it !in 1..4 })
    }
}
