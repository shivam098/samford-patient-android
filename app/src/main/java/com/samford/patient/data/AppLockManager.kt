package com.samford.patient.data

import com.samford.patient.core.security.PinHasher
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.core.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data class Wrong(val attemptsBeforePause: Int) : UnlockResult
    data class Paused(val untilMillis: Long) : UnlockResult
}

/**
 * Optional PIN lock for shared family phones. Locks on launch and after 30 seconds in the
 * background. Five wrong PINs pause entry with a growing wait. "Forgot PIN" signs out.
 */
class AppLockManager(
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null
    private var failures = 0
    private var pausedUntil = 0L

    suspend fun onLaunch() {
        if (settings.current().appLockEnabled) _locked.value = true
    }

    fun onBackground() {
        backgroundedAt = clock.now().toEpochMilli()
    }

    suspend fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (settings.current().appLockEnabled && clock.now().toEpochMilli() - since >= RELOCK_AFTER_MS) {
            _locked.value = true
        }
    }

    suspend fun unlock(pin: String): UnlockResult {
        val now = clock.now().toEpochMilli()
        if (now < pausedUntil) return UnlockResult.Paused(pausedUntil)
        val secret = settings.appLockSecret()
        if (secret == null) {
            _locked.value = false
            return UnlockResult.Unlocked
        }
        return if (PinHasher.matches(pin, secret.first, secret.second)) {
            failures = 0
            _locked.value = false
            UnlockResult.Unlocked
        } else {
            failures++
            if (failures >= MAX_FAILURES) {
                val extra = failures - MAX_FAILURES
                pausedUntil = now + PAUSE_MS * (1L shl extra.coerceAtMost(6))
                UnlockResult.Paused(pausedUntil)
            } else {
                UnlockResult.Wrong(MAX_FAILURES - failures)
            }
        }
    }

    suspend fun enable(pin: String): Boolean {
        if (!PinHasher.isValidPin(pin)) return false
        val salt = PinHasher.newSalt()
        settings.setAppLock(salt, PinHasher.hash(pin, salt))
        return true
    }

    suspend fun disable() {
        settings.clearAppLock()
        _locked.value = false
    }

    fun reset() {
        failures = 0
        pausedUntil = 0
        _locked.value = false
    }

    companion object {
        const val RELOCK_AFTER_MS = 30_000L
        const val MAX_FAILURES = 5
        const val PAUSE_MS = 30_000L
    }
}
