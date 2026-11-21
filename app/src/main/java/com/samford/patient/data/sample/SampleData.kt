package com.samford.patient.data.sample

import androidx.room.withTransaction
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.network.AllergyDto
import com.samford.patient.core.network.AuthorDto
import com.samford.patient.core.network.ClinicalDocumentDto
import com.samford.patient.core.network.DocumentVersionDto
import com.samford.patient.core.network.EventDto
import com.samford.patient.core.network.HintsDto
import com.samford.patient.core.network.LabResultDto
import com.samford.patient.core.network.MedicineHintDto
import com.samford.patient.core.network.PageRefDto
import com.samford.patient.core.network.PatientRecordDto
import com.samford.patient.core.network.PatientSummaryDto
import com.samford.patient.core.network.ProfileDto
import com.samford.patient.core.network.RecipientDto
import com.samford.patient.core.network.RecordVisitDto
import com.samford.patient.core.network.RoutingDto
import com.samford.patient.core.network.TestHintDto
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.core.sync.EventMapper
import com.samford.patient.core.sync.RecordApplier
import com.samford.patient.core.sync.toEntity
import com.samford.patient.core.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private data class SampleVersion(
    val version: Int,
    val signedAt: String,
    val reason: String?,
    val pages: List<List<InkLine>>,
)

private data class SampleDocument(
    val id: String,
    val type: String,
    val title: String,
    val versions: List<SampleVersion>,
    val hints: HintsDto? = null,
    val recipients: List<RecipientDto> = emptyList(),
)

private data class SampleVisit(
    val id: String,
    val profile: ProfileDto,
    val date: String,
    val completedAt: String,
    val dateLabel: String,
    val doctor: String,
    val department: String,
    val documents: List<SampleDocument>,
)

/**
 * Synthetic records for offline sample mode, written to this phone only. Names carry "(sample)",
 * every page is watermarked, and none of it ever came from a hospital. The data goes through the
 * same record applier and event mapper as server data, so sample mode exercises the real code paths.
 */
