package com.samford.patient.core.sync

import com.samford.patient.core.db.DocumentEntity
import com.samford.patient.core.db.DocumentVersionEntity
import com.samford.patient.core.db.FeedItemEntity
import com.samford.patient.core.db.LabResultEntity
import com.samford.patient.core.db.PageEntity
import com.samford.patient.core.db.ProfileEntity
import com.samford.patient.core.db.RecipientEntity
import com.samford.patient.core.db.RecordsDao
import com.samford.patient.core.db.VisitEntity
import com.samford.patient.core.network.AllergyDto
import com.samford.patient.core.network.ClinicalDocumentDto
import com.samford.patient.core.network.EventDto
import com.samford.patient.core.network.HintsDto
import com.samford.patient.core.network.PatientRecordDto
import com.samford.patient.core.network.ProfileDto
import com.samford.patient.core.network.SamfordJson
import com.samford.patient.core.time.Ist
import java.time.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object FeedKinds {
    const val DOCUMENT_NEW = "DOCUMENT_NEW"
    const val DOCUMENT_AMENDED = "DOCUMENT_AMENDED"
    const val LAB_RESULT_READY = "LAB_RESULT_READY"
    const val LAB_SAMPLE_COLLECTED = "LAB_SAMPLE_COLLECTED"
    const val MEDICINES_DISPENSED = "MEDICINES_DISPENSED"
    const val MEDICINES_PARTIAL = "MEDICINES_PARTIAL"
    const val MEDICINES_UNAVAILABLE = "MEDICINES_UNAVAILABLE"
    const val TOKEN_CALLED = "TOKEN_CALLED"
    const val VISIT_COMPLETED = "VISIT_COMPLETED"
}

fun ProfileDto.toEntity(order: Int, sample: Boolean = false, allergiesJson: String? = null): ProfileEntity = ProfileEntity(
    id = profileId,
    displayName = name,
    ageYears = age,
    sex = sex,
    uhid = uhid,
    allergiesJson = allergiesJson,
    sortOrder = order,
    isSample = sample,
)

/**
 * Applies a complete PatientRecord snapshot for one profile. Must run inside a transaction.
 * Anything belonging to a different patient is ignored, so a response can never mix profiles.
 */
class RecordApplier(private val dao: RecordsDao) {

    suspend fun applyRecord(profileId: String, record: PatientRecordDto) {
        val existing = dao.profile(profileId)
        val patient = record.patient?.takeIf { it.patientId == profileId }
        val allergies = SamfordJson.encodeToString(ListSerializer(AllergyDto.serializer()), record.allergies)
        dao.upsertProfiles(
            listOf(
                ProfileEntity(
                    id = profileId,
                    displayName = patient?.name ?: existing?.displayName ?: profileId,
                    ageYears = patient?.age ?: existing?.ageYears,
                    sex = patient?.sex ?: existing?.sex,
                    uhid = patient?.uhid ?: existing?.uhid,
                    allergiesJson = allergies,
                    sortOrder = existing?.sortOrder ?: 0,
                    isSample = existing?.isSample ?: false,
                ),
            ),
        )

        val visits = record.visits
        dao.upsertVisits(
            visits.map { v ->
                val completed = Ist.parseOrNull(v.completedAt)
                VisitEntity(
                    id = v.visitId, profileId = profileId, visitDate = v.date,
                    dateMillis = completed ?: istMidnight(v.date), completedAt = completed,
                    doctorName = v.doctorName, department = v.department, status = v.status,
                )
            },
        )
        dao.deleteVisitsNotIn(profileId, visits.map { it.visitId })

        val documents = visits.flatMap { it.documents }.filter { it.patientId == profileId }
        documents.forEach { applyDocument(profileId, it) }
        val keep = documents.map { it.documentId }.toSet()
        for (id in dao.documentIds(profileId)) if (id !in keep) deleteDocument(id)

        val results = record.labResults
        dao.upsertLabResults(
            results.map {
                LabResultEntity(
                    id = it.resultId, profileId = profileId, documentId = it.documentId, labId = it.labId,
                    testName = it.testName, value = it.value, unit = it.unit, refRange = it.refRange,
                    flag = it.flag?.uppercase(), reportedAt = Ist.parseOrNull(it.reportedAt), summary = it.summary,
                    reportPdfSha256 = it.reportPdfSha256?.lowercase(),
                )
            },
        )
        dao.deleteLabResultsNotIn(profileId, results.map { it.resultId })
    }

