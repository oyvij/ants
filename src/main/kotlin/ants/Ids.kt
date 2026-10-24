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
