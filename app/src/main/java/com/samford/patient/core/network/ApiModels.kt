package com.samford.patient.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Wire format of the Samford mobile API v1 (docs/mobile-api-v1.yaml in shivam098/samford-web-app,
 * owned by the backend). Fields the app does not strictly need have defaults, unknown fields are
 * ignored and enumerations are kept as strings, so additive v1 changes never break this app.
 */

@Serializable
data class DiscoveryDto(
    val schema: String? = null,
    /** online, offline or maintenance. */
    val status: String? = null,
    /** The complete base including /api/mobile/v1; null when offline. */
    val apiBaseUrl: String? = null,
    val webUrl: String? = null,
    val updatedAt: String? = null,
    val minPatientAppVersion: JsonElement? = null,
    val synthetic: Boolean? = null,
    val message: String? = null,
)

@Serializable
data class MetaDto(
    val apiVersion: String? = null,
    val serverVersion: String? = null,
    val synthetic: Boolean? = null,
    val minPatientAppVersion: Int? = null,
    val serverTime: String? = null,
    val docsUrl: String? = null,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody = ErrorBody())

@Serializable
data class ErrorBody(
    val code: String = "UNKNOWN",
    val message: String? = null,
    val retryable: Boolean = false,
    val details: JsonObject? = null,
    val requestId: String? = null,
)

@Serializable
data class OtpRequestBody(val mobile: String, val deviceId: String)

@Serializable
data class OtpChallengeDto(
    val challengeId: String,
    val expiresAt: String? = null,
    val resendAfterSeconds: Int = 30,
    /** SYNTHETIC DEMO ONLY: there is no SMS provider, so the server returns the code. */
    val demoCode: String? = null,
    val demoNotice: String? = null,
)

@Serializable
data class OtpVerifyBody(
    val challengeId: String,
    val code: String,
    val deviceId: String,
    val deviceName: String? = null,
)

@Serializable
data class PatientAccountDto(val accountId: String? = null, val mobileMasked: String? = null)

@Serializable
data class PatientSessionDto(
    val token: String,
    val tokenType: String? = null,
    val expiresAt: String? = null,
    val deviceId: String? = null,
    val account: PatientAccountDto? = null,
    val profiles: List<ProfileDto> = emptyList(),
)

@Serializable
data class ProfileDto(
    val profileId: String,
    val uhid: String? = null,
    val name: String,
    val age: Int? = null,
    /** Female, Male or Other. */
    val sex: String? = null,
)

@Serializable
data class ProfileListDto(val profiles: List<ProfileDto> = emptyList())

@Serializable
data class PatientSummaryDto(
    val patientId: String,
    val uhid: String? = null,
    val name: String? = null,
    val age: Int? = null,
    val sex: String? = null,
    val mobileMasked: String? = null,
)

@Serializable
data class AllergyDto(val substance: String, val reaction: String? = null, val certainty: String? = null)

@Serializable
data class PatientRecordDto(
    val patient: PatientSummaryDto? = null,
    val allergies: List<AllergyDto> = emptyList(),
    /** Newest first. */
    val visits: List<RecordVisitDto> = emptyList(),
    val labResults: List<LabResultDto> = emptyList(),
    val generatedAt: String? = null,
)

@Serializable
data class RecordVisitDto(
    val visitId: String,
    /** Hospital calendar date in IST, YYYY-MM-DD. */
    val date: String? = null,
    val doctorId: String? = null,
    val doctorName: String? = null,
    val department: String? = null,
    /** BOOKED, WAITING, CALLED, IN_CONSULTATION, ON_HOLD, COMPLETED, MISSED or CANCELLED. */
    val status: String? = null,
    val completedAt: String? = null,
    val documents: List<ClinicalDocumentDto> = emptyList(),
)

@Serializable
data class AuthorDto(val doctorId: String? = null, val name: String? = null)

@Serializable
data class RoutingDto(val pharmacy: Boolean = false, val labs: List<String> = emptyList(), val patient: Boolean = true)

