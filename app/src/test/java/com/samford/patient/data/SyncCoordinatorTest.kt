package com.samford.patient.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.AppContainer
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.sync.SyncEngine
import com.samford.patient.testing.FakeMobileApi
import com.samford.patient.testing.MutableClock
import com.samford.patient.testing.TestContainers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncCoordinatorTest {
    private lateinit var api: FakeMobileApi
    private lateinit var clock: MutableClock
    private lateinit var c: AppContainer
    private var rediscoveries = 0

    @Before
    fun setUp() = runTest {
        api = FakeMobileApi()
        clock = MutableClock()
        c = TestContainers.create(ApplicationProvider.getApplicationContext(), api, clock)
        val challenge = c.auth.requestOtp("9000000001") as ApiResult.Ok
        c.auth.verifyOtp(challenge.value.challengeId, "9000000001", challenge.value.demoCode!!)
        c.syncEngine.syncAll(force = false)
    }

    @After
    fun tearDown() = c.database.close()

    private fun coordinator(serverMoved: Boolean) = SyncCoordinator(
        engine = c.syncEngine, db = c.database, settings = c.settings, tokens = c.tokens, session = c.session,
        notifier = null, scheduling = null, clock = clock,
        rediscover = {
            rediscoveries++
            serverMoved
        },
    )

    @Test
    fun repeatedNetworkFailuresRediscoverTheServerAndRetryOnceThere() = runTest {
        val sync = coordinator(serverMoved = true)

        // One failure may be a blip: the address is kept.
        api.fail("notifications", ApiError.network())
        assertThat(sync.run(force = false, reason = "foreground")!!.pollError!!.isNetwork).isTrue()
        assertThat(rediscoveries).isEqualTo(0)

        // The second consecutive failure re-reads discovery; the retry at the new address clears the backoff.
        clock.advanceSeconds(3_600)
        api.fail("notifications", ApiError.network())
        api.calls.clear()
        val summary = sync.run(force = false, reason = "foreground")!!
        assertThat(rediscoveries).isEqualTo(1)
        assertThat(summary.failed).isFalse()
        assertThat(api.calls.count { it.op == "notifications" }).isEqualTo(2)
        assertThat(c.database.sync().get(SyncEngine.NOTIFICATIONS_SCOPE)!!.failureCount).isEqualTo(0)
        assertThat(sync.status.value.lastErrorCode).isNull()
    }

    @Test
    fun clientErrorsAndAnUnmovedServerKeepTheBackoff() = runTest {
        val sync = coordinator(serverMoved = false)

        api.fail("notifications", ApiError("NOT_ACCEPTABLE", httpStatus = 400))
        sync.run(force = true, reason = "manual")
        assertThat(rediscoveries).isEqualTo(0)

        clock.advanceSeconds(3_600)
        api.fail("notifications", ApiError("UPSTREAM", retryable = true, httpStatus = 502))
        val summary = sync.run(force = true, reason = "manual")!!
        assertThat(rediscoveries).isEqualTo(1)
        assertThat(summary.pollError!!.httpStatus).isEqualTo(502)
        assertThat(c.database.sync().get(SyncEngine.NOTIFICATIONS_SCOPE)!!.nextAttemptAt).isGreaterThan(clock.now().toEpochMilli())
        assertThat(sync.status.value.lastErrorCode).isEqualTo("UPSTREAM")
    }
}
