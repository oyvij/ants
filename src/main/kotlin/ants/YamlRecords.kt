package ants

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Records Ants keeps itself, as a YAML list in [file] under ANTS_HOME, each record a map read by [of] and written by
 * [toYaml]. Reads and changes are one at a time, and a change writes the whole file atomically.
 */
class YamlRecords<T>(private val file: Path, private val of: (Map<*, *>) -> T, private val toYaml: (T) -> Map<String, Any?>) {
    fun all(): List<T> = synchronized(this) {
        if (!file.exists()) emptyList() else yaml.load<List<Map<*, *>>?>(file.readText()).orEmpty().map(of)
    }

    /** Writes the records [change] makes of them. */
    fun change(change: (List<T>) -> List<T>) = update { change(it) to Unit }

    /** Writes the records [change] makes of them, and gives back the answer it made with them. */
    fun <R> update(change: (List<T>) -> Pair<List<T>, R>): R = synchronized(this) {
        val (records, answer) = change(all())
        file.writeAtomically(yaml.dump(records.map(toYaml)))
        answer
    }
}
