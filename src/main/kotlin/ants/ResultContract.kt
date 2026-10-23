package ants

import com.networknt.schema.ExecutionContext
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.format.Format
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The Result contract: an envelope Ants owns around the `output` a Task promises. A `done` Result carries `output`,
 * a `stuck` one carries `needs`. The API refuses `oneOf` at a tool schema's top level, so `if`/`then`/`else` on
 * `outcome` splits the two cases instead. A file reference in `output` is a string with `"format": "path"`, relative
 * to the Run workspace root, and a repo to clone is one with `"format": "repo"`, a Source map name or a git URL.
 */
object ResultContract {
    /** The `--json-schema` for a Task promising output of [outputSchema]. */
    fun schema(outputSchema: Map<*, *>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "outcome" to mapOf("enum" to listOf("done", "stuck")),
            "summary" to mapOf("type" to "string", "description" to "One sentence, for people."),
            "output" to outputSchema,
            "needs" to mapOf("type" to "string", "description" to "What blocks you, and what would unblock you."),
        ),
        "required" to listOf("outcome", "summary"),
        "additionalProperties" to false,
        "if" to mapOf("properties" to mapOf("outcome" to mapOf("const" to "done"))),
        "then" to mapOf("required" to listOf("output"), "not" to mapOf("required" to listOf("needs"))),
        "else" to mapOf("required" to listOf("needs"), "not" to mapOf("required" to listOf("output"))),
    )

    /**
     * The `--output-schema` codex gets for [outputSchema]: codex sends it in strict mode, which refuses `if`/`then`/
     * `else`/`not` and optional keys, so every key is required and `output` and `needs` may be null. Ants still
     * judges the Result against [schema], with the null ones dropped ([withoutNulls]) (ADR 0003).
     */
    fun strict(outputSchema: Map<*, *>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "outcome" to mapOf("type" to "string", "enum" to listOf("done", "stuck")),
            "summary" to mapOf("type" to "string", "description" to "One sentence, for people."),
            "output" to mapOf("anyOf" to listOf(outputSchema, mapOf("type" to "null")), "description" to "Null when stuck."),
            "needs" to mapOf("type" to listOf("string", "null"), "description" to "What blocks you, and what would unblock you. Null when done."),
        ),
        "required" to listOf("outcome", "summary", "output", "needs"),
        "additionalProperties" to false,
    )

    /** A Result given under [strict], as [schema] judges it: without a null `output` or `needs`. */
    fun withoutNulls(result: Map<*, *>): Map<*, *> = result.filterNot { (key, value) -> value == null && key in setOf("output", "needs") }

    /**
     * Why [result] breaks [schema], with every file reference checked against [workspace] and, given a [sourceMap],
     * every repo against it; empty when it's valid.
     */
    fun errors(result: Any?, schema: Map<*, *>, workspace: Path, sourceMap: List<Repo>? = null): List<String> {
        val repos = InSourceMap(sourceMap)
        val dialect = Dialect.builder(Dialects.getDraft202012()).format(InWorkspace(workspace)).format(repos).build()
        val config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()
        val registry = SchemaRegistry.withDialect(dialect) { it.schemaRegistryConfig(config) }
        return registry.getSchema(json.writeValueAsString(schema), InputFormat.JSON)
            .validate(json.writeValueAsString(result), InputFormat.JSON).map { it.toString() } + repos.errors()
    }

    /** Whether [schema] has a string marked `"format": "repo"` anywhere. */
    fun namesRepos(schema: Any?): Boolean = when (schema) {
        is Map<*, *> -> schema["format"] == "repo" || schema.values.any(::namesRepos)
        is List<*> -> schema.any(::namesRepos)
        else -> false
    }

    /** The names of [sourceMap]'s repos, as a clause for the hand-off line and a repo's error. */
    fun names(sourceMap: List<Repo>) =
        if (sourceMap.isEmpty()) "the Run's Source map has no repos" else "the Source map's names are ${sourceMap.joinToString { it.name }}"

    /** Why [text] isn't a valid `result.schema.json` (a JSON Schema object, draft 2020-12); empty when it is, or when it's blank. */
    fun schemaErrors(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val node = try {
            json.readTree(text)
        } catch (e: JacksonException) {
            return listOf("It's not JSON: ${e.originalMessage}")
        }
        if (!node.isObject) return listOf("A result schema is a JSON object.")
        return metaSchema.validate(text, InputFormat.JSON).map { it.toString() }
    }

    private val metaSchema by lazy {
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(SchemaLocation.of(Dialects.getDraft202012().id))
    }

    /** A relative path to something inside the workspace, symlinks followed where they exist. */
    private class InWorkspace(private val workspace: Path) : Format {
        override fun getName() = "path"

        override fun matches(context: ExecutionContext, value: String): Boolean {
            val path = try {
                Path.of(value)
            } catch (_: InvalidPathException) {
                return false
            }
            val root = workspace.toRealPath()
            val resolved = root.resolve(path).normalize().let { if (it.exists()) it.toRealPath() else it }
            return !path.isAbsolute && resolved != root && resolved.startsWith(root)
        }
    }

    /**
     * A repo Clone repos can clone: a name in [sourceMap] or a git URL; anything passes without a [sourceMap]. It
     * collects the strings that don't instead of failing each, so [errors] tells them in one error with every name.
     */
    private class InSourceMap(private val sourceMap: List<Repo>?) : Format {
        private val unknown = mutableSetOf<String>()

        override fun getName() = "repo"

        override fun matches(context: ExecutionContext, value: String): Boolean {
            if (sourceMap != null && sourceMap.none { it.name == value } && CloneRepos.url(value) == null) unknown += value
            return true
        }

        fun errors() = if (unknown.isEmpty()) emptyList()
        else listOf("${unknown.joinToString()} ${if (unknown.size == 1) "is" else "are"} neither a Source map name nor a git URL; ${names(sourceMap!!)}")
    }

    private val json = JsonMapper.shared()
}
