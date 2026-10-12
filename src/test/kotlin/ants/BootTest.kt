package ants

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.web.server.context.WebServerApplicationContext
import java.nio.file.Files
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

class PasswordFromEnvTest {
    private fun loginsOn(vararg args: String, password: String): List<String> =
        AntsTest.startAnother(AntsTest.tempHome(), *args).use { ants ->
            val url = "http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}"
            listOf(Browser(url).login().path, Browser(url).login(password).path)
        }

    @Test
    fun `ANTS_PASSWORD is the login password, not admin`() {
        assertEquals(listOf("/login", "/"), loginsOn("--ants.password=s3cret", password = "s3cret"))
    }

    @Test
    fun `ANTS_PASSWORD_FILE holds the login password, without its last newline`() {
        val file = Files.createTempFile("password", "").apply { writeText("from-file\n") }
        assertEquals(listOf("/login", "/"), loginsOn("--ants.password-file=$file", password = "from-file"))
    }

    @Test
    fun `with ANTS_PASSWORD the settings page says the password comes from it, and saving changes no password`() {
        val home = AntsTest.tempHome()
        AntsTest.startAnother(home, "--ants.password=s3cret").use { ants ->
            val url = "http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}"
            val browser = Browser(url).also { it.login("s3cret") }
            val page = browser.get("/settings").body
            assertContains(page, "ANTS_PASSWORD or ANTS_PASSWORD_FILE sets the password")
            assertFalse("""name="password"""" in page, "a password field the login ignores")

            browser.post("/settings", mapOf("action" to "save", "password" to "other"))
            assertFalse("password" in home.resolve("settings.yaml").readText().lines().filter { !it.startsWith("#") }.joinToString())
            assertEquals("/", Browser(url).login("s3cret").path)
        }
    }

    @Test
    fun `in Docker, Ants refuses to start without ANTS_PASSWORD or ANTS_PASSWORD_FILE, even with one in settings yaml`() {
        val home = AntsTest.tempHome().createDirectories()
        home.resolve("settings.yaml").writeText("password: s3cret\n")
        val error = assertThrows<Exception> { AntsTest.bootAnother(home, "--ants.docker=true") }
        assertContains(generateSequence<Throwable>(error) { it.cause }.joinToString { it.message.orEmpty() }, "ANTS_PASSWORD or ANTS_PASSWORD_FILE")
        AntsTest.bootAnother(AntsTest.tempHome(), "--ants.docker=true", "--ants.password=s3cret")
    }
}

class ShutdownTest {
    @Test
    fun `an open Transcript stream doesn't hold up shutdown`() {
        val ants = AntsTest.startAnother(AntsTest.tempHome())
        val browser = Browser("http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}").also { it.login() }
        browser.events("transcript=r/t&from=0", "transcript")
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

    @Test
    fun `a changed shipped Process deploys over the shipped one, never over the user's Save of it`() {
        val home = AntsTest.tempHome()
        AntsTest.startAnother(home).use { ants ->
            val browser = Browser("http://127.0.0.1:${(ants as WebServerApplicationContext).webServer!!.port}").also { it.login() }
            val vault = home.resolve("shipped/processes/set-up-vault.bpmn")
            browser.get("/processes") // its form token
            assertEquals("/processes/set_up_vault", browser.post("/processes/deploy", mapOf("bpmn" to vault.readText().replace("name=\"Set up Vault\"", "name=\"My Vault set-up\""))).path)
            // Ants ships a change to both: Set up Vault, which the user saved, and Ship small, which they didn't.
            vault.writeText(vault.readText().replace("name=\"Set up Vault\"", "name=\"Set up Vault, updated\""))
            val small = home.resolve("shipped/processes/ship-small.bpmn")
            small.writeText(small.readText().replace("name=\"Ship small\"", "name=\"Ship small, updated\""))
            ants.getBean(Processes::class.java).deployShipped()

            val list = browser.get("/processes").body
            assertContains(list, "My Vault set-up")
            assertFalse("Set up Vault, updated" in list)
            assertContains(list, "Ship small, updated")
        }
    }
}
