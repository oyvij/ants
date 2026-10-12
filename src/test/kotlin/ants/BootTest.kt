package ants

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.web.server.context.WebServerApplicationContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootTest : AntsTest() {
    @Test
    fun `an empty ANTS_HOME gets a settings yaml`() {
        assertTrue(home.resolve("settings.yaml").exists())
    }

    @Test
    fun `every page needs a login`() {
        for (path in listOf("/", "/operaton/app/cockpit/", "/engine-rest/engine")) {
            assertEquals("/login", browser().get(path).path, path)
        }
    }

    @Test
    fun `tests never reach a real claude or nono, by its bare name or through the login shell`() {
        for (shell in listOf(listOf("/bin/sh", "-c"), listOf("$shell", "-l", "-c"))) {
            val found = ProcessBuilder(shell + "command -v claude nono").start().inputReader().readLines()
            assertEquals(emptyList(), found.filter { !it.startsWith("$loginBin") }, "found by $shell")
        }
    }

    @Test
    fun `admin admin logs in and sees Home`() {
        val home = browser().login()
        assertEquals(200, home.status)
        assertEquals("/", home.path)
        assertContains(home.body, "<h1>Home</h1>")
    }

    @Test
    fun `a wrong password stays on the login page`() {
        assertEquals("/login", browser().login(password = "nope").path)
    }

    @Test
    fun `Cockpit is reachable after login`() {
        val browser = browser().also { it.login() }
        val cockpit = browser.get("/operaton/app/cockpit/default/")
        assertEquals(200, cockpit.status)
        assertContains(cockpit.body, "Cockpit")
        assertContains(browser.get("/operaton/api/admin/auth/user/default").body, "\"userId\":\"admin\"")
    }
}

class BindTest {
    @Test
    fun `a non-loopback bind is refused while the password is the default`() {
        val error = assertThrows<Exception> {
            AntsTest.bootAnother(AntsTest.tempHome(), "--server.address=0.0.0.0")
        }
        assertContains(generateSequence<Throwable>(error) { it.cause }.joinToString { it.message.orEmpty() }, "default password")
    }
}

class ShutdownTest {
    @Test
    fun `an open Transcript stream doesn't hold up shutdown`() {
        val ants = AntsTest.startAnother(AntsTest.tempHome())
        val browser = Browser("http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}").also { it.login() }
        browser.events("/runs/r/tasks/t/transcript/events?from=0")
        Thread.sleep(1000)
        val took = measureTime { ants.close() }
        assertTrue(took < 10.seconds, "shutdown took $took")
    }
}

class ShippedTest {
    @Test
    fun `shipped is replaced whole on start when any file differs`() {
        val home = AntsTest.tempHome()
        val agents = home.resolve("shipped/agents").createDirectories()
        agents.resolve("shipped-writer.md").writeText("edited by hand")
        agents.resolve("removed.md").writeText("shipped by an older Ants")
        AntsTest.bootAnother(home)
        assertEquals(javaClass.getResource("/shipped/agents/shipped-writer.md")!!.readText(), agents.resolve("shipped-writer.md").readText())
        assertFalse(agents.resolve("removed.md").exists())
    }

    @Test
    fun `the shipped Processes are deployed on every start`() {
        val home = AntsTest.tempHome()
        repeat(2) {
            AntsTest.startAnother(home).use { ants ->
                val browser = Browser("http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}").also { it.login() }
                for (process in listOf("Set up Vault", "Ship small", "Ship with me")) assertContains(browser.get("/processes").body, process)
            }
        }
    }
}
