package com.samford.patient.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.ApiRoot
import com.samford.patient.core.network.HttpMobileApi
import com.samford.patient.core.network.OtpVerifyBody
import com.samford.patient.core.sync.SyncEngine
import com.samford.patient.core.time.Clock
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Second half of the cross-app end-to-end test. The doctor tablet app's own test completes a visit
 * through its real sync code and writes what it signed to a JSON file (samford.e2eIn). This test
 * signs in as that patient with the real patient client and proves the patient received exactly
 * the same pages (SHA-256 and bytes) for every document and version.
 */
@RunWith(AndroidJUnit4::class)
class CrossAppPatientVerifyTest {

    @Serializable
    data class SignedDocument(
        val documentId: String,
        val type: String,
        val version: Int = 1,
        val pngSha256: List<String>,
        val pdfSha256: String? = null,
    )

    @Serializable
    data class DoctorHandoff(
        val itBase: String? = null,
        val mobile: String,
        val visitId: String,
        val patientId: String,
        val documents: List<SignedDocument>,
    )

    @Test
    fun patientReceivesExactlyWhatTheTabletSigned() = runBlocking {
        val input = System.getProperty("samford.e2eIn").orEmpty()
        assumeTrue("Set -Psamford.e2eIn to the doctor app's handoff JSON", input.isNotEmpty())
        val handoff = Json { ignoreUnknownKeys = true }.decodeFromString(DoctorHandoff.serializer(), File(input).readText())
        val rawBase = System.getProperty("samford.itBase").orEmpty().ifEmpty { handoff.itBase.orEmpty() }
        assumeTrue("No backend origin", rawBase.isNotEmpty())
        // The handoff may carry an origin or a full API base; normalise both to …/api/mobile/v1/.
        val root = ApiRoot.normalize(rawBase)!!

        val db = SamfordDatabase.inMemory(ApplicationProvider.getApplicationContext())
        val deviceId = "e2e-phone-" + UUID.randomUUID().toString().take(12)
        var token: String? = null
        val api = HttpMobileApi(OkHttpClient(), { root }, { token }, { deviceId }, "SamfordPatient-E2E", 1, { "en" })
        val engine = SyncEngine(api, db, Clock.System)
        val blobs = BlobStore(Files.createTempDirectory("e2e-blobs").toFile(), db.blobs(), api, Clock.System)

        val challenge = api.requestOtp(handoff.mobile, deviceId, UUID.randomUUID().toString()) as ApiResult.Ok
        val session = api.verifyOtp(
            OtpVerifyBody(challenge.value.challengeId, challenge.value.demoCode!!, deviceId),
            UUID.randomUUID().toString(),
        ) as ApiResult.Ok
        token = session.value.token
        engine.storeProfiles(session.value.profiles)
        assertThat(session.value.profiles.map { it.profileId }).contains(handoff.patientId)

        val summary = engine.syncAll(force = false)
        assertThat(summary.failed).isFalse()

        val records = db.records()
        val report = StringBuilder("Cross-app E2E visit ${handoff.visitId}\n")
        for (signed in handoff.documents) {
            val doc = records.document(signed.documentId)
            assertThat(doc).isNotNull()
            assertThat(doc!!.visitId).isEqualTo(handoff.visitId)
            assertThat(doc.kind).isEqualTo(signed.type)
            val pages = records.pages(signed.documentId, signed.version)
            assertThat(pages.map { it.sha256 }).containsExactlyElementsIn(signed.pngSha256).inOrder()
            for (page in pages) {
                val file = (blobs.fetch(page.sha256, "image/png") as BlobResult.Ready).file
                assertThat(BlobStore.sha256Hex(file.readBytes())).isEqualTo(page.sha256)
            }
            signed.pdfSha256?.let { assertThat(records.versions(signed.documentId).first { v -> v.version == signed.version }.pdfSha256).isEqualTo(it) }
            report.append("  ${signed.type} ${signed.documentId} v${signed.version}: ${pages.size} page(s) identical\n")
        }
        println(report)
        db.close()
    }
}