    /** Upserts one document with every immutable version, its pages and recipients, keeping local "viewed" state. */
    suspend fun applyDocument(profileId: String, doc: ClinicalDocumentDto) {
        if (doc.patientId != profileId) return
        val prior = dao.document(doc.documentId)
        val latest = doc.versions.maxByOrNull { it.version }
        dao.upsertDocument(
            DocumentEntity(
                id = doc.documentId, visitId = doc.visitId, profileId = profileId, kind = doc.type.uppercase(),
                title = doc.title ?: latest?.title, currentVersion = maxOf(doc.latestVersion, latest?.version ?: 1),
                authorName = doc.author?.name ?: latest?.authorName,
                updatedAt = Ist.parseOrNull(latest?.signedAt) ?: Ist.parseOrNull(latest?.createdAt),
                hintsJson = doc.hints?.let { SamfordJson.encodeToString(HintsDto.serializer(), it) },
                viewedVersion = prior?.viewedVersion ?: 0,
            ),
        )
        dao.upsertVersions(
            doc.versions.map { v ->
                DocumentVersionEntity(
                    documentId = doc.documentId, version = v.version, createdAt = Ist.parseOrNull(v.createdAt),
                    signedAt = Ist.parseOrNull(v.signedAt), authorName = v.authorName, title = v.title, reason = v.reason,
                    pdfSha256 = v.pdfSha256?.lowercase(), inkSha256 = v.inkSha256?.lowercase(), inkFormat = v.inkFormat,
                    recordHash = v.recordHash,
                )
            },
        )
        for (v in doc.versions) {
            dao.deletePages(doc.documentId, v.version)
            dao.upsertPages(
                v.pages.map { p ->
                    PageEntity(doc.documentId, v.version, p.index, p.pngSha256.lowercase(), p.widthPx, p.heightPx)
                },
            )
        }
        dao.deleteRecipients(doc.documentId)
        dao.upsertRecipients(
            doc.recipients.map { r ->
                RecipientEntity(
                    documentId = doc.documentId, recipientKey = r.kind + ":" + (r.labId ?: ""), kind = r.kind.uppercase(),
                    labId = r.labId, name = r.name, state = r.state.uppercase(), version = r.version,
                    updatedAt = Ist.parseOrNull(r.updatedAt), note = r.note,
                )
            },
        )
    }

    private suspend fun deleteDocument(id: String) {
        dao.deleteAllPages(id)
        dao.deleteVersions(id)
        dao.deleteRecipients(id)
        dao.deleteDocument(id)
    }

    private fun istMidnight(date: String?): Long? = try {
        date?.let { LocalDate.parse(it).atStartOfDay(Ist.zone).toInstant().toEpochMilli() }
    } catch (_: Exception) {
        null
    }
}

/**
 * Turns server feed events into localised Home items. Events are hints; names come from the record
 * that was applied just before, so items read naturally ("New prescription from Dr …").
 * Unknown event types and states are ignored.
 */
class EventMapper(private val dao: RecordsDao) {

    private class LabGroup(val index: Int, val names: MutableList<String>, val visitId: String?)

