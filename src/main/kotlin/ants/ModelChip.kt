package ants

data class ModelChip(val cli: String, val model: String, val effort: String?, val retries: Int, val cap: Int) {
    val name: String get() = Level.FAMILIES.firstOrNull { it in model } ?: model

    val bars: Int get() = EFFORTS.indexOf(effort) + 1

    val badge: String? get() = "↻ $retries/$cap".takeIf { retries > 0 }

    val detail: String get() = listOfNotNull(model, effort?.let { "$it effort" }, "$retries of $cap retries").joinToString(" · ")

    val fields: Map<String, Any?> get() = mapOf("cli" to cli, "model" to model, "effort" to effort, "retries" to retries, "cap" to cap)

    companion object {
        val EFFORTS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

        fun of(event: Map<*, *>): ModelChip? {
            val model = event["model"] as? String ?: return null
            return ModelChip(event["cli"].toString(), model, event["effort"] as? String, (event["retries"] as? Number)?.toInt() ?: 0, (event["cap"] as? Number)?.toInt() ?: 0)
        }
    }
}
