package com.samford.patient.core.sync

import androidx.room.withTransaction
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.db.SyncStateEntity
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.EventDto
import com.samford.patient.core.network.MobileApi
import com.samford.patient.core.network.PatientRecordDto
import com.samford.patient.core.network.ProfileDto
import com.samford.patient.core.time.Clock
import kotlin.time.Duration.Companion.hours

sealed interface SyncOutcome {
    data class Updated(val events: Int, val newFeedItems: Int) : SyncOutcome
    data object UpToDate : SyncOutcome
    data class Deferred(val untilMillis: Long) : SyncOutcome
    data class Failed(val error: ApiError, val nextAttemptAt: Long) : SyncOutcome
    data class SignedOut(val revoked: Boolean) : SyncOutcome
    data object UpdateRequired : SyncOutcome
}

sealed interface NotificationPoll {
    data object Unchanged : NotificationPoll
    data class Changed(val profileIds: Set<String>) : NotificationPoll
    /** First poll after sign-in (or no notification endpoint): revalidate every profile once. */
    data object Baseline : NotificationPoll
    data class Failed(val error: ApiError?) : NotificationPoll
    data class SignedOut(val revoked: Boolean) : NotificationPoll
    data object UpdateRequired : NotificationPoll
}

data class SyncSummary(
    val outcomes: Map<String, SyncOutcome>,
    val signedOut: SyncOutcome.SignedOut? = null,
    val updateRequired: Boolean = false,
    val newFeedItems: Int = 0,
    val requests: Int = 0,
) {
    val failed: Boolean get() = outcomes.values.any { it is SyncOutcome.Failed }
}

/**
 * Client sync for the Samford mobile API v1, designed to be cheap for the server at 1 lakh phones:
 * - one conditional GET /patient/notifications per run (normally 304) across all family profiles;
 * - per changed profile: the event feed from the saved cursor (304 when nothing is new), then the
 *   authoritative record only when events arrived (or after a failed record fetch);
 * - the record snapshot, feed items and cursor are committed in one transaction;
 * - per-scope exponential backoff with jitter, honouring Retry-After;
 * - the first sync of a profile is a quiet backfill (no notifications).
 */
