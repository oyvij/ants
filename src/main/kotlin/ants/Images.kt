package ants

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/*
 * Images in the prompt and in Send. A pasted image (markdown.js) is stored as `images/<sha256>.<ext>` in Ants' home and
 * referenced from the text as `![image](/images/<sha256>.<ext>)`, so the text stays plain text and a Schedule's stored
 * value keeps working. At a launch or a Send each reference goes to the CLI as the image itself: an image block beside
 * the text for claude ([userContent]), `-i <file>` for codex ([referencedImages]).
 */

/** A reference to a stored image in a text, its alt text in group 1, its path in group 2 and its name in group 3. */
private val IMAGE_REFERENCE = Regex("""!\[([^\]\n]*)]\((/images/([0-9a-f]{64}\.(png|jpeg|gif|webp)))\)""")
private val IMAGE_NAME = Regex("""[0-9a-f]{64}\.(png|jpeg|gif|webp)""")

/**
 * The largest image Ants stores: 3.75 MB, whose base64 is the 5 MB the Claude API takes in an image block (a larger one
 * fails the launch).
 */
private const val CAP = 3_932_160

/** The stored images [text] references, in order, once each; a reference to an image that isn't stored stays text alone. */
fun referencedImages(text: String, home: Path): List<Path> =
    IMAGE_REFERENCE.findAll(text).map { home.resolve("images").resolve(it.groupValues[3]) }.distinct().filter { it.exists() }.toList()

/**
 * A claude user message's `content`: [text] alone, or with an image it references, an image block per image and then
 * the text. Probed on the real claude: the model sees the image, and the replay carries its data.
 */
// ponytail: the replay goes into transcript.jsonl as it is, base64 and all (up to 5 MB an image); strip the data there if Transcripts get heavy.
fun userContent(text: String, home: Path): Any = referencedImages(text, home).takeIf { it.isNotEmpty() }?.let { images ->
    images.map {
        mapOf("type" to "image", "source" to mapOf("type" to "base64", "media_type" to "image/${it.fileName.toString().substringAfter('.')}",
            "data" to Base64.getEncoder().encodeToString(it.readBytes())))
    } + mapOf("type" to "text", "text" to text)
} ?: text

/** [html], escaped text, with each reference in it to an image named in [stored] shown as the image; any other stays text. */
fun showImages(html: String, stored: Set<String>) =
    IMAGE_REFERENCE.replace(html) { if (it.groupValues[3] in stored) """<img src="${it.groupValues[2]}" alt="${it.groupValues[1]}">""" else it.value }

/** The name Ants stores an image under: its SHA-256 and [ext]. */
private fun imageName(bytes: ByteArray, ext: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".$ext"

/** The name of the stored image a claude image block ([userContent]) carries, from its `source`. */
fun imageName(source: Map<*, *>) = imageName(Base64.getDecoder().decode(source["data"].toString()), source["media_type"].toString().substringAfter('/'))

/** Stores a pasted image and serves it back. */
@RestController
class Images(@Value("\${ants.home}") home: Path) {
    private val images = home.resolve("images")

    /** Stores the request's body, an image, and answers the path to reference it by; anything else is refused with why. */
    @PostMapping("/images")
    fun upload(request: HttpServletRequest): ResponseEntity<String> {
        val bytes = request.inputStream.readNBytes(CAP + 1)
        val head = String(bytes, 0, minOf(bytes.size, 12), Charsets.ISO_8859_1)
        val ext = when {
            head.startsWith("\u0089PNG") -> "png"
            head.startsWith("ÿØÿ") -> "jpeg"
            head.startsWith("GIF8") -> "gif"
            head.startsWith("RIFF") && head.drop(8) == "WEBP" -> "webp"
            else -> return refuse("Only an image can be pasted: PNG, JPEG, GIF or WebP.")
        }
        if (bytes.size > CAP) return refuse("The image is over the 3.75 MB an Agent takes.")
        val name = imageName(bytes, ext)
        images.createDirectories().resolve(name).writeBytes(bytes)
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("/images/$name")
    }

    @GetMapping("/images/{name}")
    fun image(@PathVariable name: String): ResponseEntity<ByteArray> {
        val file = name.takeIf(IMAGE_NAME::matches)?.let(images::resolve)?.takeIf { it.exists() } ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/${name.substringAfter('.')}"))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable()) // named by its content
            .body(file.readBytes())
    }

    private fun refuse(why: String) = ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN).body(why)
}
