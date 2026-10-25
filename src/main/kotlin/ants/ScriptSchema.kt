package ants

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.json.JsonMapper

/**
 * A row of the schema card: one property of an object schema. [type] is one of [ScriptSchema.TYPES]; an object or a
 * list of objects has its fields as [rows].
 */
data class SchemaRow(val name: String, val type: String, val description: String = "", val required: Boolean = false, val rows: List<SchemaRow> = emptyList()) {
    val nests get() = type in NESTING

    companion object {
        val NESTING = setOf("object", "array:object")
    }
}

/**
 * A schema row as the Script page's form posts it, nameless ones too: [at] is its place, such as `inputs[0].rows[1]`,
 * and [rows] its fields.
 */
class PostedRow(val at: String, val name: String, val type: String, val description: String, val required: Boolean, val rows: List<PostedRow>) {
    val nests get() = type in SchemaRow.NESTING

    /** With no name, no description and no field that isn't blank too: a row left empty, which is none. */
    val blank: Boolean get() = name.isEmpty() && description.isEmpty() && rows.all { it.blank }

    /** The row, its nameless fields left out; null when it has no name. */
    fun row(): SchemaRow? = name.takeIf { it.isNotEmpty() }?.let { SchemaRow(it, type, description, required, rows.mapNotNull(PostedRow::row)) }
}

/**
 * A Script's schemas: object JSON Schemas in `script.yaml`, edited as [SchemaRow]s. The rows edit `type`, `description`,
 * `properties`, `required` and `items`; whatever else a schema file holds is kept where a row still reads the same.
 */
object ScriptSchema {
    /** The types a row picks from, as the schema writes them (a list as `array:` and its items' type), and as the page names them. */
    val TYPES = linkedMapOf(
        "string" to "text", "number" to "number", "boolean" to "yes/no", "object" to "object",
        "array:string" to "list of text", "array:number" to "list of numbers", "array:boolean" to "list of yes/no", "array:object" to "list of objects",
    )

    /** A value of each type, for a Test run's sample inputs to start from. A nesting type's `{}` stands for its fields' values. */
    private val SAMPLES: Map<String, Any> = TYPES.keys.associateWith { type ->
        when (type) {
            "string" -> ""
            "number" -> 0
            "boolean" -> false
            "object" -> emptyMap<String, Any>()
            "array:object" -> listOf(emptyMap<String, Any>())
            else -> emptyList<Any>()
        }
    }

    /** What the page's schema card knows of the types, as JSON: the types that nest, and a sample of each. */
    val nestingJson: String = JsonMapper.shared().writeValueAsString(SchemaRow.NESTING)
    val samplesJson: String = JsonMapper.shared().writeValueAsString(SAMPLES)

    private val EDITED = setOf("type", "description", "properties", "required", "items")

    val EMPTY: Map<String, Any?> = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

    /** The rows that show the object schema [schema]. */
    fun rows(schema: Map<*, *>): List<SchemaRow> {
        val required = Settings.strings(schema["required"]).orEmpty()
        return (schema["properties"] as? Map<*, *>).orEmpty().map { (name, value) ->
            val property = value as? Map<*, *> ?: emptyMap<String, Any>()
            val type = type(property)
            val fields = when (type) {
                "object" -> rows(property)
                "array:object" -> rows(property["items"] as? Map<*, *> ?: emptyMap<String, Any>())
                else -> emptyList()
            }
            SchemaRow("$name", type, property["description"]?.toString().orEmpty(), name in required, fields)
        }
    }

    /** The object schema [rows] make, keeping what [old], the schema they were read from, holds that rows don't edit. */
    fun schema(rows: List<SchemaRow>, old: Map<*, *> = emptyMap<String, Any>()): Map<String, Any?> {
        val schema = unedited(old)
        val properties = old["properties"] as? Map<*, *>
        schema["type"] = "object"
        schema["properties"] = rows.associate { it.name to property(it, properties?.get(it.name) as? Map<*, *>) }
        rows.filter { it.required }.map { it.name }.takeIf { it.isNotEmpty() }?.let { schema["required"] = it }
        return schema
    }

    /** The rows a form posts under [prefix], such as `inputs[0].name` and its fields as `inputs[0].rows[0].name`. A row with no name is none. */
    fun rows(params: Map<String, List<String>>, prefix: String): List<SchemaRow> = posted(params, prefix).mapNotNull(PostedRow::row)

    /** The rows a form posts under [prefix], nameless ones too. */
    fun posted(params: Map<String, List<String>>, prefix: String): List<PostedRow> {
        fun param(name: String) = params[name]?.firstOrNull().orEmpty()
        val pattern = Regex("^${Regex.escape(prefix)}\\[(\\d+)]\\.name$")
        return params.keys.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toInt() }.sorted().map { i ->
            val at = "$prefix[$i]"
            val type = param("$at.type").takeIf { it in TYPES } ?: "string"
            PostedRow(at, param("$at.name").trim(), type, param("$at.description").trim(), "$at.required" in params,
                if (type in SchemaRow.NESTING) posted(params, "$at.rows") else emptyList())
        }
    }

    /** Every row's name, nested ones too. */
    fun names(rows: List<SchemaRow>): List<String> = rows.flatMap { listOf(it.name) + names(it.rows) }

    /** Why [value] breaks [schema], one sentence each; empty when it doesn't. */
    fun errors(value: Any?, schema: Map<*, *>): List<String> =
        registry.getSchema(json.writeValueAsString(schema), InputFormat.JSON).validate(json.writeValueAsString(value), InputFormat.JSON).map { it.toString() }

    private fun type(property: Map<*, *>): String = when (property["type"]) {
        "number", "integer" -> "number"
        "boolean" -> "boolean"
        "object" -> "object"
        "array" -> "array:" + type(property["items"] as? Map<*, *> ?: emptyMap<String, Any>()).takeUnless { it.startsWith("array") }.orEmpty().ifEmpty { "string" }
        else -> "string"
    }

    private fun property(row: SchemaRow, old: Map<*, *>?): Map<String, Any?> {
        val same = old != null && type(old) == row.type
        val property = if (same) unedited(old) else linkedMapOf()
        if (!same) property["type"] = row.type.substringBefore(':') else old["type"]?.let { property["type"] = it }
        if (row.type == "object") property.putAll(schema(row.rows, old.takeIf { same }.orEmpty()).filterKeys { it != "type" })
        else if (row.type.startsWith("array:")) {
            val items = (old?.get("items") as? Map<*, *>).takeIf { same }
            property["items"] = if (row.type == "array:object") schema(row.rows, items.orEmpty())
            else items ?: mapOf("type" to row.type.substringAfter(':'))
        }
        if (row.description.isNotEmpty()) property["description"] = row.description
        return property
    }

    private fun unedited(old: Map<*, *>): LinkedHashMap<String, Any?> = old.entries.filter { it.key !in EDITED }.associateTo(linkedMapOf()) { (key, value) -> "$key" to value }

    private val registry by lazy { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) }

    private val json = JsonMapper.shared()
}
