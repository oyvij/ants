package ants

data class Level(val model: String, val effort: String?) {
    val flags: List<String> get() = listOf("--model", model) + (effort?.let { listOf("--effort", it) } ?: emptyList())

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

        private val FAMILIES: List<String> = listOf("haiku", "sonnet", "opus")

        fun claude(level: Int): Level = CLAUDE[level - 1]

        fun family(model: String): Int? = FAMILIES.indexOfFirst { it in model }.takeIf { it >= 0 }?.plus(1)
    }
}
