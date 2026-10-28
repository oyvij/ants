package ants

data class ModelChip(val cli: String, val level: Level, val retries: Int, val cap: Int) {
    val name: String get() = Level.familyName(level.model)

    val bars: Int get() = EFFORTS.indexOf(level.effort) + 1

    val badge: String? get() = "↻ $retries/$cap".takeIf { retries > 0 }

    val detail: String get() = listOfNotNull(level.model, level.effort?.let { "$it effort" }, "$retries of $cap retries").joinToString(" · ")

    val fields: Map<String, Any?> get() = mapOf("cli" to cli, "model" to level.model, "effort" to level.effort, "retries" to retries, "cap" to cap)

    companion object {
        val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

        fun of(event: Map<*, *>): ModelChip? {
            val model = event["model"] as? String ?: return null
            return ModelChip(event["cli"].toString(), Level(model, event["effort"] as? String), (event["retries"] as? Number)?.toInt() ?: 0, (event["cap"] as? Number)?.toInt() ?: 0)
        }
    }
}
