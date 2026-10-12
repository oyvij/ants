package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The `ants` launcher, run as the user runs it: from an install laid out as the tarball is (`bin/ants`, `lib/ants.jar`,
 * `runtime/` as the Java runtime, here the tests' own), through a link on the user's PATH as install.sh makes one.
 * HOME is temporary, so ANTS_HOME is `$HOME/.ants`, and a fake `launchctl` is first on PATH, never the real one.
 */
class LauncherTest {
    private val home: Path = Files.createTempDirectory("ants-launcher").toRealPath()
    private val antsHome = home.resolve(".ants")
    private val plist = home.resolve("Library/LaunchAgents/no.oyvij.ants.plist")
    private val uid = ProcessBuilder("id", "-u").start().inputReader().readText().trim()
    private val install = home.resolve("install")
    private val link = home.resolve(".local/bin/ants")

    init {
        Files.copy(Path.of("src/dist/bin/ants"), install.resolve("bin").createDirectories().resolve("ants"), COPY_ATTRIBUTES)
        install.resolve("lib").createDirectories().resolve("ants.jar").createSymbolicLinkPointingTo(Path.of(System.getProperty("ants.jar")))
        install.resolve("runtime").createSymbolicLinkPointingTo(Path.of(System.getProperty("java.home")))
        link.parent.createDirectories()
        link.createSymbolicLinkPointingTo(install.resolve("bin/ants"))
    }

    data class Ran(val exit: Int, val output: String)

    private fun launch(vararg command: String) = ProcessBuilder(link.toString(), *command).redirectErrorStream(true).also {
        it.environment().clear()
        it.environment() += mapOf("HOME" to "$home", "PATH" to "$fakeLaunchctl:/usr/bin:/bin")
    }.start()

    private fun ants(vararg command: String, input: String = ""): Ran {
        val process = launch(*command)
        process.outputWriter().use { it.write(input) }
        val output = process.inputReader().readText()
        return Ran(process.waitFor(), output)
    }

    private fun launchctl() = home.resolve("launchctl.log").takeIf { it.exists() }?.readLines().orEmpty()

    @AfterEach
    fun stopTheStandIn() {
        ants("stop")
    }

    @Test
    fun `start installs the launchd agent, starts it and says where Ants is`() {
        val start = ants("start")

        assertEquals(0, start.exit, start.output)
        assertContains(start.output, "Ants is running at http://127.0.0.1:7323")
        val agent = plist.readText()
        assertContains(agent, "<string>no.oyvij.ants</string>")
        assertContains(agent, "<string>$install/bin/ants</string>\n        <string>run</string>")
        assertContains(agent, "<key>RunAtLoad</key>\n    <true/>")
        assertContains(agent, "<key>ANTS_HOME</key>\n        <string>$antsHome</string>")
        assertContains(agent, "<string>$antsHome/logs/ants.log</string>")
        assertEquals("bootstrap gui/$uid $plist", launchctl().last())
    }

    @Test
    fun `a second start says where the running Ants is and starts none`() {
        ants("start")
        val pid = antsHome.resolve("ants.lock").readLines().first()

        val again = ants("start")

        assertEquals(0, again.exit, again.output)
        assertEquals("Ants is already running at http://127.0.0.1:7323 (pid $pid)\n", again.output)
        assertEquals(1, launchctl().count { it.startsWith("bootstrap") })
    }

    @Test
    fun `status says whether Ants is running and where`() {
        assertEquals(Ran(3, "Ants is not running\n"), ants("status"))
        ants("start")
        val status = ants("status")
        assertEquals(0, status.exit)
        assertContains(status.output, "Ants is running at http://127.0.0.1:7323")
    }

    @Test
    fun `stop stops the launchd agent and uninstalls it, so it stays stopped after logging in again`() {
        ants("start")
        val pid = antsHome.resolve("ants.lock").readLines().first().toLong()

        val stop = ants("stop")

        assertEquals(0, stop.exit, stop.output)
        assertEquals("Ants stopped\n", stop.output)
        assertEquals("bootout gui/$uid/no.oyvij.ants", launchctl().last())
        assertFalse(plist.exists())
        assertFalse(ProcessHandle.of(pid).isPresent)
        assertEquals(3, ants("status").exit)
        assertEquals("Ants is not running\n", ants("stop").output)
    }

    @Test
    fun `restart stops the running Ants and starts a new one`() {
        ants("start")
        val pid = antsHome.resolve("ants.lock").readLines().first()

        val restart = ants("restart")

        assertEquals(0, restart.exit, restart.output)
        assertContains(restart.output, "Ants is running at http://127.0.0.1:7323")
        assertNotEquals(pid, antsHome.resolve("ants.lock").readLines().first())
        assertEquals(listOf("bootout", "bootstrap"), launchctl().takeLast(2).map { it.substringBefore(' ') })
    }

    @Test
    fun `logs shows the end of the log launchd writes`() {
        antsHome.resolve("logs").createDirectories()
        antsHome.resolve("logs/ants.log").writeText((1..150).joinToString("") { "line $it\n" })

        val logs = ants("logs")

        assertEquals(0, logs.exit)
        assertEquals((51..150).joinToString("") { "line $it\n" }, logs.output)
    }

    @Test
    fun `rollback says there is no previous version yet`() {
        assertEquals(Ran(1, "There is no previous version to roll back to\n"), ants("rollback"))
    }

    @Test
    fun `run runs Ants in the foreground, which start then finds, and ants password changes its password`() {
        val run = launch("run", "--server.port=0", "--ants.shell=/bin/sh")
        val output = StringBuffer()
        Thread { run.inputReader().forEachLine { output.appendLine(it) } }.start()
        val lock = antsHome.resolve("ants.lock")
        try {
            val started = System.nanoTime()
            while (!lock.exists() || lock.readLines().size < 2) {
                check(run.isAlive && System.nanoTime() - started < 120e9) { "Ants never wrote its lock file:\n$output" }
                Thread.sleep(200)
            }
            val (pid, url) = lock.readLines()
            assertEquals(run.pid(), pid.toLong())
            assertEquals("/", Browser(url).login().path)

            assertEquals(Ran(0, "Ants is already running at $url (pid $pid)\n"), ants("start"))
            assertEquals(emptyList(), launchctl())

            assertEquals(Ran(0, "Password changed\n"), ants("password", input = "s3cret\ns3cret\n"))
            assertEquals("/login", Browser(url).login().path)
            assertEquals("/", Browser(url).login("s3cret").path)
        } finally {
            run.destroy()
        }
        run.waitFor()
        assertFalse(lock.exists(), "a stopped Ants leaves no lock file")
    }

    @Test
    fun `password refuses two passwords that differ, or an empty one`() {
        assertEquals(Ran(1, "The passwords differ; the password is unchanged\n"), ants("password", input = "one\ntwo\n"))
        assertEquals(Ran(1, "The password is empty; the password is unchanged\n"), ants("password", input = "\n\n"))
    }

    companion object {
        val fakeLaunchctl: Path = Path.of("src/test/resources/fake-launchctl").toAbsolutePath()
    }
}
