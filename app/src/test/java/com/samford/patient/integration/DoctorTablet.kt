package com.samford.patient.integration

import com.samford.patient.core.blob.BlobStore
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Plays the doctor's tablet and the hospital desks through the same public API, so patient-app
 * integration tests can create fresh handwritten documents and drive pharmacy/lab states.
 */
class DoctorTablet(private val origin: String, personaId: String = "doctor-a") {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val api = "$origin/api/mobile/v1"
    private val deviceId = "it-tablet-" + UUID.randomUUID().toString().take(8)
    private val token: String

    init {
        val session = post("$api/doctor/sessions", buildJsonObject { put("personaId", personaId); put("deviceId", deviceId) }, auth = false)
        token = session["token"]!!.jsonPrimitive.content
    }

    class Page(val bytes: ByteArray, val sha256: String, val width: Int, val height: Int)

    class Bundle(val visitId: String, val patientId: String, val documents: Map<String, String>, val pages: Map<String, List<Page>>)

    /** Front desk books and checks in a brand-new synthetic patient for this doctor today. */
    fun checkIn(name: String, mobile: String): Pair<String, String> {
        val visit = post(
            "$origin/api/test-support/visits",
            buildJsonObject {
                put("doctorId", "doctor-a")
                putJsonObject("patient") {
                    put("name", name)
                    put("age", 37)
                    put("sex", "Female")
                    put("mobile", mobile)
                }
            },
            auth = false,
        )
        return visit["visitId"]!!.jsonPrimitive.content to visit["patientId"]!!.jsonPrimitive.content
    }

    fun startVisit(visitId: String) {
        post("$api/doctor/queue/$visitId/start", buildJsonObject { put("identityConfirmed", true) })
    }

    fun handwrittenPage(label: String, width: Int = 900, height: Int = 1270): Page {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color(22, 38, 120)
        g.stroke = BasicStroke(4f)
        g.font = Font(Font.SERIF, Font.ITALIC, 44)
        g.drawString("SYNTHETIC SAMPLE - $label", 40, 120)
        for (i in 0 until 8) g.drawArc(60 + i * 90, 300 + (i % 3) * 40, 80, 60, 0, 270)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        val bytes = out.toByteArray()
        return Page(bytes, BlobStore.sha256Hex(bytes), width, height)
    }

    fun pdf(label: String): Pair<ByteArray, String> {
        val text = "%PDF-1.4\n1 0 obj <</Type /Catalog /Pages 2 0 R>> endobj\n2 0 obj <</Type /Pages /Kids [3 0 R] /Count 1>> endobj\n" +
            "3 0 obj <</Type /Page /Parent 2 0 R /MediaBox [0 0 200 200]>> endobj\n% $label\ntrailer <</Root 1 0 R>>\n%%EOF\n"
        val bytes = text.toByteArray()
        return bytes to BlobStore.sha256Hex(bytes)
    }

    fun upload(bytes: ByteArray, sha256: String, mediaType: String) {
        val request = Request.Builder().url("$api/blobs/$sha256")
            .put(bytes.toRequestBody(mediaType.toMediaType()))
            .header("Authorization", "Bearer $token").header("X-Device-Id", deviceId)
            .build()
        http.newCall(request).execute().use { check(it.code == 200 || it.code == 201) { "PUT blob ${it.code}: ${it.body.string()}" } }
    }

