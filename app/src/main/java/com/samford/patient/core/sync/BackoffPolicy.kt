package com.samford.patient.core.sync

import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with "equal jitter": the n-th consecutive failure waits a random time in
 * [d/2, d] where d = base * 2^(n-1), capped. A server Retry-After is a floor, plus up to 10%
 * (max 60 s) extra spread so thousands of phones told the same Retry-After do not return together.
 */
class BackoffPolicy(
    val base: Duration = 30.seconds,
    val cap: Duration = 6.hours,
    private val random: Random = Random.Default,
) {
    fun delayFor(consecutiveFailures: Int, retryAfter: Duration? = null): Duration {
        val n = consecutiveFailures.coerceAtLeast(1)
        val exponent = min(n - 1, 30)
        val raw = base.inWholeMilliseconds.toDouble() * (1L shl exponent).toDouble()
        val capped = min(raw, cap.inWholeMilliseconds.toDouble())
        val jittered = capped / 2 + random.nextDouble() * (capped / 2)
        val computed = jittered.toLong().milliseconds
        if (retryAfter == null || retryAfter <= Duration.ZERO) return computed
        val spreadMs = min(retryAfter.inWholeMilliseconds / 10, 60_000L)
        val honoured = retryAfter + (if (spreadMs > 0) random.nextLong(spreadMs + 1) else 0L).milliseconds
        return maxOf(computed, honoured)
    }

    companion object {
        /** Minimum spacing between user-initiated refreshes of the same scope. */
        val MANUAL_REFRESH_FLOOR: Duration = 1.minutes
    }
}

/** Stable per-install phase so periodic syncs of 1 lakh phones are spread across the whole hour. */
object SyncPhase {
    fun initialDelayMillis(installId: String, periodMillis: Long): Long {
        val h = installId.hashCode().toLong() and 0x7fffffffL
        return h % periodMillis
    }
}
