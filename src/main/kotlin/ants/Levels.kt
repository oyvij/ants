package ants

data class Level(val model: String, val effort: String?) {
    val flags: List<String> get() = listOf("--model", model) + (effort?.let { listOf("--effort", it) } ?: emptyList())
    val codexFlags: List<String> get() = listOf("-m", model) + (effort?.let { listOf("-c", "model_reasoning_effort=\"$it\"") } ?: emptyList())

    val label: String get() = listOfNotNull(model, effort).joinToString(" ")

    companion object {
        val RANGE: IntRange = 1..5
        const val START = 2

        private val CLAUDE: List<Level> = listOf(
            Level("haiku", null),
            Level("sonnet", "medium"),
            Level("opus", "medium"),
            Level("opus", "high"),
            Level("opus", "max"),
        )

        private const val CODEX_MINI = "gpt-5.1-codex-mini"
        private const val CODEX_MAIN = "gpt-5.2-codex"

        private val CODEX: List<Level> = listOf(
            Level(CODEX_MINI, "low"),
            Level(CODEX_MAIN, "low"),
            Level(CODEX_MAIN, "medium"),
            Level(CODEX_MAIN, "high"),
            Level(CODEX_MAIN, "xhigh"),
        )

        val FAMILIES: List<String> = listOf("haiku", "sonnet", "opus")

        fun of(text: String?): Int? = text?.toIntOrNull()?.takeIf { it in RANGE }

        fun claude(level: Int): Level = CLAUDE[level - 1]

        fun codex(level: Int): Level = CODEX[level - 1]

        fun above(level: Int): Int = minOf(level + 1, RANGE.last)

        fun family(model: String): Int? = FAMILIES.indexOfFirst { it in model }.takeIf { it >= 0 }?.plus(1)
    }
}
