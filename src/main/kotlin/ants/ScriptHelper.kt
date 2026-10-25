package ants

import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

@RestController
class ScriptHelper(private val chats: PageChats, private val scripts: ScriptsPage, private val json: JsonMapper) {
    private inner class Page : PageChat(chats) {
        override val name = "script-helper"
        override val title = "Script helper"
        override val page = "Script editor"
        override val instructions = INSTRUCTIONS
        override val prefix = "sh"
        override val lines = LINES
        override val tools = TOOLS

        override fun call(name: String, arguments: Map<*, *>): Pair<String, Boolean> = try {
            when (name) {
                "buffer" -> json.readValue(ask(name, emptyMap<String, Any>()), String::class.java)
                "replace_buffer" -> ask(name, mapOf("code" to (arguments["code"]?.toString() ?: throw Refused("replace_buffer needs code."))))
                    .let { "The Script editor's code is now yours, unsaved." }
                "test_run" -> return testRun(arguments["inputs"] ?: emptyMap<String, Any>())
                else -> throw Refused("No tool $name here.")
            } to false
        } catch (e: Exception) {
            (e.message ?: e.toString()) to true
        }

        private fun testRun(inputs: Any): Pair<String, Boolean> {
            val fields = json.readValue(ask("form", emptyMap<String, Any>()), List::class.java)
            val params = LinkedMultiValueMap<String, String>()
            fields.filterIsInstance<List<*>>().forEach { (name, value) -> params.add("$name", "$value") }
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

        private val LINES: Map<String, (Map<*, *>) -> String> = mapOf(
            "buffer" to { _ -> "read the code" },
            "replace_buffer" to { _ -> "replaced the code" },
            "test_run" to { _ -> "test ran the code" },
        )

        private val TOOLS = listOf(
            Mcp.tool("buffer", "The code in the Script editor, as it is now with the user's unsaved edits."),
            Mcp.tool("replace_buffer", "Replaces the code in the Script editor with yours, unsaved: the user saves it, and Ctrl-Z takes it back.",
                mapOf("code" to "The whole new code, a JavaScript ES module.")),
            Mcp.tool("test_run", "Test runs the code in the Script editor as it is now, unsaved, with the Script's vars and Secrets, in a scratch folder: " +
                "gives the exit code, the output with every Secret masked as ••••, and the payload the code wrote to ANTS_OUTPUT.",
                mapOf("inputs" to mapOf("type" to "object", "description" to "The sample inputs, as the Script gets them on stdin, such as {\"text\": \"hello\"}.")),
                optional = setOf("inputs")),
        )
    }
}
