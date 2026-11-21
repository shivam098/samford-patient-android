package com.samford.patient.data

import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.notify.Notifier
import com.samford.patient.core.security.TokenStore
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.core.sync.SyncEngine
import com.samford.patient.core.sync.SyncOutcome
import com.samford.patient.core.sync.SyncSummary
import com.samford.patient.core.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncStatus(
    val running: Boolean = false,
    val lastErrorCode: String? = null,
    val lastRunAt: Long? = null,
)

/** Callbacks into the scheduler, kept as an interface so unit tests need no WorkManager. */
interface SyncScheduling {
    fun scheduleRetry(atMillis: Long, nowMillis: Long)
    fun schedulePrefetch()
}

/**
 * Single entry point for every sync (app open, pull-to-refresh, periodic worker). Serialises runs,
 * turns server sign-outs into the right local action, posts generic notifications and schedules
 * jittered retries and Wi-Fi prefetch.
 */
class SyncCoordinator(
    private val engine: SyncEngine,
    private val db: SamfordDatabase,
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val session: SessionManager,
    private val notifier: Notifier?,
    private val scheduling: SyncScheduling?,
    private val clock: Clock,
    private val onUpdateRequired: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * [reason] "background" posts a generic local notification for new items; any other reason means
     * the person is looking at the app, so new items are only marked as seen by the notifier.
     */
    suspend fun run(force: Boolean, reason: String): SyncSummary? {
        val current = settings.current()
        if (current.sampleMode || !tokens.isSignedIn() || session.sessionExpired.value) return null
        if (mutex.isLocked && !force) return null
        return mutex.withLock {
            _status.update { it.copy(running = true) }
            try {
                val summary = engine.syncAll(force = force)
                summary.signedOut?.let { session.onServerSignedOut(it.revoked) }
                if (summary.updateRequired) onUpdateRequired()
                if (summary.signedOut == null) {
                    if (reason == "background") notifier?.notifyPending(db.records(), current.notificationsEnabled)
                    else db.records().markAllNotified()
                    val now = clock.now().toEpochMilli()
                    summary.outcomes.values.filterIsInstance<SyncOutcome.Failed>().minOfOrNull { it.nextAttemptAt }
                        ?.let { scheduling?.scheduleRetry(it, now) }
                    if (summary.newFeedItems > 0 && current.autoDownloadOnWifi) scheduling?.schedulePrefetch()
                }
                val error = summary.outcomes.values.filterIsInstance<SyncOutcome.Failed>().firstOrNull()?.error?.code
                _status.value = SyncStatus(running = false, lastErrorCode = error, lastRunAt = clock.now().toEpochMilli())
                summary
            } catch (e: Exception) {
                _status.value = SyncStatus(running = false, lastErrorCode = "unexpected", lastRunAt = clock.now().toEpochMilli())
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                null
            }
        }
    }
}

/**
 * Downloads current-version pages of recent documents on unmetered networks so they open offline.
 * Bounded per run and by the cache size; older pages load lazily when opened.
 */
class Prefetcher(
    private val db: SamfordDatabase,
    private val blobs: BlobStore,
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val clock: Clock,
) {
    suspend fun prefetchRecent(budgetBytes: Long = 40L * 1024 * 1024): Int {
        val s = settings.current()
        if (s.sampleMode || !s.autoDownloadOnWifi || !tokens.isSignedIn()) return 0
        val since = clock.now().toEpochMilli() - 90.days.inWholeMilliseconds
        var spent = 0L
        var fetched = 0
        for (page in db.records().currentPagesUpdatedSince(since)) {
            if (spent >= budgetBytes || spent >= blobs.maxBytes / 2) break
            if (blobs.cached(page.sha256) != null) continue
            when (val r = blobs.fetch(page.sha256, "image/png")) {
                is BlobResult.Ready -> {
                    spent += r.file.length()
                    fetched++
                }
                is BlobResult.Failed -> if (r.error.isNetwork || r.error.isUnauthorized) break
            }
        }
        return fetched
    }
}
