package com.samford.patient.sync

import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.sync.BackoffPolicy
import com.samford.patient.core.sync.SyncPhase
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class BackoffPolicyTest {
    private val policy = BackoffPolicy(base = 30.seconds, cap = 6.hours, random = Random(42))

    @Test
    fun delayDoublesWithEqualJitter() {
        for (n in 1..8) {
            val ceiling = 30.seconds * (1 shl (n - 1))
            repeat(200) {
                val d = policy.delayFor(n)
                assertThat(d).isAtLeast(ceiling / 2)
                assertThat(d).isAtMost(ceiling)
            }
        }
    }

    @Test
    fun delayIsCappedAtSixHours() {
        repeat(200) { assertThat(policy.delayFor(50)).isAtMost(6.hours) }
        repeat(200) { assertThat(policy.delayFor(50)).isAtLeast(3.hours) }
    }

    @Test
    fun retryAfterIsAFloorWithBoundedSpread() {
        repeat(200) {
            val d = policy.delayFor(1, retryAfter = 10.minutes)
            assertThat(d).isAtLeast(10.minutes)
            assertThat(d).isAtMost(11.minutes)
        }
        // A long Retry-After spreads by at most 60 seconds.
        repeat(200) { assertThat(policy.delayFor(1, retryAfter = 2.hours)).isAtMost(2.hours + 60.seconds) }
    }

    @Test
    fun jitterSpreadsClientsThatFailedTogether() {
        val delays = (1..1000).map { BackoffPolicy(random = Random(it)).delayFor(4).inWholeSeconds }.toSet()
        assertThat(delays.size).isGreaterThan(100)
    }

    @Test
    fun periodicPhaseIsStablePerInstallAndSpreadAcrossThePeriod() {
        val period = 30.minutes.inWholeMilliseconds
        assertThat(SyncPhase.initialDelayMillis("install-1", period)).isEqualTo(SyncPhase.initialDelayMillis("install-1", period))
        val buckets = IntArray(30)
        repeat(100_000) {
            val delay = SyncPhase.initialDelayMillis(java.util.UUID.randomUUID().toString(), period)
            assertThat(delay).isIn(com.google.common.collect.Range.closedOpen(0L, period))
            buckets[(delay / 60_000).toInt()]++
        }
        // 1 lakh installs land roughly evenly in each minute of the window (no thundering herd).
        buckets.forEach { assertThat(it).isIn(com.google.common.collect.Range.closed(2_800, 3_900)) }
    }

    @Test
    fun zeroRetryAfterFallsBackToComputedDelay() {
        assertThat(policy.delayFor(1, retryAfter = 0.milliseconds)).isAtLeast(15.seconds)
    }
}
