package com.samford.patient.network

import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.ApiRoot
import com.samford.patient.core.network.HttpMobileApi
import com.samford.patient.core.network.OtpVerifyBody
import com.samford.patient.core.network.RetryAfter
import com.samford.patient.testing.MutableClock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpMobileApiTest {
    private val server = MockWebServer()
    private var token: String? = "tok-1"
    private lateinit var api: HttpMobileApi

    @Before
    fun setUp() {
        server.start()
        val root = server.url("/api/mobile/v1/")
        api = HttpMobileApi(
            client = OkHttpClient(),
            roots = { root },
            tokens = { token },
            devices = { "device-1234" },
            userAgent = "test",
            appVersionCode = 1,
            language = { "hi" },
            clock = MutableClock(),
        )
    }

    @After
    fun tearDown() = server.close()

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }.build()

    @Test
    fun authenticatedGetSendsBearerDeviceAppHeadersAndIfNoneMatch() = runTest {
        server.enqueue(json(200, """{"events":[],"nextCursor":"5","hasMore":false,"serverTime":"2026-11-21T00:00:00Z","futureField":1}""", "ETag" to "\"e1\""))

        val result = api.feed("patient-a", cursor = "4", etag = "\"e0\"")

        val ok = result as ApiResult.Ok
        assertThat(ok.etag).isEqualTo("\"e1\"")
        assertThat(ok.value.nextCursor).isEqualTo("5")
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/mobile/v1/patient/profiles/patient-a/feed")
        assertThat(request.url.queryParameter("cursor")).isEqualTo("4")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer tok-1")
        assertThat(request.headers["X-Device-Id"]).isEqualTo("device-1234")
        assertThat(request.headers["If-None-Match"]).isEqualTo("\"e0\"")
        assertThat(request.headers["X-Samford-App"]).isEqualTo("patient-android")
        assertThat(request.headers["X-Samford-App-Version"]).isEqualTo("1")
        assertThat(request.headers["Cookie"]).isNull()
    }

    @Test
    fun notModifiedIsASuccessfulRevalidation() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())
        assertThat(api.record("patient-a", "\"r1\"")).isEqualTo(ApiResult.NotModified)
    }

    @Test
    fun postsCarryIdempotencyKeyAndOnlyContractFields() = runTest {
        server.enqueue(json(201, """{"challengeId":"c1","expiresAt":"2026-11-21T00:05:00Z","resendAfterSeconds":30,"demoCode":"123456","demoNotice":"SYNTHETIC"}"""))
        server.enqueue(json(201, """{"token":"t","tokenType":"Bearer","expiresAt":"2027-01-01T00:00:00Z","deviceId":"device-1234","account":{"accountId":"a","mobileMasked":"******0001"},"profiles":[{"profileId":"p","uhid":"U","name":"N","age":3,"sex":"Female"}]}"""))

        val challenge = api.requestOtp("9000000001", "device-1234", "key-request-1") as ApiResult.Ok
        val session = api.verifyOtp(OtpVerifyBody("c1", "123456", "device-1234", null), "key-verify-1") as ApiResult.Ok

        assertThat(challenge.value.demoCode).isEqualTo("123456")
        assertThat(session.value.profiles.single().profileId).isEqualTo("p")
        val first = server.takeRequest()
        assertThat(first.method).isEqualTo("POST")
        assertThat(first.headers["Idempotency-Key"]).isEqualTo("key-request-1")
        assertThat(first.headers["Authorization"]).isNull()
        assertThat(first.body?.utf8()).isEqualTo("""{"mobile":"9000000001","deviceId":"device-1234"}""")
        val second = server.takeRequest()
        assertThat(second.headers["Idempotency-Key"]).isEqualTo("key-verify-1")
        assertThat(second.body?.utf8()).isEqualTo("""{"challengeId":"c1","code":"123456","deviceId":"device-1234"}""")
    }

    @Test
    fun errorEnvelopeRetryAfterAndDetailsAreParsed() = runTest {
        server.enqueue(json(429, """{"error":{"code":"OTP_RATE_LIMITED","message":"Wait.","retryable":true,"requestId":"r-9"}}""", "Retry-After" to "90"))
        server.enqueue(json(401, """{"error":{"code":"OTP_INVALID","message":"Wrong.","retryable":false,"details":{"attemptsRemaining":1}}}"""))
        server.enqueue(json(401, """{"error":{"code":"DEVICE_SIGNED_OUT","message":"Signed out.","retryable":false}}"""))
        server.enqueue(MockResponse.Builder().code(503).body("<html>proxy</html>").build())

        val limited = (api.requestOtp("9000000001", "device-1234", "k1") as ApiResult.Err).error
        assertThat(limited.code).isEqualTo("OTP_RATE_LIMITED")
        assertThat(limited.retryAfter).isEqualTo(90.seconds)
        assertThat(limited.requestId).isEqualTo("r-9")

        val wrong = (api.verifyOtp(OtpVerifyBody("c1", "000000", "device-1234"), "k2") as ApiResult.Err).error
        assertThat(wrong.attemptsRemaining).isEqualTo(1)
        assertThat(wrong.isUnauthorized).isTrue()

        val revoked = (api.profiles(null) as ApiResult.Err).error
        assertThat(revoked.isRevoked).isTrue()

        val proxy = (api.notifications(null, null) as ApiResult.Err).error
        assertThat(proxy.code).isEqualTo("UNAVAILABLE")
        assertThat(proxy.retryable).isTrue()
    }

    @Test
    fun unreachableServerIsAPlainNetworkError() = runTest {
        server.close()
        val error = (api.profiles(null) as ApiResult.Err).error
        assertThat(error.code).isEqualTo(ApiError.CODE_NETWORK)
        assertThat(error.isNetwork).isTrue()
    }

    @Test
    fun signedOutCallsAreNotSent() = runTest {
        token = null
        val error = (api.profiles(null) as ApiResult.Err).error
        assertThat(error.isUnauthorized).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun blobPathIsOnlyBuiltForValidHashes() = runTest {
        val result = api.blob("../../etc/passwd") { _, _ -> Unit }
        assertThat(result).isInstanceOf(ApiResult.Err::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun apiRootNormalisationAcceptsDiscoveryAndHumanInput() {
        assertThat(ApiRoot.normalize("https://x.trycloudflare.com/api/mobile/v1").toString()).isEqualTo("https://x.trycloudflare.com/api/mobile/v1/")
        assertThat(ApiRoot.normalize("x.trycloudflare.com").toString()).isEqualTo("https://x.trycloudflare.com/api/mobile/v1/")
        assertThat(ApiRoot.normalize("http://10.0.2.2:4320/").toString()).isEqualTo("http://10.0.2.2:4320/api/mobile/v1/")
        assertThat(ApiRoot.normalize("  ")).isNull()
        assertThat(ApiRoot.displayOrigin(ApiRoot.normalize("http://10.0.2.2:4320")!!)).isEqualTo("http://10.0.2.2:4320")
    }

    @Test
    fun retryAfterAcceptsSecondsAndHttpDates() {
        val clock = MutableClock()
        assertThat(RetryAfter.parse("120", clock)).isEqualTo(120.seconds)
        assertThat(RetryAfter.parse("Sat, 21 Nov 2026 04:32:00 GMT", clock)).isEqualTo(120.seconds)
        assertThat(RetryAfter.parse("-5", clock)).isNull()
        assertThat(RetryAfter.parse("soon", clock)).isNull()
    }
}
