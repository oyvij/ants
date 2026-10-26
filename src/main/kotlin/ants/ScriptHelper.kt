package ants

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

@RestController
class ScriptHelper(
    private val chats: PageChats, private val scripts: ScriptsPage, private val saved: Scripts, private val settings: Settings, private val templates: TemplateEngine,
    private val json: JsonMapper,
) {
    private inner class Page : PageChat(chats) {
        override val name = "script-helper"
        override val title = "Script helper"
        override val pageName = "Script editor"
        override val instructions = INSTRUCTIONS
        override val idPrefix = "sh"
        override val lines = LINES
        override val tools = TOOLS

        override fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean> = try {
            when (name) {
                "buffer" -> json.readValue(ask(name, emptyMap<String, Any>()), String::class.java)
                "replace_buffer" -> ask(name, mapOf("code" to (arguments["code"]?.toString() ?: throw Refused("replace_buffer needs code."))))
                    .let { "The Script editor's code is now yours, unsaved." }
                "schemas" -> form().let { params ->
                    val script = ScriptForm.of(params).script(TEST_RUN, params["script"]?.firstOrNull()?.let(saved::named))
                    json.writeValueAsString(mapOf("inputs" to script.inputs, "output" to script.output))
                }
                "replace_schemas" -> replaceSchemas(arguments)
                "env" -> ScriptForm.of(form()).let { page ->
                    json.writeValueAsString(mapOf(
                        "vars" to page.varsMap.keys.toList(), "secrets" to page.own.map { it.name }.filter(String::isNotBlank),
                        "settingsSecrets" to settings.secrets().map { (label, variables) -> mapOf("label" to label, "variables" to variables, "linked" to (label in page.linked)) },
                    ))
                }
                "add_settings_secret" -> addSettingsSecret(arguments["label"]?.toString()?.trim().orEmpty(), (arguments["variables"] as? List<*>).orEmpty().map { "$it".trim() })
                "check" -> json.writeValueAsString(scripts.check(form()))
                "test_run" -> return testRun(arguments["inputs"] ?: emptyMap<String, Any>())
                else -> throw Refused("No tool $name here.")
            } to false
        } catch (e: Exception) {
            (e.message ?: e.toString()) to true
        }

        /** The page's form as it would post now, unsaved edits and all. */
        private fun form(): LinkedMultiValueMap<String, String> {
            val fields = json.readValue(ask("form", emptyMap<String, Any>()), List::class.java)
            val params = LinkedMultiValueMap<String, String>()
            fields.filterIsInstance<List<*>>().forEach { (name, value) -> params.add("$name", "$value") }
            return params
        }

        /** Hands the page the schema card's rows for each schema given, rendered as the page renders them, for it to swap in as one undo step. */
        private fun replaceSchemas(arguments: Map<*, *>): String {
            val rows = SCHEMAS.keys.filter { arguments[it] != null }.associateWith { name ->
                val schema = arguments[name] as? Map<*, *>
                if (schema == null || (schema["type"] ?: "object") != "object") throw Refused("The $name schema must be an object's JSON Schema, with its fields as properties.")
                ScriptSchema.rows(schema)
            }
            if (rows.isEmpty()) throw Refused("replace_schemas needs inputs, output or both.")
            ask("replace_schemas", rows.mapValues { (name, rows) ->
                StringOutput().also { templates.render("schemarows.kte", mapOf("rows" to rows, "prefix" to name, "depth" to 0, "field" to SCHEMAS[name]!!.second), it) }.toString()
            })
            val now = rows.mapValues { (_, rows) -> rows.takeIf { it.isNotEmpty() }?.let { ScriptSchema.schema(it) } }
            return "The Script editor's ${rows.keys.joinToString(" and ") { SCHEMAS[it]!!.first }} ${if (rows.size == 1) "is" else "are"} now yours, unsaved: " +
                "the user saves, and Ctrl-Z takes it back. The page's rows keep each field's type, description, required mark and nested fields only, so they hold: " +
                json.writeValueAsString(now)
        }

        /** Adds [label] to Settings' Secrets under [variables], with no value for the user to fill in there, unless it is there already; links it on the page either way. */
        private fun addSettingsSecret(label: String, variables: List<String>): String {
            val existing = settings.secrets()[label]
            if (existing == null) {
                if (label.isEmpty()) throw Refused("add_settings_secret needs a label.")
                if (variables.isEmpty()) throw Refused("add_settings_secret needs a variable name, such as SLACK_TOKEN.")
                variables.firstOrNull { !VARIABLE.matches(it) }?.let { throw Refused("A variable name is capitals, digits and underscores, such as SLACK_TOKEN, not $it.") }
                settings.save { it["secrets"] = settings.secrets() + (label to variables) }
            }
            ask("link_secret", mapOf("label" to label))
            val under = (existing ?: variables).joinToString(" or ") { "process.env.$it" }
            return if (existing == null) "Settings now has the Secret $label, with no value: tell the user to fill in its value in Settings. It is linked on the page, unsaved, " +
                "and the Script reads it as $under."
            else "Settings already has the Secret $label, so it is unchanged: it is linked on the page, unsaved, and the Script reads it as $under."
        }

        private fun testRun(inputs: Any): Pair<String, Boolean> {
            val params = form()
            params["sample"] = listOf(json.writeValueAsString(inputs))
            val result = scripts.testRun(params)
            return json.writeValueAsString(result) to (result["exitCode"] != 0)
        }
    }

    @PostMapping("/script-helper")
    fun open(@RequestParam(required = false) token: String?): Map<String, String> = mapOf("token" to chats.open(token, Page::class.java) { Page() })

    @PostMapping("/script-helper/send")
    fun send(@RequestParam token: String, @RequestParam message: String): Map<String, String> = chats.send(token, message, "Script helper")

    @PostMapping("/script-helper/stop")
    fun stop(@RequestParam token: String) = chats.stop(token)

    @PostMapping("/script-helper/answer")
    fun answer(@RequestParam token: String, @RequestParam call: String, @RequestParam(required = false) answer: String?, @RequestParam(required = false) error: String?) =
        chats.answer(token, call, answer, error)

    companion object {
        private val INSTRUCTIONS = ScriptHelper::class.java.getResource("/script-helper.md")!!.readText()

        private val VARIABLE = Regex("[A-Z_][A-Z0-9_]*")

        /** Each schema's form prefix, to what the helper's answers call it and what its Add button adds. */
        private val SCHEMAS = mapOf("inputs" to ("input schema" to "input"), "output" to ("output schema" to "field"))

        private val LINES: Map<String, (Map<*, *>) -> String> = mapOf(
            "buffer" to { _ -> "read the code" },
            "replace_buffer" to { _ -> "replaced the code" },
            "schemas" to { _ -> "read the schemas" },
            "replace_schemas" to { arguments -> "replaced the " + SCHEMAS.filterKeys { arguments[it] != null }.values.joinToString(" and ") { it.first }.ifEmpty { "schemas" } },
            "env" to { _ -> "read the vars and Secrets" },
            "add_settings_secret" to { arguments -> "added the Secret ${arguments["label"]} to Settings" },
            "check" to { _ -> "checked the Script" },
            "test_run" to { _ -> "test ran the code" },
        )

        private val TOOLS = listOf(
            Mcp.tool("buffer", "The code in the Script editor, as it is now with the user's unsaved edits."),
            Mcp.tool("replace_buffer", "Replaces the code in the Script editor with yours, unsaved: the user saves it, and Ctrl-Z takes it back.",
                mapOf("code" to "The whole new code, a JavaScript ES module.")),
            Mcp.tool("schemas", "The Script's input schema and output schema, as JSON Schema, as the page has them now with the user's unsaved edits: " +
                "{\"inputs\": {...}, \"output\": {...}}. output is null when the Script declares no payload; then any payload is accepted."),
            Mcp.tool("replace_schemas", "Replaces the Script's input schema, its output schema or both with yours, unsaved, as rows on the page: the user saves them, and Ctrl-Z " +
                "takes them back. Each is an object's JSON Schema; a field has a type (string, number, boolean, object, or array with items), a description, and for an object " +
                "its properties and required. An output schema with no properties removes it.",
                mapOf(
                    "inputs" to mapOf("type" to "object", "description" to "The input schema, what the Script reads as ants.inputs, such as " +
                        "{\"type\": \"object\", \"properties\": {\"text\": {\"type\": \"string\", \"description\": \"What to post\"}}, \"required\": [\"text\"]}."),
                    "output" to mapOf("type" to "object", "description" to "The output schema, what the Script hands back with ants.output, in the same form."),
                ), optional = SCHEMAS.keys),
            Mcp.tool("env", "The names the Script can read from process.env, never a value, as the page has them now with the user's unsaved edits: " +
                "its vars, its own Secrets, each read under its name, and every Secret kept in Settings, each read under its variables once linked to the Script " +
                "(linked: true) on the page's From Settings."),
            Mcp.tool("add_settings_secret", "Adds a Secret to Settings with no value, under its label and variable names, and links it to this Script on the page, unsaved: " +
                "the user fills in its value in Settings. A label Settings has already is only linked, under its own variables. Add one only when no Secret `env` lists fits.",
                mapOf("label" to "The Secret's label in Settings, such as slack.", "variables" to mapOf("type" to "array", "items" to mapOf("type" to "string"),
                    "description" to "The variable names the Script reads it under, such as [\"SLACK_TOKEN\"]: capitals, digits and underscores."))),
            Mcp.tool("check", "The validator's findings for the page as it is now, unsaved, the same the editor marks: the code's syntax, the code against both schemas " +
                "(ants.inputs.<name> read, ants.output({...}) written) and the schema rows, each with a severity, a sentence, and a line or a row. " +
                "Save refuses any error; warnings never block."),
            Mcp.tool("test_run", "Test runs the code in the Script editor as it is now, unsaved, with the Script's vars and Secrets, in a scratch folder: " +
                "gives the exit code, the output with every Secret masked as ••••, and the payload the code handed back with ants.output.",
                mapOf("inputs" to mapOf("type" to "object", "description" to "The sample inputs, as the Script gets them in ants.inputs, such as {\"text\": \"hello\"}.")),
                optional = setOf("inputs")),
        )
    }
}