@Serializable
data class MedicineHintDto(
    val name: String,
    val strength: String? = null,
    val dose: String? = null,
    val frequency: String? = null,
    val durationDays: Int? = null,
    val notes: String? = null,
)

@Serializable
data class TestHintDto(val name: String, val code: String? = null)

/** Optional typed hints. The signed handwriting stays authoritative; hints are unverified. */
@Serializable
data class HintsDto(
    val medicines: List<MedicineHintDto> = emptyList(),
    val tests: List<TestHintDto> = emptyList(),
    val diagnosis: String? = null,
    val notes: String? = null,
)

@Serializable
data class ClinicalDocumentDto(
    /** Stable across versions. */
    val documentId: String,
    val visitId: String,
    val patientId: String,
    /** VISIT_SUMMARY, PRESCRIPTION, INVESTIGATIONS, ADVICE_REFERRAL or OTHER. */
    val type: String = "OTHER",
    val title: String? = null,
    val author: AuthorDto? = null,
    val latestVersion: Int = 1,
    val routing: RoutingDto? = null,
    val hints: HintsDto? = null,
    /** Oldest first; every version is immutable. */
    val versions: List<DocumentVersionDto> = emptyList(),
    /** Recipients of the latest version. */
    val recipients: List<RecipientDto> = emptyList(),
)

@Serializable
data class DocumentVersionDto(
    val version: Int,
    val createdAt: String? = null,
    val signedAt: String? = null,
    /** Addendum reason; null for version 1. */
    val reason: String? = null,
    val authorName: String? = null,
    val title: String? = null,
    val pages: List<PageRefDto> = emptyList(),
    val pdfSha256: String? = null,
    val pdfUrl: String? = null,
    val inkSha256: String? = null,
    val inkFormat: String? = null,
    val inkUrl: String? = null,
    val recordHash: String? = null,
    val urlsExpireAt: String? = null,
)

@Serializable
data class PageRefDto(
    val index: Int,
    val pngSha256: String,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    /** Short-lived signed URL. The app reads GET /blobs/{sha256} instead so cache keys never expire. */
    val url: String? = null,
)

@Serializable
data class RecipientDto(
    /** PHARMACY, LAB or PATIENT. */
    val kind: String,
    val labId: String? = null,
    val name: String? = null,
    /** AVAILABLE, VIEWED, DISPENSED, PARTIALLY_DISPENSED, NOT_DISPENSED, SAMPLE_COLLECTED, RESULT_READY or SUPERSEDED. */
    val state: String,
    val version: Int = 1,
    val updatedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class LabResultDto(
    val resultId: String,
    val documentId: String? = null,
    val labId: String? = null,
    val testName: String,
    val value: String = "",
    val unit: String? = null,
    val refRange: String? = null,
    /** NORMAL, ABNORMAL or CRITICAL. */
    val flag: String? = null,
    val reportedAt: String? = null,
    val summary: String? = null,
    val reportPdfSha256: String? = null,
    val reportUrl: String? = null,
)

@Serializable
data class EventDto(
    /** Numeric string, monotonic per server. */
    val eventId: String,
    val type: String,
    val at: String? = null,
    val message: String? = null,
    val visitId: String? = null,
    val documentId: String? = null,
    val patientId: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class EventPageDto(
    val events: List<EventDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val serverTime: String? = null,
)

@Serializable
data class NotificationDto(
    val notificationId: String,
    val profileId: String,
    val type: String? = null,
    val title: String? = null,
    val body: String? = null,
    val at: String? = null,
    val documentId: String? = null,
    val visitId: String? = null,
)

@Serializable
data class NotificationPageDto(
    val notifications: List<NotificationDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val serverTime: String? = null,
)

@Serializable
data class ViewedBody(val version: Int)

@Serializable
data class ViewedResponseDto(
    val documentId: String? = null,
    val version: Int? = null,
    val state: String? = null,
    val viewedAt: String? = null,
)