class SampleDataInstaller(
    private val db: SamfordDatabase,
    private val blobs: BlobStore,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    suspend fun install(): String = withContext(Dispatchers.Default) {
        val now = clock.now().toEpochMilli()
        val documentDtos = mutableMapOf<String, ClinicalDocumentDto>()
        for (visit in VISITS) {
            for (doc in visit.documents) {
                val versions = doc.versions.map { v ->
                    val pages = v.pages.mapIndexed { index, lines ->
                        val spec = SamplePageSpec(
                            header = SampleHeader(
                                documentTitle = doc.title,
                                department = visit.department,
                                patientLine = "Patient: ${visit.profile.name} · ${visit.profile.age} ${visit.profile.sex} · UHID ${visit.profile.uhid}",
                                visitLine = "Visit: ${visit.dateLabel} · ${visit.id.uppercase()}",
                                doctorLine = "Doctor: ${visit.doctor}",
                                pageLabel = "Page ${index + 1} of ${v.pages.size} · version ${v.version}",
                            ),
                            lines = lines,
                            signature = visit.doctor,
                            seed = (doc.id.hashCode().toLong() shl 8) + v.version * 16 + index,
                        )
                        val png = SamplePageRenderer.render(spec)
                        val sha = blobs.put(png, "image/png", pinned = true)
                        PageRefDto(index, sha, SamplePageRenderer.WIDTH, SamplePageRenderer.HEIGHT)
                    }
                    DocumentVersionDto(
                        version = v.version, createdAt = v.signedAt, signedAt = v.signedAt, reason = v.reason,
                        authorName = visit.doctor, title = doc.title, pages = pages,
                        recordHash = BlobStore.sha256Hex("${doc.id}:${v.version}:${pages.joinToString { it.pngSha256 }}".toByteArray()),
                    )
                }
                documentDtos[doc.id] = ClinicalDocumentDto(
                    documentId = doc.id, visitId = visit.id, patientId = visit.profile.profileId, type = doc.type,
                    title = doc.title, author = AuthorDto("sample-doctor", visit.doctor), latestVersion = versions.maxOf { it.version },
                    routing = RoutingDto(pharmacy = doc.type == "PRESCRIPTION", labs = doc.recipients.mapNotNull { it.labId }, patient = true),
                    hints = doc.hints, versions = versions, recipients = doc.recipients,
                )
            }
        }

        db.withTransaction {
            val dao = db.records()
            dao.upsertProfiles(PROFILES.mapIndexed { i, p -> p.toEntity(i, sample = true) })
            val applier = RecordApplier(dao)
            val mapper = EventMapper(dao)
            for (profile in PROFILES) {
                val visits = VISITS.filter { it.profile.profileId == profile.profileId }
                val record = PatientRecordDto(
                    patient = PatientSummaryDto(profile.profileId, profile.uhid, profile.name, profile.age, profile.sex, "******0000"),
                    allergies = if (profile == ASHA) listOf(AllergyDto("Penicillin", "Skin rash", "Confirmed")) else emptyList(),
                    visits = visits.map { v ->
                        RecordVisitDto(v.id, v.date, "sample-doctor", v.doctor, v.department, "COMPLETED", v.completedAt, v.documents.map { documentDtos.getValue(it.id) })
                    },
                    labResults = LAB_RESULTS.filter { r -> visits.any { v -> v.documents.any { it.id == r.documentId } } },
                )
                applier.applyRecord(profile.profileId, record)
                dao.insertFeedItems(mapper.map(profile.profileId, eventsFor(profile.profileId), notify = false, nowMillis = now))
            }
        }
        settings.setSampleMode(true)
        settings.setActiveProfile(ASHA.profileId)
        ASHA.profileId
    }

    private fun eventsFor(profileId: String): List<EventDto> {
        var seq = if (profileId == ASHA.profileId) 1000 else 2000
        fun ev(type: String, at: String, visitId: String?, documentId: String?, vararg data: Pair<String, String>) = EventDto(
            eventId = (seq++).toString(), type = type, at = at, visitId = visitId, documentId = documentId, patientId = profileId,
            data = buildJsonObject { data.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        )
        return VISITS.filter { it.profile.profileId == profileId }.sortedBy { it.completedAt }.flatMap { v ->
            buildList {
                add(ev("VISIT_UPDATED", v.completedAt, v.id, null, "status" to "COMPLETED"))
                v.documents.forEach { d ->
                    val first = d.versions.first()
                    add(ev("DOCUMENT_AVAILABLE", first.signedAt, v.id, d.id, "version" to "1", "type" to d.type, "doctorName" to v.doctor))
                    d.versions.drop(1).forEach { amended ->
                        add(ev("DOCUMENT_AMENDED", amended.signedAt, v.id, d.id, "version" to amended.version.toString(), "reason" to (amended.reason ?: "")))
                    }
                    d.recipients.filter { it.kind != "PATIENT" && it.state != "AVAILABLE" && it.state != "VIEWED" }.forEach { r ->
                        add(
                            ev(
                                "DOCUMENT_STATE_CHANGED", r.updatedAt ?: first.signedAt, v.id, d.id,
                                "recipientKind" to r.kind, "state" to r.state, "labId" to (r.labId ?: ""), "version" to r.version.toString(),
                            ),
                        )
                    }
                }
                LAB_RESULTS.filter { r -> v.documents.any { it.id == r.documentId } }.forEach { r ->
                    add(ev("LAB_RESULT_READY", r.reportedAt ?: v.completedAt, v.id, r.documentId, "resultId" to r.resultId, "labId" to (r.labId ?: "")))
                }
            }
        }
    }

    companion object {
        private val ASHA = ProfileDto("sample-asha", "SMF-DEMO-0001", "Asha Devi (sample)", 42, "Female")
        private val ROHAN = ProfileDto("sample-rohan", "SMF-DEMO-0002", "Rohan (sample)", 9, "Male")
        private val PROFILES = listOf(ASHA, ROHAN)
        private const val LAB = "sample-central-lab"

        private fun t(text: String, indent: Int = 0) = InkLine.Text(text, indent = indent)

        private fun patientCopy(state: String = "AVAILABLE", version: Int = 1, at: String) =
            RecipientDto("PATIENT", null, "Patient app", state, version, at, null)

        private val VISITS = listOf(
            SampleVisit(
                id = "sv-1001", profile = ASHA, date = "2026-11-18", completedAt = "2026-11-18T04:28:00Z",
                dateLabel = "18 Nov 2026, 9:40 AM IST", doctor = "Dr. Meera Sinha (sample)", department = "General Medicine",
                documents = listOf(
                    SampleDocument(
                        "sd-1001-summary", "VISIT_SUMMARY", "Visit summary",
                        listOf(
                            SampleVersion(
                                1, "2026-11-18T04:28:00Z", null,
                                listOf(
                                    listOf(
                                        t("c/o fever x 3 days, body ache"),
                                        t("No cough. No breathlessness."),
                                        t("O/E: T 100.4 F, P 92/min, BP 124/80"),
                                        t("Throat mildly congested. Chest clear."),
                                        InkLine.Gap,
                                        t("Imp: Viral fever (likely)"),
                                        t("Advice: oral fluids, rest."),
                                        t("Review in 5 days, or earlier if"),
                                        t("fever persists beyond 3 days.", indent = 1),
                                    ),
                                ),
                            ),
                        ),
                        hints = HintsDto(diagnosis = "Viral fever (likely)", notes = "Oral fluids and rest. Review in 5 days."),
                        recipients = listOf(patientCopy(state = "VIEWED", at = "2026-11-18T04:40:00Z")),
                    ),
                    SampleDocument(
                        "sd-1001-rx", "PRESCRIPTION", "Prescription",
                        listOf(
                            SampleVersion(
                                1, "2026-11-18T04:28:00Z", null,
                                listOf(
                                    listOf(
                                        InkLine.Rx,
                                        t("1. Tab Paracetamol 500 mg"),
                                        t("1 tab three times a day x 3 days", indent = 1),
                                        t("2. ORS sachet in 1 L water, sip"),
                                        t("3. Tab Cetirizine 10 mg"),
                                        t("1 at night x 5 days", indent = 1),
                                    ),
                                ),
                            ),
                            SampleVersion(
                                2, "2026-11-18T05:05:00Z", "Paracetamol dose clarified: only when temperature is above 100 F.",
                                listOf(
                                    listOf(
                                        InkLine.Rx,
                                        t("1. Tab Paracetamol 500 mg"),
                                        InkLine.Text("1 tab three times a day x 3 days", struck = true, indent = 1),
                                        InkLine.Text("1 tab only if T above 100 F, max 3/day", indent = 1, correction = true),
                                        t("2. ORS sachet in 1 L water, sip"),
                                        t("3. Tab Cetirizine 10 mg"),
                                        t("1 at night x 5 days", indent = 1),
                                        InkLine.Gap,
                                        InkLine.Text("Amended 18/11, dose clarified. MS", correction = true),
                                    ),
                                ),
                            ),
                        ),
                        hints = HintsDto(
                            medicines = listOf(
                                MedicineHintDto("Paracetamol", "500 mg", "1 tablet", "Only if temperature above 100 F, max 3 a day", 3),
                                MedicineHintDto("ORS", null, "1 sachet in 1 L water", "Sip through the day"),
                                MedicineHintDto("Cetirizine", "10 mg", "1 tablet", "At night", 5),
                            ),
                        ),
                        recipients = listOf(
                            RecipientDto("PHARMACY", null, "Samford OPD Pharmacy", "DISPENSED", 2, "2026-11-18T05:20:00Z", null),
                            patientCopy(version = 2, at = "2026-11-18T05:05:00Z"),
                        ),
                    ),
                    SampleDocument(
                        "sd-1001-inv", "INVESTIGATIONS", "Investigations",
                        listOf(
                            SampleVersion(
                                1, "2026-11-18T04:28:00Z", null,
                                listOf(
                                    listOf(
                                        t("1. CBC"),
                                        t("2. ESR"),
                                        t("3. Fasting blood sugar"),
                                        InkLine.Gap,
                                        t("Lab: Samford Central Lab"),
                                        t("Fasting sample tomorrow morning"),
                                    ),
                                ),
                            ),
                        ),
                        hints = HintsDto(tests = listOf(TestHintDto("CBC"), TestHintDto("ESR"), TestHintDto("Fasting blood sugar"))),
                        recipients = listOf(
                            RecipientDto("LAB", LAB, "Samford Central Lab (sample)", "RESULT_READY", 1, "2026-11-18T09:50:00Z", null),
                            patientCopy(at = "2026-11-18T04:28:00Z"),
                        ),
                    ),
                ),
            ),
            SampleVisit(
                id = "sv-0902", profile = ASHA, date = "2026-09-02", completedAt = "2026-09-02T06:05:00Z",
                dateLabel = "2 Sep 2026, 11:15 AM IST", doctor = "Dr. Arvind Kumar (sample)", department = "Orthopaedics",
                documents = listOf(
                    SampleDocument(
                        "sd-0902-summary", "VISIT_SUMMARY", "Visit summary",
                        listOf(
                            SampleVersion(
                                1, "2026-09-02T06:05:00Z", null,
                                listOf(
                                    listOf(
                                        t("c/o right knee pain x 2 weeks"),
                                        t("Pain on climbing stairs"),
                                        t("No swelling, no injury"),
                                        t("O/E: mild crepitus, right knee"),
                                        t("Range of movement full, painful at end"),
                                    ),
                                    listOf(
                                        t("Imp: Early osteoarthritis, right knee"),
                                        t("Advice: quadriceps exercises daily"),
                                        t("Weight reduction, avoid squatting"),
                                        t("Review in 4 weeks"),
                                    ),
                                ),
                            ),
                        ),
                        recipients = listOf(patientCopy(state = "VIEWED", at = "2026-09-02T07:00:00Z")),
                    ),
                    SampleDocument(
                        "sd-0902-rx", "PRESCRIPTION", "Prescription",
                        listOf(
                            SampleVersion(
                                1, "2026-09-02T06:05:00Z", null,
                                listOf(
                                    listOf(
                                        InkLine.Rx,
                                        t("1. Tab Aceclofenac 100 mg"),
                                        t("1 twice a day after food x 5 days", indent = 1),
                                        t("2. Diclofenac gel, apply twice a day"),
                                        t("3. Cap Calcium + Vitamin D3"),
                                        t("1 daily x 30 days", indent = 1),
                                    ),
                                ),
                            ),
                        ),
                        recipients = listOf(
                            RecipientDto(
                                "PHARMACY", null, "Samford OPD Pharmacy", "PARTIALLY_DISPENSED", 1, "2026-09-02T06:30:00Z",
                                "Calcium + Vitamin D3 not in stock today",
                            ),
                            patientCopy(state = "VIEWED", at = "2026-09-02T07:00:00Z"),
                        ),
                    ),
                ),
            ),
            SampleVisit(
                id = "sv-1010", profile = ROHAN, date = "2026-10-10", completedAt = "2026-10-10T04:50:00Z",
                dateLabel = "10 Oct 2026, 10:05 AM IST", doctor = "Dr. Neha Gupta (sample)", department = "Paediatrics",
                documents = listOf(
                    SampleDocument(
                        "sd-1010-summary", "VISIT_SUMMARY", "Visit summary",
                        listOf(
                            SampleVersion(
                                1, "2026-10-10T04:50:00Z", null,
                                listOf(
                                    listOf(
                                        t("c/o loose stools x 2 days"),
                                        t("No blood, no vomiting"),
                                        t("Active, playful, well hydrated"),
                                        t("Weight 26 kg"),
                                        t("Imp: Acute gastroenteritis"),
                                        t("no dehydration", indent = 1),
                                        t("Advice: ORS after each loose stool"),
                                    ),
                                ),
                            ),
                        ),
                        recipients = listOf(patientCopy(at = "2026-10-10T04:50:00Z")),
                    ),
                    SampleDocument(
                        "sd-1010-rx", "PRESCRIPTION", "Prescription",
                        listOf(
                            SampleVersion(
                                1, "2026-10-10T04:50:00Z", null,
                                listOf(
                                    listOf(
                                        InkLine.Rx,
                                        t("1. ORS, 100 ml after each stool"),
                                        t("2. Syp Zinc 20 mg/5 ml"),
                                        t("5 ml once a day x 14 days", indent = 1),
                                    ),
                                ),
                            ),
                        ),
                        recipients = listOf(
                            RecipientDto("PHARMACY", null, "Samford OPD Pharmacy", "DISPENSED", 1, "2026-10-10T05:05:00Z", null),
                            patientCopy(at = "2026-10-10T04:50:00Z"),
                        ),
                    ),
                ),
            ),
        )

        private val LAB_RESULTS = listOf(
            LabResultDto("sr-hb", "sd-1001-inv", LAB, "Haemoglobin", "12.1", "g/dL", "12.0–15.0", "NORMAL", "2026-11-18T09:50:00Z", "Verified by the lab."),
            LabResultDto("sr-tlc", "sd-1001-inv", LAB, "Total leucocyte count", "9.8", "×10³/µL", "4.0–11.0", "NORMAL", "2026-11-18T09:50:00Z", "Verified by the lab."),
            LabResultDto("sr-plt", "sd-1001-inv", LAB, "Platelets", "1.9", "lakh/µL", "1.5–4.5", "NORMAL", "2026-11-18T09:50:00Z", "Verified by the lab."),
            LabResultDto("sr-esr", "sd-1001-inv", LAB, "ESR", "28", "mm/hr", "0–20", "ABNORMAL", "2026-11-18T09:50:00Z", "Slightly above the reference range. Your doctor will review it."),
        )
    }
}