class SyncEngine(
    private val api: MobileApi,
    private val db: SamfordDatabase,
    private val clock: Clock,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val maxPagesPerRun: Int = 20,
) {
    private val records get() = db.records()
    private val applier get() = RecordApplier(records)
    private val mapper get() = EventMapper(records)

    /** Counts HTTP requests issued, for the client load model and tests. */
    @Volatile
    var requestCount: Int = 0
        private set

    suspend fun syncProfile(profileId: String, force: Boolean = false): SyncOutcome {
        val dao = db.sync()
        var feed = dao.get(feedScope(profileId)) ?: SyncStateEntity(feedScope(profileId))
        var record = dao.get(recordScope(profileId)) ?: SyncStateEntity(recordScope(profileId))
        val now = clock.now().toEpochMilli()
        val deferredUntil = maxOf(feed.nextAttemptAt, record.nextAttemptAt)
        if (deferredUntil > now) {
            val serverAskedToWait = feed.lastErrorCode == RATE_LIMITED || record.lastErrorCode == RATE_LIMITED
            if (!force || serverAskedToWait) return SyncOutcome.Deferred(deferredUntil)
        }
        if (force && feed.failureCount == 0 && record.caughtUp && feed.lastSuccessAt != null &&
            now - feed.lastSuccessAt < BackoffPolicy.MANUAL_REFRESH_FLOOR.inWholeMilliseconds
        ) {
            return SyncOutcome.UpToDate
        }

        // 1. New events since the saved cursor.
        val notify = feed.cursor != null
        val events = mutableListOf<EventDto>()
        var cursor = feed.cursor
        var etag: String? = null
        var caughtUp = false
        var pages = 0
        while (pages < maxPagesPerRun) {
            requestCount++
            val sentEtag = if (feed.caughtUp && cursor == feed.cursor) feed.etag else null
            val at = clock.now().toEpochMilli()
            when (val r = api.feed(profileId, cursor, sentEtag)) {
                ApiResult.NotModified -> {
                    caughtUp = true
                    etag = feed.etag
                    break
                }
                is ApiResult.Ok -> {
                    events += r.value.events
                    cursor = r.value.nextCursor ?: cursor
                    pages++
                    if (!r.value.hasMore || r.value.nextCursor == null) {
                        caughtUp = true
                        etag = r.etag
                        break
                    }
                }
                is ApiResult.Err -> return fail(feed, r.error, at)
            }
        }

        // 2. The authoritative record, only when something changed or the last fetch failed.
        var snapshot: PatientRecordDto? = null
        var recordEtag = record.etag
        val needRecord = events.isNotEmpty() || !record.caughtUp || force
        var recordError: ApiError? = null
        if (needRecord) {
            requestCount++
            when (val r = api.record(profileId, if (record.caughtUp) record.etag else null)) {
                ApiResult.NotModified -> Unit
                is ApiResult.Ok -> {
                    snapshot = r.value
                    recordEtag = r.etag
                }
                is ApiResult.Err -> recordError = r.error
            }
        }

        // 3. Commit snapshot, feed items and cursors together.
        val at = clock.now().toEpochMilli()
        val inserted = db.withTransaction {
            snapshot?.let { applier.applyRecord(profileId, it) }
            val items = mapper.map(profileId, events, notify, at)
            val ids = if (items.isEmpty()) emptyList() else records.insertFeedItems(items)
            feed = feed.copy(
                cursor = cursor, etag = if (caughtUp) etag else null, caughtUp = caughtUp,
                lastAttemptAt = at, lastSuccessAt = at, failureCount = 0, nextAttemptAt = 0, lastErrorCode = null,
            )
            dao.upsert(feed)
            if (recordError == null) {
                record = record.copy(
                    etag = recordEtag, caughtUp = true, lastAttemptAt = at,
                    lastSuccessAt = if (needRecord) at else record.lastSuccessAt,
                    failureCount = 0, nextAttemptAt = 0, lastErrorCode = null,
                )
                dao.upsert(record)
            } else {
                dao.upsert(record.copy(caughtUp = false))
            }
            ids.count { it != -1L }
        }
        recordError?.let { return fail(record.copy(caughtUp = false), it, at) }
        return if (events.isNotEmpty() || snapshot != null) SyncOutcome.Updated(events.size, inserted) else SyncOutcome.UpToDate
    }

    private suspend fun fail(state: SyncStateEntity, error: ApiError, at: Long): SyncOutcome {
        if (error.isUnauthorized) return SyncOutcome.SignedOut(error.isRevoked)
        if (error.isUpdateRequired) return SyncOutcome.UpdateRequired
        val failures = state.failureCount + 1
        val delay = backoff.delayFor(failures, error.retryAfter)
        val next = state.copy(
            lastAttemptAt = at, failureCount = failures,
            nextAttemptAt = at + delay.inWholeMilliseconds,
            lastErrorCode = if (error.httpStatus == 429) RATE_LIMITED else error.code,
        )
        db.sync().upsert(next)
        return SyncOutcome.Failed(error, next.nextAttemptAt)
    }

    /** One conditional poll across all profiles; pages through on the first (baseline) poll. */
    suspend fun pollNotifications(): NotificationPoll {
        val dao = db.sync()
        var state = dao.get(NOTIFICATIONS_SCOPE) ?: SyncStateEntity(NOTIFICATIONS_SCOPE)
        val now = clock.now().toEpochMilli()
        if (state.nextAttemptAt > now) return NotificationPoll.Failed(null)
        val baseline = state.cursor == null
        val changed = mutableSetOf<String>()
        var cursor = state.cursor
        var pages = 0
        while (pages < maxPagesPerRun) {
            requestCount++
            val sentEtag = if (state.caughtUp && cursor == state.cursor) state.etag else null
            val at = clock.now().toEpochMilli()
            when (val r = api.notifications(cursor, sentEtag)) {
                ApiResult.NotModified -> {
                    dao.upsert(state.copy(lastAttemptAt = at, lastSuccessAt = at, failureCount = 0, nextAttemptAt = 0, lastErrorCode = null))
                    return if (baseline) NotificationPoll.Baseline else if (changed.isEmpty()) NotificationPoll.Unchanged else NotificationPoll.Changed(changed)
                }
                is ApiResult.Ok -> {
                    r.value.notifications.forEach { changed += it.profileId }
                    cursor = r.value.nextCursor ?: cursor
                    pages++
                    val done = !r.value.hasMore || r.value.nextCursor == null
                    state = state.copy(
                        cursor = cursor, etag = if (done) r.etag else null, caughtUp = done,
                        lastAttemptAt = at, lastSuccessAt = at, failureCount = 0, nextAttemptAt = 0, lastErrorCode = null,
                    )
                    dao.upsert(state)
                    if (done) break
                }
                is ApiResult.Err -> {
                    val e = r.error
                    return when {
                        e.isUnauthorized -> NotificationPoll.SignedOut(e.isRevoked)
                        e.isUpdateRequired -> NotificationPoll.UpdateRequired
                        e.isNotFound -> NotificationPoll.Baseline
                        else -> {
                            fail(state, e, at)
                            NotificationPoll.Failed(e)
                        }
                    }
                }
            }
        }
        return when {
            baseline -> NotificationPoll.Baseline
            changed.isEmpty() -> NotificationPoll.Unchanged
            else -> NotificationPoll.Changed(changed)
        }
    }

    /** Refreshes the profiles registered with the signed-in mobile. At most daily unless [force]. */
    suspend fun refreshProfiles(force: Boolean = false): SyncOutcome {
        val dao = db.sync()
        val state = dao.get(PROFILES_SCOPE) ?: SyncStateEntity(PROFILES_SCOPE)
        val now = clock.now().toEpochMilli()
        val last = state.lastSuccessAt
        if (!force && last != null && now - last < PROFILE_REFRESH.inWholeMilliseconds) return SyncOutcome.UpToDate
        if (!force && state.nextAttemptAt > now) return SyncOutcome.Deferred(state.nextAttemptAt)
        requestCount++
        return when (val r = api.profiles(state.etag)) {
            is ApiResult.Ok -> {
                storeProfiles(r.value.profiles)
                dao.upsert(state.copy(etag = r.etag, lastAttemptAt = now, lastSuccessAt = now, failureCount = 0, nextAttemptAt = 0, lastErrorCode = null))
                SyncOutcome.Updated(0, 0)
            }
            ApiResult.NotModified -> {
                dao.upsert(state.copy(lastAttemptAt = now, lastSuccessAt = now, failureCount = 0, nextAttemptAt = 0))
                SyncOutcome.UpToDate
            }
            is ApiResult.Err -> fail(state, r.error, now)
        }
    }

    suspend fun storeProfiles(profiles: List<ProfileDto>) {
        db.withTransaction {
            val existing = records.profiles().associateBy { it.id }
            records.upsertProfiles(
                profiles.mapIndexed { i, p -> p.toEntity(i, allergiesJson = existing[p.profileId]?.allergiesJson) },
            )
            records.deleteProfilesNotIn(profiles.map { it.profileId })
        }
    }

    /** Sends queued "viewed" acknowledgements. Stored idempotency keys make retries replay safely. */
    suspend fun flushAcks(): Int {
        var sent = 0
        for (ack in db.acks().pending()) {
            requestCount++
            val result = api.markViewed(ack.documentId, ack.version, ack.idempotencyKey)
            if (result is ApiResult.Err) {
                val status = result.error.httpStatus
                if (status == 404 || status == 409 || status == 422) {
                    db.acks().delete(ack.documentId, ack.version)
                    continue
                }
                break
            }
            db.acks().delete(ack.documentId, ack.version)
            sent++
        }
        return sent
    }

    /**
     * One sync run. Periodic/foreground: notification probe, then only changed profiles.
     * Manual ([force]): revalidate every profile (normally one 304 each).
     */
    suspend fun syncAll(force: Boolean): SyncSummary {
        val before = requestCount
        when (val p = refreshProfiles(force = false)) {
            is SyncOutcome.SignedOut -> return SyncSummary(emptyMap(), p, requests = requestCount - before)
            SyncOutcome.UpdateRequired -> return SyncSummary(emptyMap(), updateRequired = true, requests = requestCount - before)
            else -> Unit
        }
        val known = records.profiles().map { it.id }
        val targets: Collection<String> = when (val poll = pollNotifications()) {
            NotificationPoll.Baseline -> known
            NotificationPoll.Unchanged, is NotificationPoll.Failed -> if (force) known else emptyList()
            is NotificationPoll.Changed -> {
                if (poll.profileIds.any { it !in known }) refreshProfiles(force = true)
                val refreshed = records.profiles().map { it.id }
                if (force) refreshed else poll.profileIds.filter { it in refreshed }
            }
            is NotificationPoll.SignedOut ->
                return SyncSummary(emptyMap(), SyncOutcome.SignedOut(poll.revoked), requests = requestCount - before)
            NotificationPoll.UpdateRequired ->
                return SyncSummary(emptyMap(), updateRequired = true, requests = requestCount - before)
        }
        val outcomes = linkedMapOf<String, SyncOutcome>()
        var newItems = 0
        for (id in targets) {
            val outcome = syncProfile(id, force)
            outcomes[id] = outcome
            when (outcome) {
                is SyncOutcome.Updated -> newItems += outcome.newFeedItems
                is SyncOutcome.SignedOut -> return SyncSummary(outcomes, outcome, newFeedItems = newItems, requests = requestCount - before)
                SyncOutcome.UpdateRequired -> return SyncSummary(outcomes, updateRequired = true, newFeedItems = newItems, requests = requestCount - before)
                else -> Unit
            }
        }
        flushAcks()
        return SyncSummary(outcomes, null, false, newItems, requestCount - before)
    }

    /** Fetches one document's detail when the viewer needs it (for example a version missing locally). */
    suspend fun refreshDocument(profileId: String, documentId: String): ApiError? {
        requestCount++
        return when (val r = api.document(documentId, null)) {
            is ApiResult.Ok -> {
                db.withTransaction { applier.applyDocument(profileId, r.value) }
                null
            }
            ApiResult.NotModified -> null
            is ApiResult.Err -> r.error
        }
    }

    companion object {
        const val NOTIFICATIONS_SCOPE = "notifications"
        const val PROFILES_SCOPE = "profiles"
        private const val RATE_LIMITED = "RATE_LIMITED"
        val PROFILE_REFRESH = 24.hours
        fun feedScope(profileId: String) = "feed:$profileId"
        fun recordScope(profileId: String) = "record:$profileId"
    }
}
