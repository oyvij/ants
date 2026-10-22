package ants

const val VERBOSITY = "ants.verbosity"

enum class Verbosity(val label: String) {
    BRIEF("Brief"), NORMAL("Normal"), DETAILED("Detailed");

    val value = name.lowercase()

    /** The `verbosity` key a Profile keeps in `settings.yaml`: none at normal. */
    val setting: Map<String, String> get() = if (this == NORMAL) emptyMap() else mapOf("verbosity" to value)

    companion object {
        fun parse(value: Any?): Verbosity? = entries.firstOrNull { it.value == value }

        fun of(value: Any?): Verbosity = parse(value) ?: NORMAL
    }
}
