package com.samford.patient.core.network

import com.samford.patient.core.time.Clock
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

val SamfordJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
}

/**
 * OkHttp implementation of [MobileApi]: bearer token plus X-Device-Id (no cookies), an
 * Idempotency-Key on every POST, If-None-Match on revalidated GETs, app identification headers and
 * Retry-After honoured on every error.
 */
class HttpMobileApi(
    private val client: OkHttpClient,
    private val roots: ApiRootProvider,
    private val tokens: TokenProvider,
    private val devices: DeviceIdProvider,
    private val userAgent: String,
    private val appVersionCode: Int,
    private val language: () -> String,
    private val clock: Clock = Clock.System,
    private val json: Json = SamfordJson,
) : MobileApi {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun meta(root: HttpUrl): ApiResult<MetaDto> =
        call("meta", authenticated = false, rootOverride = root, parse = decoder(MetaDto.serializer()))

    override suspend fun requestOtp(mobile: String, deviceId: String, idempotencyKey: String): ApiResult<OtpChallengeDto> =
        call(
            "patient/otp/request",
            method = "POST",
            body = encode(OtpRequestBody.serializer(), OtpRequestBody(mobile, deviceId)),
            idempotencyKey = idempotencyKey,
            authenticated = false,
            parse = decoder(OtpChallengeDto.serializer()),
        )

    override suspend fun verifyOtp(body: OtpVerifyBody, idempotencyKey: String): ApiResult<PatientSessionDto> =
        call(
            "patient/otp/verify",
            method = "POST",
            body = encode(OtpVerifyBody.serializer(), body),
            idempotencyKey = idempotencyKey,
            authenticated = false,
            parse = decoder(PatientSessionDto.serializer()),
        )

    override suspend fun logout(): ApiResult<Unit> = call("session", method = "DELETE", parse = { })

    override suspend fun profiles(etag: String?): ApiResult<ProfileListDto> =
        call("patient/profiles", etag = etag, parse = decoder(ProfileListDto.serializer()))

    override suspend fun record(profileId: String, etag: String?): ApiResult<PatientRecordDto> =
        call("patient/profiles/${seg(profileId)}/record", etag = etag, parse = decoder(PatientRecordDto.serializer()))

    override suspend fun feed(profileId: String, cursor: String?, etag: String?, limit: Int): ApiResult<EventPageDto> =
        call(
            "patient/profiles/${seg(profileId)}/feed",
            query = mapOf("cursor" to cursor, "limit" to limit.toString()),
            etag = etag,
            parse = decoder(EventPageDto.serializer()),
        )

    override suspend fun document(documentId: String, etag: String?): ApiResult<ClinicalDocumentDto> =
        call("patient/documents/${seg(documentId)}", etag = etag, parse = decoder(ClinicalDocumentDto.serializer()))

    override suspend fun notifications(cursor: String?, etag: String?, limit: Int): ApiResult<NotificationPageDto> =
        call(
            "patient/notifications",
            query = mapOf("cursor" to cursor, "limit" to limit.toString()),
            etag = etag,
            parse = decoder(NotificationPageDto.serializer()),
        )

    override suspend fun markViewed(documentId: String, version: Int, idempotencyKey: String): ApiResult<ViewedResponseDto> =
        call(
            "patient/documents/${seg(documentId)}/viewed",
            method = "POST",
            body = encode(ViewedBody.serializer(), ViewedBody(version)),
            idempotencyKey = idempotencyKey,
            parse = decoder(ViewedResponseDto.serializer()),
        )

    override suspend fun <T> blob(sha256: String, read: (InputStream, Long) -> T): ApiResult<T> {
        if (!SHA256_HEX.matches(sha256)) return ApiResult.Err(ApiError.badResponse())
        return call("blobs/$sha256", accept = "*/*") { body ->
            body.byteStream().use { read(it, body.contentLength()) }
        }
    }

    private fun <T> decoder(serializer: KSerializer<T>): (ResponseBody) -> T =
        { body -> json.decodeFromString(serializer, body.string()) }

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(jsonType)

    private suspend fun <T> call(
        path: String,
        method: String = "GET",
        body: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        etag: String? = null,
        idempotencyKey: String? = null,
        authenticated: Boolean = true,
        rootOverride: HttpUrl? = null,
        accept: String = "application/json",
        parse: (ResponseBody) -> T,
    ): ApiResult<T> {
        val root = rootOverride ?: roots.root() ?: return ApiResult.Err(ApiError.notConfigured())
        val url = root.resolve(path)?.newBuilder()?.apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }?.build() ?: return ApiResult.Err(ApiError.notConfigured())

        val builder = Request.Builder()
            .url(url)
            .method(method, body)
            .header("Accept", accept)
            .header("User-Agent", userAgent)
            .header("Accept-Language", language())
            .header("X-Samford-App", APP_ID)
            .header("X-Samford-App-Version", appVersionCode.toString())
        if (authenticated) {
            val token = tokens.token() ?: return ApiResult.Err(ApiError.signedOut())
            builder.header("Authorization", "Bearer $token")
            builder.header("X-Device-Id", devices.deviceId())
        }
        etag?.let { builder.header("If-None-Match", it) }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        val request = builder.build()

        return try {
            runInterruptible(Dispatchers.IO) {
                client.newCall(request).execute().use { response -> toResult(response, parse) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SerializationException) {
            ApiResult.Err(ApiError.badResponse())
        } catch (_: IllegalArgumentException) {
            ApiResult.Err(ApiError.badResponse())
        } catch (_: IOException) {
            ApiResult.Err(ApiError.network())
        }
    }

    private fun <T> toResult(response: Response, parse: (ResponseBody) -> T): ApiResult<T> = when {
        response.code == 304 -> ApiResult.NotModified
        response.isSuccessful -> ApiResult.Ok(parse(response.body), response.header("ETag"))
        else -> ApiResult.Err(parseError(response))
    }

    private fun parseError(response: Response): ApiError {
        val text = try {
            response.peekBody(MAX_ERROR_BYTES).string()
        } catch (_: IOException) {
            ""
        }
        val envelope = try {
            if (text.isBlank()) null else json.decodeFromString(ErrorEnvelope.serializer(), text)
        } catch (_: Exception) {
            null
        }
        val status = response.code
        val retryAfter = RetryAfter.parse(response.header("Retry-After"), clock)
        val code = envelope?.error?.code ?: when (status) {
            401 -> "AUTH_REQUIRED"
            403 -> "FORBIDDEN"
            404 -> "NOT_FOUND"
            426 -> "APP_UPDATE_REQUIRED"
            429 -> "RATE_LIMITED"
            in 500..599 -> "UNAVAILABLE"
            else -> "HTTP_$status"
        }
        val retryable = (envelope?.error?.retryable ?: false) || status == 408 || status == 429 || status >= 500
        return ApiError(
            code = code,
            serverMessage = envelope?.error?.message,
            retryable = retryable,
            httpStatus = status,
            retryAfter = retryAfter,
            details = envelope?.error?.details,
            requestId = envelope?.error?.requestId ?: response.header("X-Request-Id"),
        )
    }

    private fun seg(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val APP_ID = "patient-android"
        private const val MAX_ERROR_BYTES = 64L * 1024
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

object RetryAfter {
    /** Retry-After is either delta-seconds or an HTTP-date. Negative or unparseable values are ignored. */
    fun parse(header: String?, clock: Clock): Duration? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return if (it >= 0) it.seconds else null }
        return try {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val ms = at.toEpochMilli() - clock.now().toEpochMilli()
            if (ms > 0) ms.milliseconds else Duration.ZERO
        } catch (_: Exception) {
            null
        }
    }
}

/** Normalises a user- or discovery-supplied server address to the API base `…/api/mobile/v1/`. */
object ApiRoot {
    private const val SUFFIX = "api/mobile/v1/"

    fun normalize(input: String): HttpUrl? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        val path = url.encodedPath.trimEnd('/')
        val base = when {
            path.endsWith("/api/mobile/v1") -> url.newBuilder().encodedPath("$path/").build()
            else -> url.newBuilder().encodedPath((if (path.isEmpty()) "/" else "$path/") + SUFFIX).build()
        }
        return base.newBuilder().query(null).fragment(null).build()
    }

    /** The origin a person would recognise, e.g. `https://example.trycloudflare.com`. */
    fun displayOrigin(root: HttpUrl): String {
        val path = root.encodedPath.removeSuffix(SUFFIX).trimEnd('/')
        val portPart = if (root.port == HttpUrl.defaultPort(root.scheme)) "" else ":${root.port}"
        return "${root.scheme}://${root.host}$portPart$path"
    }
}
