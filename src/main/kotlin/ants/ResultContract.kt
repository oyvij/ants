package ants

import com.networknt.schema.ExecutionContext
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.format.Format
import tools.jackson.databind.json.JsonMapper
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The Result contract: an envelope Ants owns around the `output` a Task promises. A `done` Result carries `output`,
 * a `stuck` one carries `needs`. The API refuses `oneOf` at a tool schema's top level, so `if`/`then`/`else` on
 * `outcome` splits the two cases instead. A file reference in `output` is a string with `"format": "path"`, relative
 * to the Run workspace root.
 */
object ResultContract {
    /** The `--json-schema` for a Task promising [output]. */
    fun schema(output: Map<*, *>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "outcome" to mapOf("enum" to listOf("done", "stuck")),
            "summary" to mapOf("type" to "string", "description" to "One sentence, for people."),
            "output" to output,
            "needs" to mapOf("type" to "string", "description" to "What blocks you, and what would unblock you."),
        ),
        "required" to listOf("outcome", "summary"),
        "additionalProperties" to false,
        "if" to mapOf("properties" to mapOf("outcome" to mapOf("const" to "done"))),
        "then" to mapOf("required" to listOf("output"), "not" to mapOf("required" to listOf("needs"))),
        "else" to mapOf("required" to listOf("needs"), "not" to mapOf("required" to listOf("output"))),
    )

    /** Why [result] breaks [schema], with every file reference checked against [workspace]; empty when it's valid. */
    fun errors(result: Any?, schema: Map<*, *>, workspace: Path): List<String> {
        val dialect = Dialect.builder(Dialects.getDraft202012()).format(InWorkspace(workspace)).build()
        val config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()
        val registry = SchemaRegistry.withDialect(dialect) { it.schemaRegistryConfig(config) }
        return registry.getSchema(json.writeValueAsString(schema), InputFormat.JSON)
            .validate(json.writeValueAsString(result), InputFormat.JSON).map { it.toString() }
    }

    /** A relative path that stays inside the workspace. */
    private class InWorkspace(private val workspace: Path) : Format {
        override fun getName() = "path"

        override fun matches(context: ExecutionContext, value: String) = try {
            val path = Path.of(value)
            !path.isAbsolute && workspace.resolve(path).normalize().startsWith(workspace.normalize())
        } catch (_: InvalidPathException) {
            false
        }
    }

    private val json = JsonMapper.shared()
}
