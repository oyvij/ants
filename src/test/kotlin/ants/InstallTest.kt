package ants

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `curl … install.sh | bash`, run as the user runs it, against a release folder holding what CI publishes: the
 * tarball the build makes and its SHA256SUMS, fetched over `file://` instead of from GitHub. HOME is temporary and a
 * fake `launchctl` is first on PATH, as in [LauncherTest]. JUnit deletes HOME after each test, also when it fails:
 * each holds an installed release, hundreds of MB.
 */
class InstallTest(@TempDir temp: Path) {
    private val home: Path = temp.toRealPath()
    private val antsHome = home.resolve(".ants")
    private val release = home.resolve("release").createDirectories()
    private val tarball = Path.of(System.getProperty("ants.tarball"))
    private val version = Regex(tarball("(.+)")).matchEntire(tarball.name)!!.groupValues[1]
    private val link = home.resolve(".local/bin/ants")

    init {
        tarball.copyTo(release.resolve(tarball.name))
        tarball.resolveSibling("SHA256SUMS").copyTo(release.resolve("SHA256SUMS"))
    }

    private fun run(vararg command: String) = ProcessBuilder(*command).redirectErrorStream(true).also {
        it.environment().clear()
        it.environment() += mapOf(
            "HOME" to "$home",
            "PATH" to "${LauncherTest.fakeLaunchctl}:/usr/bin:/bin",
            "ANTS_RELEASES" to release.toUri().toString(),
            "ANTS_MODELS" to "", // the installed Ants' update check reads no model table from Anthropic
        )
    }.start()

    private fun install() = run("/bin/bash", "-c", "curl -fsSL ${Path.of("install.sh").toAbsolutePath().toUri()} | bash").ran()

    @AfterEach
    fun stopTheStandIn() {
        if (link.exists()) run(link.toString(), "stop").waitFor()
    }

    @Test
    fun `install sh installs the release into a versioned folder, links ants and starts Ants`() {
        val installed = install()

        assertEquals(0, installed.exit, installed.output)
        assertContains(installed.output, "Ants is running at http://127.0.0.1:7323")
        assertEquals(Path.of("versions/$version"), antsHome.resolve("current").readSymbolicLink())
        assertEquals(antsHome.resolve("current/bin/ants"), link.readSymbolicLink())
        assertEquals(listOf("ants.jar"), antsHome.resolve("versions/$version/lib").listDirectoryEntries().map { it.name })
        assertEquals(listOf(version), antsHome.resolve("versions").listDirectoryEntries().map { it.name })
    }

    @Test
    fun `install sh keeps only the new version and the one before it`() {
        for (old in listOf("0.0.1", "0.0.2", "0.0.3")) antsHome.resolve("versions/$old").createDirectories()
        antsHome.resolve("current").createSymbolicLinkPointingTo(Path.of("versions/0.0.3"))
        antsHome.resolve("previous").createSymbolicLinkPointingTo(Path.of("versions/0.0.2"))

        val installed = install()

        assertEquals(0, installed.exit, installed.output)
        assertEquals(Path.of("versions/0.0.3"), antsHome.resolve("previous").readSymbolicLink())
        assertEquals(listOf("0.0.3", version), antsHome.resolve("versions").listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `the installed Ants runs on the Java runtime it brought`() {
        install()
        run(link.toString(), "stop").waitFor()

        val ants = run(link.toString(), "run", "--server.port=0", "--ants.shell=/bin/sh")
        try {
            val (pid, url) = listening(ants, antsHome.resolve("ants.lock"))
            val java = ProcessHandle.of(pid.toLong()).get().info().command().get()
            assertEquals(antsHome.resolve("versions/$version/runtime/bin/java").toString(), java)
            assertEquals("/", Browser(url).login().path)
        } finally {
            ants.destroy()
        }
        ants.waitFor()
    }

    @Test
    fun `a download that doesn't match its checksum installs nothing`() {
        release.resolve("SHA256SUMS").writeText("${"0".repeat(64)}  ${tarball.name}\n")

        val installed = install()

        assertEquals(1, installed.exit, installed.output)
        assertContains(installed.output, "doesn't match its checksum; nothing was installed")
        assertFalse(antsHome.resolve("versions").exists())
        assertFalse(link.exists())
        assertFalse(home.resolve("launchctl.log").exists())
    }
}
