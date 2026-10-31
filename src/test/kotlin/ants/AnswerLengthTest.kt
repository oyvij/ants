package ants

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContains
import kotlin.test.assertFalse

class AnswerLengthTest {
    @ParameterizedTest
    @ValueSource(strings = ["/process-builder.md", "/script-helper.md", "/template-builder.md"])
    fun `the helper's instructions keep each reply to 2–5 lines, never over 15, with its key reason in one line`(resource: String) {
        val instructions = javaClass.getResource(resource)!!.readText().replace(Regex("\\s+"), " ")
        assertContains(instructions, "Each reply is 2–5 lines, never over 15.")
        assertContains(instructions, "Give your key reason in one line.")
        assertContains(instructions, "Don't recap the tool steps: the dialog already shows them.")
        assertContains(instructions, "A proposal is a short list of steps, within the limit.")
        assertContains(instructions, "The Script hand-off code block and the Build log are exempt from the limit.")
        assertFalse("Keep answers short" in instructions)
    }
}
