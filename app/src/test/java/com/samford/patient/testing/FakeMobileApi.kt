package com.samford.patient.testing

import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.AuthorDto
import com.samford.patient.core.network.ClinicalDocumentDto
import com.samford.patient.core.network.DocumentVersionDto
import com.samford.patient.core.network.EventDto
import com.samford.patient.core.network.EventPageDto
import com.samford.patient.core.network.LabResultDto
import com.samford.patient.core.network.MetaDto
import com.samford.patient.core.network.MobileApi
import com.samford.patient.core.network.NotificationDto
import com.samford.patient.core.network.NotificationPageDto
import com.samford.patient.core.network.OtpChallengeDto
import com.samford.patient.core.network.OtpVerifyBody
import com.samford.patient.core.network.PageRefDto
import com.samford.patient.core.network.PatientAccountDto
import com.samford.patient.core.network.PatientRecordDto
import com.samford.patient.core.network.PatientSessionDto
import com.samford.patient.core.network.PatientSummaryDto
import com.samford.patient.core.network.ProfileDto
import com.samford.patient.core.network.ProfileListDto
import com.samford.patient.core.network.RecipientDto
import com.samford.patient.core.network.RecordVisitDto
import com.samford.patient.core.network.ViewedResponseDto
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl

/**
 * In-memory Samford backend that follows the v1 contract semantics the client relies on:
 * cursor feeds (oldest first), ETag/304 on every GET, notifications across profiles, immutable
 * content-addressed blobs, and injectable failures. Every call is recorded for assertions.
 */
class FakeMobileApi : MobileApi {
    data class Call(val op: String, val arg: String? = null, val etag: String? = null, val cursor: String? = null)

    val calls = mutableListOf<Call>()
    val profiles = mutableListOf(
        ProfileDto("patient-a", "SMF-0001", "Demo patient A", 34, "Female"),
        ProfileDto("patient-child", "SMF-0002", "Demo child A", 6, "Male"),
    )
    val records = mutableMapOf<String, PatientRecordDto>()
    private val events = mutableMapOf<String, MutableList<EventDto>>()
    private val notifications = mutableListOf<NotificationDto>()
    val blobs = mutableMapOf<String, ByteArray>()
    private var eventSeq = 100
    private val recordRevision = mutableMapOf<String, Int>()

    /** Next failures to return, by operation name ("feed", "record", "notifications", "blob", "requestOtp", "verifyOtp"). */
    private val failures = mutableMapOf<String, ArrayDeque<ApiError>>()
    var demoCode = "123456"
    val registeredMobiles = mutableSetOf("9000000001")
    var corruptBlobs = false

    fun fail(op: String, error: ApiError) {
        failures.getOrPut(op) { ArrayDeque() }.addLast(error)
    }

    private fun takeFailure(op: String): ApiError? = failures[op]?.removeFirstOrNull()

    fun count(op: String) = calls.count { it.op == op }

    // ---- Server-side helpers used by tests ----

    fun putBlob(bytes: ByteArray): String = BlobStore.sha256Hex(bytes).also { blobs[it] = bytes }

    fun setRecord(profileId: String, record: PatientRecordDto) {
        records[profileId] = record
        recordRevision[profileId] = (recordRevision[profileId] ?: 0) + 1
    }

