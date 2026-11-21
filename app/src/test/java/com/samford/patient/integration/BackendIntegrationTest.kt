package com.samford.patient.integration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.HttpMobileApi
import com.samford.patient.core.network.OtpVerifyBody
import com.samford.patient.core.sync.FeedKinds
import com.samford.patient.core.sync.SyncEngine
import com.samford.patient.core.time.Clock
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real client stack (HTTP API, sync engine, Room, verified blob cache) against the real
 * Samford mock backend started in isolated mode, e.g.
 * `npm run mobile:isolated -- --port 4321 --data <dir> --reset --relaxed-limits --test-support`, then
 * `./gradlew :app:testDebugUnitTest --tests '*BackendIntegrationTest' -Psamford.itBase=http://127.0.0.1:4321`.
 * Skipped when no backend is configured.
 */
@RunWith(AndroidJUnit4::class)
class BackendIntegrationTest {
    private val origin = System.getProperty("samford.itBase").orEmpty().trimEnd('/')
    private val clients = mutableListOf<PatientClient>()

    @Before
    fun requireBackend() {
        assumeTrue("Set -Psamford.itBase to run backend integration tests", origin.isNotEmpty())
        System.setProperty("java.awt.headless", "true")
    }

    @After
    fun closeClients() = clients.forEach { it.db.close() }

    inner class PatientClient {
        val db = SamfordDatabase.inMemory(ApplicationProvider.getApplicationContext())
        private val deviceId = "it-phone-" + UUID.randomUUID().toString().take(12)
        @Volatile var token: String? = null
        val api = HttpMobileApi(
            client = OkHttpClient(),
            roots = { "$origin/api/mobile/v1/".toHttpUrl() },
            tokens = { token },
            devices = { deviceId },
            userAgent = "SamfordPatient-IT",
            appVersionCode = 1,
            language = { "en" },
        )
        val engine = SyncEngine(api, db, Clock.System)
        val blobs = BlobStore(Files.createTempDirectory("it-blobs").toFile(), db.blobs(), api, Clock.System)

        init {
            clients += this
        }

        suspend fun signIn(mobile: String): List<String> {
            val challenge = api.requestOtp(mobile, deviceId, UUID.randomUUID().toString())
            check(challenge is ApiResult.Ok) { "OTP request failed: $challenge" }
            val code = challenge.value.demoCode!!
            assertThat(code).matches("[0-9]{6}")
            val session = api.verifyOtp(OtpVerifyBody(challenge.value.challengeId, code, deviceId, "IT phone"), UUID.randomUUID().toString())
            check(session is ApiResult.Ok) { "OTP verify failed: $session" }
            token = session.value.token
            engine.storeProfiles(session.value.profiles)
            return session.value.profiles.map { it.profileId }
        }
    }

    @Test
    fun seededFamilySignsInAndEveryPageMatchesItsHash() = runBlocking {
        val phone = PatientClient()
        assertThat(phone.signIn("9000000001")).containsAtLeast("patient-a", "patient-child")

        val first = phone.engine.syncAll(force = false)
        assertThat(first.signedOut).isNull()
        assertThat(first.failed).isFalse()

        val records = phone.db.records()
        val docs = records.documentIds("patient-a").mapNotNull { records.document(it) }
        assertThat(docs.map { it.kind }).containsAtLeast("VISIT_SUMMARY", "PRESCRIPTION")
        var verified = 0
        for (doc in docs) {
            for (page in records.pages(doc.id, doc.currentVersion)) {
                val file = (phone.blobs.fetch(page.sha256, "image/png") as BlobResult.Ready).file
                assertThat(BlobStore.sha256Hex(file.readBytes())).isEqualTo(page.sha256)
                verified++
            }
        }
        assertThat(verified).isGreaterThan(0)
        val prescription = docs.first { it.kind == "PRESCRIPTION" }
        assertThat(records.recipients(prescription.id).map { it.state }).contains("DISPENSED")

        // Steady state: one conditional request, answered 304.
        val second = phone.engine.syncAll(force = false)
        assertThat(second.requests).isEqualTo(1)
        println("IT seeded: first sync requests=${first.requests}, steady-state requests=${second.requests}, pages verified=$verified")
    }

