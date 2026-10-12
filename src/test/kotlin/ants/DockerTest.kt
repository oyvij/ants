package ants

import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.MINUTES
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The image the Dockerfile builds from `./gradlew bootJar`, run beside Postgres the way a server would run it. Where
 * Docker doesn't run, such as on a Mac without it, the test is skipped; CI always runs it.
 */
class DockerTest {
    private class Ran(val exit: Int, val output: String)

    private fun run(vararg command: String): Ran {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputReader().readText()
        check(process.waitFor(10, MINUTES))
        return Ran(process.exitValue(), output)
    }

    private fun docker(vararg args: String): String =
        run("docker", *args).also { check(it.exit == 0) { "docker ${args.joinToString(" ")}: ${it.output}" } }.output

    @Test
    fun `the image refuses to start without a password, and runs Ants on Postgres with its data on one volume`() {
        if (runCatching { run("docker", "info").exit }.getOrNull() != 0) {
            val help = "Docker isn't running, so the image test is skipped. CI always runs it."
            if (System.getenv("CI") != null) throw AssertionError(help)
            abort<Nothing>(help)
        }
        val id = "ants-test-${System.nanoTime()}"
        try {
            docker("build", "--tag", id, ".")

            val refused = run("docker", "run", "--rm", id)
            assertNotEquals(0, refused.exit)
            assertContains(refused.output, "set ANTS_PASSWORD or ANTS_PASSWORD_FILE")

            docker("network", "create", id)
            docker("run", "--detach", "--name", "$id-db", "--network", id, "--env", "POSTGRES_PASSWORD=pg", "--env", "POSTGRES_DB=ants", "postgres:17")
            eventually(60) { assertEquals(0, run("docker", "exec", "$id-db", "pg_isready", "--username", "postgres").exit) }
            docker(
                "run", "--detach", "--name", id, "--network", id, "--volume", "$id:/data", "--publish", "127.0.0.1::7323",
                "--env", "ANTS_PASSWORD=s3cret",
                "--env", "SPRING_DATASOURCE_URL=jdbc:postgresql://$id-db/ants",
                "--env", "SPRING_DATASOURCE_USERNAME=postgres",
                "--env", "SPRING_DATASOURCE_PASSWORD=pg",
                id,
            )
            val browser = Browser("http://${docker("port", id, "7323").lines().first().trim()}")
            try {
                eventually(180) { assertEquals(200, runCatching { browser.get("/login").status }.getOrNull()) }
                assertEquals("/", browser.login("s3cret").path)
                // The server, and so the login page, is up before Ants deploys the shipped Processes (on ApplicationReady).
                eventually(60) { assertContains(browser.get("/processes").body, "Ship small") }

                // The shipped Processes Ants deployed on start are in Postgres, and everything else on the volume.
                assertContains(docker("exec", "$id-db", "psql", "--username", "postgres", "--dbname", "ants", "--tuples-only", "--command", "select name_ from act_re_procdef"), "Ship small")
                val data = docker("exec", id, "ls", "/data")
                assertContains(data, "settings.yaml")
                assertContains(data, "shipped")
                assertFalse("engine" in data, "an H2 engine on the volume: $data")

                // Detect found the CLIs the image carries, and they run.
                val settings = docker("exec", id, "cat", "/data/settings.yaml")
                for (cli in listOf("claude", "codex", "git", "gh")) {
                    assertContains(settings, Regex("bin/$cli\\b"))
                    docker("exec", id, cli, "--version")
                }
            } catch (e: Throwable) {
                throw AssertionError("${e.message}\nAnts' log in its container:\n" + run("docker", "logs", id).output, e)
            }
        } finally {
            run("docker", "rm", "--force", id, "$id-db")
            run("docker", "network", "rm", id)
            run("docker", "volume", "rm", id)
            run("docker", "image", "rm", id)
        }
    }
}