    fun addEvent(profileId: String, type: String, documentId: String? = null, visitId: String? = null, notify: Boolean = true, vararg data: Pair<String, String>) {
        val id = (eventSeq++).toString()
        events.getOrPut(profileId) { mutableListOf() } += EventDto(
            eventId = id, type = type, at = "2026-11-21T04:00:00Z", visitId = visitId, documentId = documentId, patientId = profileId,
            data = buildJsonObject { data.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
        )
        if (notify) notifications += NotificationDto(id, profileId, type, "Samford", "You have an update.", "2026-11-21T04:00:00Z", documentId, visitId)
    }

    // ---- MobileApi ----

    override suspend fun meta(root: HttpUrl): ApiResult<MetaDto> {
        calls += Call("meta", root.toString())
        return ApiResult.Ok(MetaDto(apiVersion = "1.0.0", serverVersion = "fake", synthetic = true, minPatientAppVersion = 1))
    }

    override suspend fun requestOtp(mobile: String, deviceId: String, idempotencyKey: String): ApiResult<OtpChallengeDto> {
        calls += Call("requestOtp", mobile)
        takeFailure("requestOtp")?.let { return ApiResult.Err(it) }
        if (mobile !in registeredMobiles) return ApiResult.Err(ApiError("MOBILE_NOT_REGISTERED", httpStatus = 404))
        return ApiResult.Ok(OtpChallengeDto("challenge-1", "2026-11-21T04:05:00Z", 30, demoCode, "SYNTHETIC DEMO ONLY"))
    }

    override suspend fun verifyOtp(body: OtpVerifyBody, idempotencyKey: String): ApiResult<PatientSessionDto> {
        calls += Call("verifyOtp", body.code)
        takeFailure("verifyOtp")?.let { return ApiResult.Err(it) }
        if (body.code != demoCode) {
            return ApiResult.Err(ApiError("OTP_INVALID", httpStatus = 401, details = buildJsonObject { put("attemptsRemaining", JsonPrimitive(2)) }))
        }
        return ApiResult.Ok(
            PatientSessionDto("token-abc", "Bearer", "2027-02-19T00:00:00Z", body.deviceId, PatientAccountDto("acct-1", "******0001"), profiles.toList()),
        )
    }

    override suspend fun logout(): ApiResult<Unit> {
        calls += Call("logout")
        return ApiResult.Ok(Unit)
    }

    override suspend fun profiles(etag: String?): ApiResult<ProfileListDto> {
        calls += Call("profiles", etag = etag)
        val tag = "\"profiles-${profiles.joinToString { it.profileId }.hashCode()}\""
        return if (etag == tag) ApiResult.NotModified else ApiResult.Ok(ProfileListDto(profiles.toList()), tag)
    }

    override suspend fun record(profileId: String, etag: String?): ApiResult<PatientRecordDto> {
        calls += Call("record", profileId, etag)
        takeFailure("record")?.let { return ApiResult.Err(it) }
        val name = profiles.firstOrNull { it.profileId == profileId }?.name
        val record = records[profileId] ?: PatientRecordDto(PatientSummaryDto(profileId, null, name))
        val tag = "\"record-$profileId-${recordRevision[profileId] ?: 0}\""
        return if (etag == tag) ApiResult.NotModified else ApiResult.Ok(record, tag)
    }

    override suspend fun feed(profileId: String, cursor: String?, etag: String?, limit: Int): ApiResult<EventPageDto> {
        calls += Call("feed", profileId, etag, cursor)
        takeFailure("feed")?.let { return ApiResult.Err(it) }
        val all = events[profileId].orEmpty()
        val after = cursor?.toIntOrNull() ?: 0
        val pending = all.filter { it.eventId.toInt() > after }
        val latest = all.lastOrNull()?.eventId ?: "0"
        val tag = "\"feed-$profileId-$latest\""
        if (etag != null && etag == tag && pending.isEmpty()) return ApiResult.NotModified
        val page = pending.take(limit)
        val hasMore = pending.size > limit
        val next = page.lastOrNull()?.eventId ?: cursor ?: "0"
        return ApiResult.Ok(EventPageDto(page, next, hasMore, "2026-11-21T04:00:00Z"), if (hasMore) null else tag)
    }

    override suspend fun document(documentId: String, etag: String?): ApiResult<ClinicalDocumentDto> {
        calls += Call("document", documentId, etag)
        val doc = records.values.flatMap { it.visits }.flatMap { it.documents }.firstOrNull { it.documentId == documentId }
            ?: return ApiResult.Err(ApiError("NOT_FOUND", httpStatus = 404))
        return ApiResult.Ok(doc, "\"doc-$documentId-${doc.latestVersion}\"")
    }

    override suspend fun notifications(cursor: String?, etag: String?, limit: Int): ApiResult<NotificationPageDto> {
        calls += Call("notifications", etag = etag, cursor = cursor)
        takeFailure("notifications")?.let { return ApiResult.Err(it) }
        val after = cursor?.toIntOrNull() ?: 0
        val pending = notifications.filter { it.notificationId.toInt() > after }
        val latest = notifications.lastOrNull()?.notificationId ?: "0"
        val tag = "\"notifications-$latest\""
        if (etag != null && etag == tag && pending.isEmpty()) return ApiResult.NotModified
        val page = pending.take(limit)
        val hasMore = pending.size > limit
        return ApiResult.Ok(NotificationPageDto(page, page.lastOrNull()?.notificationId ?: cursor ?: "0", hasMore), if (hasMore) null else tag)
    }

    override suspend fun markViewed(documentId: String, version: Int, idempotencyKey: String): ApiResult<ViewedResponseDto> {
        calls += Call("markViewed", "$documentId@$version:$idempotencyKey")
        return ApiResult.Ok(ViewedResponseDto(documentId, version, "VIEWED", "2026-11-21T04:00:00Z"))
    }

    override suspend fun <T> blob(sha256: String, read: (InputStream, Long) -> T): ApiResult<T> {
        calls += Call("blob", sha256)
        takeFailure("blob")?.let { return ApiResult.Err(it) }
        val bytes = blobs[sha256] ?: return ApiResult.Err(ApiError("NOT_FOUND", httpStatus = 404))
        val served = if (corruptBlobs) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes
        return ApiResult.Ok(read(ByteArrayInputStream(served), served.size.toLong()))
    }
}

/** Builds contract-shaped documents and records for tests. */
object Fixtures {
    fun page(api: FakeMobileApi, label: String, index: Int = 0): PageRefDto {
        val sha = api.putBlob("PNG-$label-$index".toByteArray())
        return PageRefDto(index, sha, 1240, 1754, "https://signed.example/$sha")
    }