    @Test
    fun visitCompletedOnTheTabletReachesThePatientWithIdenticalPagesThenDispenseLabAndAddendum() = runBlocking {
        val tablet = DoctorTablet(origin)
        val mobile = "9" + (100_000_000 + Random.nextInt(800_000_000)).toString()
        val (visitId, patientId) = tablet.checkIn("IT patient ${mobile.takeLast(4)}", mobile)
        tablet.startVisit(visitId)
        val bundle = tablet.completeVisit(visitId, patientId)
        val rxId = bundle.documents.getValue("PRESCRIPTION")
        val invId = bundle.documents.getValue("INVESTIGATIONS")

        val phone = PatientClient()
        assertThat(phone.signIn(mobile)).contains(patientId)
        phone.engine.syncAll(force = false)
        val records = phone.db.records()

        // 1. Exactly the pixels the tablet uploaded.
        val rxPages = records.pages(rxId, 1)
        assertThat(rxPages.map { it.sha256 }).containsExactlyElementsIn(bundle.pages.getValue("PRESCRIPTION").map { it.sha256 }).inOrder()
        bundle.pages.getValue("PRESCRIPTION").forEachIndexed { i, uploaded ->
            val file = (phone.blobs.fetch(rxPages[i].sha256, "image/png") as BlobResult.Ready).file
            assertThat(file.readBytes()).isEqualTo(uploaded.bytes)
        }
        assertThat(records.recipients(rxId).map { it.kind }).contains("PHARMACY")
        assertThat(records.recipients(invId).single { it.kind == "LAB" }.labId).isEqualTo("central-lab")

        // 2. The pharmacy dispenses: one notification-driven sync updates the state and Home.
        tablet.deliver(rxId, "PHARMACY", "DISPENSED")
        val afterDispense = phone.engine.syncAll(force = false)
        assertThat(records.recipients(rxId).single { it.kind == "PHARMACY" }.state).isEqualTo("DISPENSED")
        assertThat(records.unnotifiedFeedItems().map { it.kind }).contains(FeedKinds.MEDICINES_DISPENSED)

        // 3. The lab collects the sample and releases a result.
        tablet.deliver(invId, "LAB", "SAMPLE_COLLECTED", labId = "central-lab")
        tablet.deliver(
            invId, "LAB", "RESULT_READY", labId = "central-lab",
            result = buildJsonObject {
                put("testName", "Haemoglobin")
                put("value", "12.4")
                put("unit", "g/dL")
                put("refRange", "12.0-15.0")
                put("flag", "NORMAL")
            },
        )
        phone.engine.syncAll(force = false)
        assertThat(records.unnotifiedFeedItems().map { it.kind }).contains(FeedKinds.LAB_RESULT_READY)
        val results = records.observeLabResults(patientId).first()
        assertThat(results.map { it.testName }).contains("Haemoglobin")
        assertThat(results.first { it.testName == "Haemoglobin" }.documentId).isEqualTo(invId)

        // 4. The doctor signs an addendum: version 2 arrives, version 1 stays readable and unchanged.
        val original = bundle.pages.getValue("PRESCRIPTION")
        val corrected = listOf(original[0], tablet.handwrittenPage("Rx page 2 corrected $visitId"))
        assertThat(tablet.addendum(rxId, 1, corrected, "Dose corrected after review")).isEqualTo(2)
        phone.engine.syncAll(force = false)
        val doc = records.document(rxId)!!
        assertThat(doc.currentVersion).isEqualTo(2)
        assertThat(records.versions(rxId).map { it.version }).containsExactly(2, 1).inOrder()
        assertThat(records.versions(rxId).first().reason).isEqualTo("Dose corrected after review")
        assertThat(records.pages(rxId, 1).map { it.sha256 }).containsExactlyElementsIn(original.map { it.sha256 }).inOrder()
        assertThat(records.pages(rxId, 2).map { it.sha256 }).containsExactlyElementsIn(corrected.map { it.sha256 }).inOrder()
        assertThat(records.unnotifiedFeedItems().map { it.kind }).contains(FeedKinds.DOCUMENT_AMENDED)

        // 5. Viewing the latest version is acknowledged to the hospital exactly once.
        phone.db.acks().insert(com.samford.patient.core.db.PendingAckEntity(rxId, 2, System.currentTimeMillis(), UUID.randomUUID().toString()))
        assertThat(phone.engine.flushAcks()).isEqualTo(1)
        println("IT E2E: visit=$visitId pages verified=${original.size}, dispense sync requests=${afterDispense.requests}")
    }

    @Test
    fun anotherFamilyCannotReadThisRecordOrItsPages() = runBlocking {
        val owner = PatientClient()
        owner.signIn("9000000001")
        owner.engine.syncAll(force = false)
        val doc = owner.db.records().documentIds("patient-a").first()
        val page = owner.db.records().pages(doc, owner.db.records().document(doc)!!.currentVersion).first()

        val stranger = PatientClient()
        stranger.signIn("9000000003")
        val docResult = stranger.api.document(doc, null)
        assertThat(docResult).isInstanceOf(ApiResult.Err::class.java)
        assertThat((docResult as ApiResult.Err).error.httpStatus).isEqualTo(404)
        assertThat(stranger.blobs.fetch(page.sha256)).isInstanceOf(BlobResult.Failed::class.java)
        val record = stranger.api.record("patient-a", null)
        assertThat((record as ApiResult.Err).error.httpStatus).isEqualTo(404)
    }

    @Test
    fun amendedSeedPrescriptionKeepsBothVersions() = runBlocking {
        val phone = PatientClient()
        phone.signIn("9000000002")
        phone.engine.syncAll(force = false)
        val records = phone.db.records()
        val amended = records.documentIds("patient-b").mapNotNull { records.document(it) }.first { it.currentVersion >= 2 }
        assertThat(records.versions(amended.id).map { it.version }).containsAtLeast(2, 1)
        assertThat(records.versions(amended.id).first().reason).isNotEmpty()
        val wrongCode = phone.api.verifyOtp(OtpVerifyBody("no-such-challenge", "000000", "it-phone-x"), UUID.randomUUID().toString())
        assertThat(wrongCode).isInstanceOf(ApiResult.Err::class.java)
    }
}
