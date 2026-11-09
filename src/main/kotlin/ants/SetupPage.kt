package ants

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Export and Import of a [Setup] on the Settings page: Export downloads one; Import uploads one and shows its preview,
 * where each item can be unticked and a Clash renamed, then lands what is ticked and shows the report.
 */
@Controller
class SetupPage(private val setup: Setup, private val clock: Clock) {
    /** The Setups uploaded and previewed, by the token their preview posts back, until they are imported. */
    private val uploaded = ConcurrentHashMap<String, ByteArray>()

    @GetMapping("/settings/setup/export")
    fun export(): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("ants-setup-${LocalDate.now(clock)}.zip").build().toString())
        .body(setup.export())

    @PostMapping("/settings/setup/import")
    fun upload(@RequestParam file: MultipartFile, model: Model): String {
        val zip = file.bytes
        val preview = try {
            setup.preview(zip)
        } catch (e: Setup.Refused) {
            return show(model, refusal = "${file.originalFilename ?: "The file"}: ${e.message}")
        }
        val token = UUID.randomUUID().toString()
        uploaded[token] = zip
        return show(model, preview = preview, token = token)
    }

    @PostMapping("/settings/setup/import/{token}")
    fun import(@PathVariable token: String, @RequestParam params: Map<String, String>, @RequestParam(name = "item", required = false) ticked: List<String>?, model: Model): String {
        val zip = uploaded.remove(token) ?: return show(model, refusal = "That preview has gone: upload the Setup again.")
        val choices = ticked.orEmpty().associateWith { Setup.Choice(params["rename:$it"]) }
        return try {
            show(model, report = setup.import(zip, choices))
        } catch (e: Setup.Refused) {
            show(model, refusal = e.message)
        }
    }

    private fun show(model: Model, preview: Setup.Preview? = null, token: String? = null, report: List<Setup.Landed>? = null, refusal: String? = null): String {
        model.addAttribute("preview", preview)
        model.addAttribute("token", token)
        model.addAttribute("report", report)
        model.addAttribute("refusal", refusal)
        return "setup"
    }
}