    fun version(api: FakeMobileApi, docId: String, version: Int, reason: String? = null, pages: Int = 1) = DocumentVersionDto(
        version = version,
        createdAt = "2026-11-21T0$version:00:00Z",
        signedAt = "2026-11-21T0$version:00:00Z",
        reason = reason,
        authorName = "Dr. Demo A",
        title = "Prescription",
        pages = (0 until pages).map { page(api, "$docId-v$version", it) },
        pdfSha256 = api.putBlob("PDF-$docId-$version".toByteArray()),
        recordHash = BlobStore.sha256Hex("$docId-$version".toByteArray()),
    )

    fun document(
        api: FakeMobileApi,
        id: String,
        visitId: String,
        patientId: String,
        type: String = "PRESCRIPTION",
        versions: Int = 1,
        recipients: List<RecipientDto> = listOf(
            RecipientDto("PHARMACY", null, "Samford OPD Pharmacy", "AVAILABLE", versions, "2026-11-21T04:00:00Z", null),
        ),
    ) = ClinicalDocumentDto(
        documentId = id, visitId = visitId, patientId = patientId, type = type, title = type.lowercase(),
        author = AuthorDto("doctor-a", "Dr. Demo A"), latestVersion = versions,
        versions = (1..versions).map { version(api, id, it, if (it > 1) "Dose corrected" else null) },
        recipients = recipients,
    )

    fun record(patientId: String, vararg documents: ClinicalDocumentDto, results: List<LabResultDto> = emptyList()) = PatientRecordDto(
        patient = PatientSummaryDto(patientId, "SMF-0001", "Demo patient A", 34, "Female", "******0001"),
        visits = documents.groupBy { it.visitId }.map { (visitId, docs) ->
            RecordVisitDto(visitId, "2026-11-21", "doctor-a", "Dr. Demo A", "General Medicine", "COMPLETED", "2026-11-21T04:30:00Z", docs)
        },
        labResults = results,
    )
}
