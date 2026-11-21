package com.samford.patient.sync

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.RecipientDto
import com.samford.patient.core.sync.BackoffPolicy
import com.samford.patient.core.sync.FeedKinds
import com.samford.patient.core.sync.NotificationPoll
import com.samford.patient.core.sync.SyncEngine
import com.samford.patient.core.sync.SyncOutcome
import com.samford.patient.testing.FakeMobileApi
import com.samford.patient.testing.Fixtures
import com.samford.patient.testing.MutableClock
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private lateinit var db: SamfordDatabase
    private lateinit var api: FakeMobileApi
    private lateinit var clock: MutableClock
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = SamfordDatabase.inMemory(ApplicationProvider.getApplicationContext())
        api = FakeMobileApi()
        clock = MutableClock()
        engine = SyncEngine(api, db, clock, BackoffPolicy(random = Random(7)))
        api.setRecord("patient-a", Fixtures.record("patient-a", Fixtures.document(api, "rx-1", "visit-1", "patient-a")))
        api.addEvent("patient-a", "DOCUMENT_AVAILABLE", "rx-1", "visit-1", true, "type" to "PRESCRIPTION", "doctorName" to "Dr. Demo A")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun signIn() = engine.storeProfiles(api.profiles)

    @Test
    fun firstSyncIsAQuietBackfillOfFeedAndRecord() = runTest {
        signIn()
        val summary = engine.syncAll(force = false)

        assertThat(summary.signedOut).isNull()
        val doc = db.records().document("rx-1")!!
        assertThat(doc.kind).isEqualTo("PRESCRIPTION")
        assertThat(db.records().pages("rx-1", 1).map { it.sha256 }).containsExactlyElementsIn(
            api.records.getValue("patient-a").visits.single().documents.single().versions.single().pages.map { it.pngSha256 },
        )
        val items = db.records().unnotifiedFeedItems()
        assertThat(items).isEmpty() // backfill never notifies
        assertThat(db.sync().get(SyncEngine.feedScope("patient-a"))!!.cursor).isEqualTo("100")
    }

    @Test
    fun unchangedPeriodicRunCostsOneConditionalRequest() = runTest {
        signIn()
        engine.syncAll(force = false)
        api.calls.clear()

        val summary = engine.syncAll(force = false)

        assertThat(api.calls.map { it.op }).containsExactly("notifications")
        assertThat(api.calls.single().etag).isNotNull()
        assertThat(summary.requests).isEqualTo(1)
    }

    @Test
    fun amendmentArrivesThroughNotificationFeedAndRecordWithVersionHistoryKept() = runTest {
        signIn()
        engine.syncAll(force = false)
        api.calls.clear()

        api.setRecord("patient-a", Fixtures.record("patient-a", Fixtures.document(api, "rx-1", "visit-1", "patient-a", versions = 2)))
        api.addEvent("patient-a", "DOCUMENT_AMENDED", "rx-1", "visit-1", true, "version" to "2", "reason" to "Dose corrected")

        val summary = engine.syncAll(force = false)

        assertThat(api.calls.map { it.op }).containsExactly("notifications", "feed", "record").inOrder()
        assertThat(api.calls.first { it.op == "feed" }.cursor).isEqualTo("100")
        assertThat(summary.newFeedItems).isEqualTo(1)
        val doc = db.records().document("rx-1")!!
        assertThat(doc.currentVersion).isEqualTo(2)
        assertThat(db.records().versions("rx-1").map { it.version }).containsExactly(2, 1).inOrder()
        assertThat(db.records().pages("rx-1", 1)).isNotEmpty() // the original stays viewable
        val item = db.records().unnotifiedFeedItems().single()
        assertThat(item.kind).isEqualTo(FeedKinds.DOCUMENT_AMENDED)
        assertThat(item.refId).isEqualTo("rx-1")
    }

    @Test
    fun feedRevalidatesWithETagAndSkipsRecordWhenNothingChanged() = runTest {
        signIn()
        engine.syncProfile("patient-a")
        api.calls.clear()
        clock.advanceSeconds(120)

        val outcome = engine.syncProfile("patient-a")

        assertThat(outcome).isEqualTo(SyncOutcome.UpToDate)
        assertThat(api.calls.map { it.op }).containsExactly("feed")
        assertThat(api.calls.single().etag).isEqualTo("\"feed-patient-a-100\"")
    }

    @Test
    fun rateLimitHonoursRetryAfterEvenForManualRefresh() = runTest {
        signIn()
        api.fail("feed", ApiError("RATE_LIMITED", httpStatus = 429, retryable = true, retryAfter = 120.seconds))

        val outcome = engine.syncProfile("patient-a", force = true)

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        val next = (outcome as SyncOutcome.Failed).nextAttemptAt
        assertThat(next).isAtLeast(clock.now.toEpochMilli() + 120_000)
        assertThat(next).isAtMost(clock.now.toEpochMilli() + 132_000) // at most 10% spread
        api.calls.clear()
        assertThat(engine.syncProfile("patient-a", force = true)).isInstanceOf(SyncOutcome.Deferred::class.java)
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun serverErrorsBackOffExponentiallyButManualRefreshMayRetry() = runTest {
        signIn()
        api.fail("feed", ApiError("UNAVAILABLE", httpStatus = 503, retryable = true))
        val first = engine.syncProfile("patient-a") as SyncOutcome.Failed
        assertThat(engine.syncProfile("patient-a")).isInstanceOf(SyncOutcome.Deferred::class.java)

        api.fail("feed", ApiError("UNAVAILABLE", httpStatus = 503, retryable = true))
        val second = engine.syncProfile("patient-a", force = true) as SyncOutcome.Failed
        val now = clock.now.toEpochMilli()
        assertThat(first.nextAttemptAt - now).isIn(com.google.common.collect.Range.closed(15_000L, 30_000L))
        assertThat(second.nextAttemptAt - now).isIn(com.google.common.collect.Range.closed(30_000L, 60_000L))

        val ok = engine.syncProfile("patient-a", force = true)
        assertThat(ok).isInstanceOf(SyncOutcome.Updated::class.java)
        assertThat(db.sync().get(SyncEngine.feedScope("patient-a"))!!.failureCount).isEqualTo(0)
    }

    @Test
    fun failedRecordFetchIsRetriedOnTheNextRunEvenWithoutNewEvents() = runTest {
        signIn()
        api.fail("record", ApiError("UNAVAILABLE", httpStatus = 503, retryable = true))
        assertThat(engine.syncProfile("patient-a")).isInstanceOf(SyncOutcome.Failed::class.java)
        assertThat(db.records().document("rx-1")).isNull()
        // The event cursor advanced, but the record is marked as still needed.
        assertThat(db.sync().get(SyncEngine.recordScope("patient-a"))!!.caughtUp).isFalse()

        clock.advanceSeconds(3600)
        api.calls.clear()
        engine.syncProfile("patient-a")

        assertThat(api.calls.map { it.op }).containsExactly("feed", "record").inOrder()
        assertThat(db.records().document("rx-1")).isNotNull()
    }

    @Test
    fun expiredTokenSignsOutWithoutWipeButRevokedDeviceIsReportedAsRevoked() = runTest {
        signIn()
        api.fail("notifications", ApiError("TOKEN_EXPIRED", httpStatus = 401))
        assertThat(engine.syncAll(force = false).signedOut).isEqualTo(SyncOutcome.SignedOut(revoked = false))

        api.fail("notifications", ApiError("DEVICE_SIGNED_OUT", httpStatus = 401))
        assertThat(engine.syncAll(force = false).signedOut).isEqualTo(SyncOutcome.SignedOut(revoked = true))
    }

    @Test
    fun recordsOfAnotherPatientAreNeverApplied() = runTest {
        signIn()
        val foreign = Fixtures.document(api, "rx-x", "visit-1", "patient-b")
        api.setRecord("patient-a", Fixtures.record("patient-a", Fixtures.document(api, "rx-1", "visit-1", "patient-a"), foreign))

        engine.syncProfile("patient-a")

        assertThat(db.records().document("rx-1")).isNotNull()
        assertThat(db.records().document("rx-x")).isNull()
    }

    @Test
    fun pharmacyAndLabStatesBecomeHomeItemsWithRecipientNames() = runTest {
        signIn()
        engine.syncAll(force = false)
        val dispensed = Fixtures.document(
            api, "rx-1", "visit-1", "patient-a",
            recipients = listOf(RecipientDto("PHARMACY", null, "Samford OPD Pharmacy", "DISPENSED", 1, "2026-11-21T05:00:00Z", null)),
        )
        api.setRecord("patient-a", Fixtures.record("patient-a", dispensed))
        api.addEvent("patient-a", "DOCUMENT_STATE_CHANGED", "rx-1", "visit-1", true, "recipientKind" to "PHARMACY", "state" to "DISPENSED", "version" to "1")
        api.addEvent("patient-a", "SOMETHING_NEW_IN_V1_1", "rx-1", "visit-1", false)

        engine.syncAll(force = false)

        val item = db.records().unnotifiedFeedItems().single()
        assertThat(item.kind).isEqualTo(FeedKinds.MEDICINES_DISPENSED)
        assertThat(item.actor).isEqualTo("Samford OPD Pharmacy")
        assertThat(item.refType).isEqualTo("visit")
        assertThat(db.records().recipients("rx-1").single().state).isEqualTo("DISPENSED")
    }

    @Test
    fun firstNotificationPollPagesToTheEndAndIsTreatedAsBaseline() = runTest {
        signIn()
        repeat(250) { api.addEvent("patient-child", "VISIT_UPDATED", null, "visit-$it", true, "status" to "WAITING") }

        val poll = engine.pollNotifications()

        assertThat(poll).isEqualTo(NotificationPoll.Baseline)
        assertThat(api.count("notifications")).isEqualTo(3)
        api.calls.clear()
        assertThat(engine.pollNotifications()).isEqualTo(NotificationPoll.Unchanged)
        assertThat(api.calls.single().etag).isNotNull()
    }

    @Test
    fun viewedAcknowledgementsAreSentOnceWithTheirStoredIdempotencyKey() = runTest {
        signIn()
        engine.syncAll(force = false)
        db.acks().insert(com.samford.patient.core.db.PendingAckEntity("rx-1", 1, 0L, "key-123"))

        assertThat(engine.flushAcks()).isEqualTo(1)
        assertThat(api.calls.last().arg).isEqualTo("rx-1@1:key-123")
        assertThat(engine.flushAcks()).isEqualTo(0)
    }
}
