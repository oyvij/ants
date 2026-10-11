package ants

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** Boots Ants on a random port with a temporary ANTS_HOME (and so a temporary H2), driven over HTTP. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
abstract class AntsTest {
    @LocalServerPort
    var port = 0

    fun browser() = Browser("http://127.0.0.1:$port")

    companion object {
        val home: Path = tempHome()

        /** A temporary ANTS_HOME that doesn't exist yet. */
        fun tempHome(): Path = Files.createTempDirectory("ants-test").resolve("ants")

        @JvmStatic
        @DynamicPropertySource
        fun antsHome(registry: DynamicPropertyRegistry) = registry.add("ants.home") { home.toString() }
    }
}

/** A user with a browser: keeps cookies, follows redirects and posts forms with their CSRF token. */
class Browser(private val base: String) {
    class Page(val status: Int, val path: String, val body: String)

    private val client = HttpClient.newBuilder()
        .cookieHandler(CookieManager())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private var csrf: String? = null

    fun get(path: String) = send(HttpRequest.newBuilder(URI("$base$path")).GET())

    fun post(path: String, form: Map<String, String>): Page {
        val body = (form + listOfNotNull(csrf?.let { "_csrf" to it }))
            .entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return send(
            HttpRequest.newBuilder(URI("$base$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )
    }

    fun login(password: String = "admin"): Page {
        get("/login")
        return post("/login", mapOf("username" to "admin", "password" to password))
    }

    private fun send(request: HttpRequest.Builder): Page {
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        csrf = Regex("""name="_csrf"[^>]*value="([^"]+)"""").find(body)?.groupValues?.get(1) ?: csrf
        return Page(response.statusCode(), response.uri().path, body)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
