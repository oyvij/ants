package ants

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeClaudeTest {
    @Test
    fun `launches started at once each get a record of their own`() {
        val claude = FakeClaude()
        claude.session("draft", "{}")
        val n = 8
        repeat(5) { round ->
            val processes = List(n) { ProcessBuilder(claude.command.toString()).redirectErrorStream(true).start() }
            // Each reads its first line before it claims a number, so writing them all at once has them claim at once.
            processes.forEachIndexed { i, process -> process.outputWriter().use { it.write("""{"type":"user","message":{"content":"/draft $round-$i"}}""" + "\n") } }
            processes.forEach { assertTrue(it.waitFor(30, SECONDS)) }
            val stdins = claude.launches("draft").map { it.stdin }
            assertEquals((round + 1) * n, stdins.size)
            assertEquals(stdins.size, stdins.toSet().size)
        }
    }
}