    /** Signs and completes the visit with a two-page prescription (to the pharmacy) and investigations (to a lab). */
    fun completeVisit(visitId: String, patientId: String, labId: String = "central-lab"): Bundle {
        val rxPages = listOf(handwrittenPage("Rx page 1 $visitId"), handwrittenPage("Rx page 2 $visitId"))
        val invPages = listOf(handwrittenPage("Investigations $visitId"))
        val (rxPdf, rxPdfSha) = pdf("rx-$visitId")
        val (invPdf, invPdfSha) = pdf("inv-$visitId")
        (rxPages + invPages).forEach { upload(it.bytes, it.sha256, "image/png") }
        upload(rxPdf, rxPdfSha, "application/pdf")
        upload(invPdf, invPdfSha, "application/pdf")
        val bundleId = UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        val body = buildJsonObject {
            put("bundleId", bundleId)
            put("completedAt", now)
            putJsonArray("documents") {
                add(document(UUID.randomUUID().toString(), "PRESCRIPTION", "Prescription", rxPages, rxPdfSha, true, emptyList(), now))
                add(document(UUID.randomUUID().toString(), "INVESTIGATIONS", "Investigations", invPages, invPdfSha, false, listOf(labId), now))
            }
        }
        val response = post("$api/doctor/visits/$visitId/complete", body, idempotencyKey = bundleId)
        val ids = response["documents"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["type"]!!.jsonPrimitive.content to o["documentId"]!!.jsonPrimitive.content
        }
        return Bundle(visitId, patientId, ids, mapOf("PRESCRIPTION" to rxPages, "INVESTIGATIONS" to invPages))
    }

    /** Signs version n+1 of a document with a reason. Unchanged pages keep their hashes. */
    fun addendum(documentId: String, baseVersion: Int, pages: List<Page>, reason: String): Int {
        pages.forEach { upload(it.bytes, it.sha256, "image/png") }
        val (pdf, pdfSha) = pdf("addendum-$documentId-$baseVersion")
        upload(pdf, pdfSha, "application/pdf")
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("clientAddendumId", id)
            put("baseVersion", baseVersion)
            put("reason", reason)
            put("pages", pageArray(pages))
            put("pdfSha256", pdfSha)
            put("inkSha256", JsonNull)
            put("inkFormat", JsonNull)
            put("signedAt", java.time.Instant.now().toString())
        }
        return post("$api/doctor/documents/$documentId/addenda", body, idempotencyKey = id)["version"]!!.jsonPrimitive.content.toInt()
    }

    /** Pharmacy or lab desk action through the same rules as the web desks (isolated server only). */
    fun deliver(documentId: String, recipient: String, state: String, labId: String? = null, result: JsonObject? = null) {
        post(
            "$origin/api/test-support/deliveries",
            buildJsonObject {
                put("documentId", documentId)
                put("recipient", recipient)
                labId?.let { put("labId", it) }
                put("state", state)
                result?.let { put("result", it) }
            },
            auth = false,
        )
    }

    private fun document(
        clientDocId: String,
        type: String,
        title: String,
        pages: List<Page>,
        pdfSha: String,
        pharmacy: Boolean,
        labs: List<String>,
        signedAt: String,
    ) = buildJsonObject {
        put("clientDocId", clientDocId)
        put("type", type)
        put("title", title)
        put("pages", pageArray(pages))
        put("pdfSha256", pdfSha)
        put("inkSha256", JsonNull)
        put("inkFormat", JsonNull)
        putJsonObject("routing") {
            put("pharmacy", pharmacy)
            putJsonArray("labs") { labs.forEach { add(it) } }
            put("patient", true)
        }
        put("signedAt", signedAt)
    }

    private fun pageArray(pages: List<Page>): JsonArray = buildJsonArray {
        pages.forEachIndexed { i, p ->
            add(
                buildJsonObject {
                    put("index", i)
                    put("pngSha256", p.sha256)
                    put("widthPx", p.width)
                    put("heightPx", p.height)
                },
            )
        }
    }

    private fun post(url: String, body: JsonObject, auth: Boolean = true, idempotencyKey: String = UUID.randomUUID().toString()): JsonObject {
        val builder = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Idempotency-Key", idempotencyKey)
        if (auth) builder.header("Authorization", "Bearer $token").header("X-Device-Id", deviceId)
        http.newCall(builder.build()).execute().use { r ->
            val text = r.body.string()
            check(r.isSuccessful) { "POST $url -> ${r.code}: $text" }
            return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
        }
    }
}