    suspend fun map(profileId: String, events: List<EventDto>, notify: Boolean, nowMillis: Long): List<FeedItemEntity> {
        val items = mutableListOf<FeedItemEntity>()
        val labGroups = mutableMapOf<String, LabGroup>()
        for (e in events) {
            if (e.patientId != null && e.patientId != profileId) continue
            val at = Ist.parseOrNull(e.at) ?: nowMillis
            fun add(kind: String, refType: String, refId: String, actor: String?, subject: String?) {
                items += FeedItemEntity(
                    id = "ev:${e.eventId}", profileId = profileId, kind = kind, occurredAt = at,
                    refType = refType, refId = refId, actor = actor, subject = subject,
                    notified = !notify, read = false,
                )
            }
            val doc = e.documentId?.let { dao.document(it) }
            when (e.type) {
                "DOCUMENT_AVAILABLE" -> if (e.documentId != null) {
                    add(FeedKinds.DOCUMENT_NEW, "document", e.documentId, e.str("doctorName") ?: doc?.authorName, e.str("type") ?: doc?.kind)
                }
                "DOCUMENT_AMENDED" -> if (e.documentId != null) {
                    add(FeedKinds.DOCUMENT_AMENDED, "document", e.documentId, doc?.authorName, doc?.kind ?: e.str("type"))
                }
                "DOCUMENT_STATE_CHANGED" -> {
                    val kind = e.str("recipientKind")
                    val state = e.str("state")
                    val recipient = e.documentId?.let { id ->
                        dao.recipients(id).firstOrNull { it.kind == kind && (it.labId ?: "") == (e.str("labId") ?: "") }
                    }
                    val feedKind = when {
                        kind == "PHARMACY" && state == "DISPENSED" -> FeedKinds.MEDICINES_DISPENSED
                        kind == "PHARMACY" && state == "PARTIALLY_DISPENSED" -> FeedKinds.MEDICINES_PARTIAL
                        kind == "PHARMACY" && state == "NOT_DISPENSED" -> FeedKinds.MEDICINES_UNAVAILABLE
                        kind == "LAB" && state == "SAMPLE_COLLECTED" -> FeedKinds.LAB_SAMPLE_COLLECTED
                        else -> null
                    }
                    val visitId = e.visitId ?: doc?.visitId
                    if (feedKind != null && visitId != null) add(feedKind, "visit", visitId, recipient?.name, null)
                }
                "LAB_RESULT_READY" -> {
                    val resultId = e.str("resultId")
                    val result = resultId?.let { dao.labResult(it) }
                    val labName = e.documentId?.let { id -> dao.recipients(id).firstOrNull { it.labId == e.str("labId") }?.name }
                    val groupKey = e.documentId ?: e.visitId
                    val existing = groupKey?.let { labGroups[it] }
                    when {
                        // Several results from one investigations order become one Home item.
                        existing != null && result != null -> {
                            existing.names += result.testName
                            items[existing.index] = items[existing.index].copy(
                                refType = if (existing.visitId != null) "visit" else "labResult",
                                refId = existing.visitId ?: items[existing.index].refId,
                            )
                        }
                        result != null -> {
                            add(FeedKinds.LAB_RESULT_READY, "labResult", result.id, labName, result.testName)
                            if (groupKey != null) labGroups[groupKey] = LabGroup(items.lastIndex, mutableListOf(result.testName), e.visitId ?: doc?.visitId)
                        }
                        e.visitId != null -> add(FeedKinds.LAB_RESULT_READY, "visit", e.visitId, labName, null)
                    }
                }
                "VISIT_UPDATED" -> {
                    val visitId = e.visitId ?: continue
                    when (e.str("status")) {
                        "CALLED" -> add(FeedKinds.TOKEN_CALLED, "visit", visitId, dao.visit(visitId)?.doctorName, e.str("token"))
                        "COMPLETED" -> add(FeedKinds.VISIT_COMPLETED, "visit", visitId, dao.visit(visitId)?.doctorName, null)
                    }
                }
            }
        }
        for (group in labGroups.values) {
            if (group.names.size > 1) {
                val shown = group.names.take(MAX_NAMED_RESULTS).joinToString(", ")
                val more = group.names.size - MAX_NAMED_RESULTS
                items[group.index] = items[group.index].copy(subject = if (more > 0) "$shown +$more" else shown)
            }
        }
        return items
    }

    private companion object {
        const val MAX_NAMED_RESULTS = 3
    }

    private fun EventDto.str(key: String): String? = (data[key] as? JsonPrimitive)?.contentOrNull
}
