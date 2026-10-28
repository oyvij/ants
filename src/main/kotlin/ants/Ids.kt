package ants

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.readText

private val random = SecureRandom()

private const val ID_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789"

const val AGENT_ID = 'a'
const val SKILL_ID = 'k'
const val SKILL_ID_FILE = "ants.yaml"

fun newId(prefix: Char, taken: (String) -> Boolean): String =
    generateSequence { "$prefix-" + (1..6).map { ID_CHARACTERS[random.nextInt(ID_CHARACTERS.length)] }.joinToString("") }.first { !taken(it) }

data class Identified(val id: String, val kind: String, val name: String, val where: Path)

val Agent.identified get() = id?.let { Identified(it, "Agent", name, file) }

val Skill.identified get() = id?.let { Identified(it, "Skill", name, folder) }

class IdFile(private val file: Path, private val prefix: Char) {
    @Synchronized
    fun of(path: Path): String {
        val key = path.toAbsolutePath().normalize().toString()
        val ids = read()
        ids.entries.firstOrNull { it.value == key }?.let { return it.key }
        val id = newId(prefix) { it in ids }
        file.writeAtomically(yaml.dump(ids + (id to key)))
        return id
    }

    private fun read(): Map<String, String> = if (file.exists()) {
        (Yaml().load<Any?>(file.readText()) as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() }
    } else emptyMap()
}

/** An agent file's [text] with [id] as its `id:`, in its frontmatter, which it gets if it has none. */
fun withId(text: String, id: String): String = when {
    "id" in frontmatter(text).first -> Regex("(?m)^id:.*$").replaceFirst(text, "id: $id")
    Regex("""\A---\R.*?\R---""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(text) -> text.replaceFirst(Regex("""\A---\R"""), "---\nid: $id\n")
    else -> "---\nid: $id\n---\n$text"
}
