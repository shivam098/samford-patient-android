package com.samford.patient.core.network

import java.io.InputStream
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl

/** Result of one API call. [NotModified] is a successful ETag revalidation (HTTP 304). */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T, val etag: String? = null) : ApiResult<T>
    data object NotModified : ApiResult<Nothing>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

/**
 * A failed call, normalised from the server error envelope `{error:{code,message,retryable,details,requestId}}`,
 * the HTTP status and Retry-After. [httpStatus] is null when the server was never reached.
 */
data class ApiError(
    val code: String,
    val serverMessage: String? = null,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
    val retryAfter: Duration? = null,
    val details: JsonObject? = null,
    val requestId: String? = null,
) {
    val isNetwork: Boolean get() = code == CODE_NETWORK
    val isUnauthorized: Boolean get() = httpStatus == 401 || code == CODE_SIGNED_OUT
    /** The hospital ended this device's access: cached records must leave the phone. */
    val isRevoked: Boolean get() = code == "ACCESS_REVOKED" || code == "DEVICE_SIGNED_OUT"
    val isNotFound: Boolean get() = httpStatus == 404
    val isUpdateRequired: Boolean get() = httpStatus == 426 || code == "APP_UPDATE_REQUIRED"
    val attemptsRemaining: Int? get() = (details?.get("attemptsRemaining") as? JsonPrimitive)?.intOrNull

    companion object {
        const val CODE_NETWORK = "NETWORK_UNAVAILABLE"
        const val CODE_NOT_CONFIGURED = "SERVER_NOT_CONFIGURED"
        const val CODE_SIGNED_OUT = "SIGNED_OUT"
        const val CODE_BAD_RESPONSE = "BAD_RESPONSE"
        const val CODE_INTEGRITY = "INTEGRITY_MISMATCH"
        const val CODE_INVALID_MOBILE = "INVALID_MOBILE"
        const val CODE_INVALID_CODE = "INVALID_CODE_FORMAT"

        fun network() = ApiError(CODE_NETWORK, retryable = true)
        fun notConfigured() = ApiError(CODE_NOT_CONFIGURED, retryable = true)
        fun signedOut() = ApiError(CODE_SIGNED_OUT, httpStatus = 401)
        fun badResponse(status: Int? = null) = ApiError(CODE_BAD_RESPONSE, retryable = true, httpStatus = status)
    }
}

fun interface ApiRootProvider {
    /** The API base ending in `/api/mobile/v1/`, or null when no server is known yet. */
    suspend fun root(): HttpUrl?
}

fun interface TokenProvider {
    suspend fun token(): String?
}

fun interface DeviceIdProvider {
    /** Random per installation; sent at sign-in and as X-Device-Id on every authenticated call. */
    suspend fun deviceId(): String
}

/** The patient side of the Samford mobile API v1. Implemented over HTTP and by in-memory fakes in tests. */
interface MobileApi {
    suspend fun meta(root: HttpUrl): ApiResult<MetaDto>
    suspend fun requestOtp(mobile: String, deviceId: String, idempotencyKey: String): ApiResult<OtpChallengeDto>
    suspend fun verifyOtp(body: OtpVerifyBody, idempotencyKey: String): ApiResult<PatientSessionDto>
    suspend fun logout(): ApiResult<Unit>
    suspend fun profiles(etag: String?): ApiResult<ProfileListDto>
    suspend fun record(profileId: String, etag: String?): ApiResult<PatientRecordDto>
    suspend fun feed(profileId: String, cursor: String?, etag: String?, limit: Int = PAGE_SIZE): ApiResult<EventPageDto>
    suspend fun document(documentId: String, etag: String?): ApiResult<ClinicalDocumentDto>
    suspend fun notifications(cursor: String?, etag: String?, limit: Int = PAGE_SIZE): ApiResult<NotificationPageDto>
    suspend fun markViewed(documentId: String, version: Int, idempotencyKey: String): ApiResult<ViewedResponseDto>

    /** Streams an immutable content-addressed blob; [read] runs on an IO thread with the body stream and length. */
    suspend fun <T> blob(sha256: String, read: (InputStream, Long) -> T): ApiResult<T>

    companion object {
        const val PAGE_SIZE = 100
    }
}
