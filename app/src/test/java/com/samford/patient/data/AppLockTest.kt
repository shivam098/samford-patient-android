package com.samford.patient.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.security.PinHasher
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.testing.MutableClock
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockTest {
    private val clock = MutableClock()
    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(Files.createTempDirectory("lock").toFile(), "s.preferences_pb")
        },
    )
    private val lock = AppLockManager(settings, clock)

    @Test
    fun pinIsStoredOnlyAsASaltedHash() = runTest {
        assertThat(lock.enable("2468")).isTrue()
        val (salt, hash) = settings.appLockSecret()!!
        assertThat(String(hash, Charsets.ISO_8859_1)).doesNotContain("2468")
        assertThat(PinHasher.matches("2468", salt, hash)).isTrue()
        assertThat(PinHasher.matches("2469", salt, hash)).isFalse()
        assertThat(lock.enable("12")).isFalse()
        assertThat(lock.enable("12ab")).isFalse()
    }

    @Test
    fun locksOnLaunchAndAfterThirtySecondsInBackground() = runTest {
        lock.enable("2468")
        lock.onLaunch()
        assertThat(lock.locked.value).isTrue()
        assertThat(lock.unlock("2468")).isEqualTo(UnlockResult.Unlocked)

        lock.onBackground()
        clock.advanceSeconds(10)
        lock.onForeground()
        assertThat(lock.locked.value).isFalse()

        lock.onBackground()
        clock.advanceSeconds(31)
        lock.onForeground()
        assertThat(lock.locked.value).isTrue()
    }

    @Test
    fun repeatedWrongPinsPauseEntry() = runTest {
        lock.enable("2468")
        lock.onLaunch()
        repeat(4) { assertThat(lock.unlock("0000")).isInstanceOf(UnlockResult.Wrong::class.java) }
        val paused = lock.unlock("0000") as UnlockResult.Paused
        assertThat(paused.untilMillis).isEqualTo(clock.now.toEpochMilli() + 30_000)
        assertThat(lock.unlock("2468")).isInstanceOf(UnlockResult.Paused::class.java)
        clock.advanceSeconds(31)
        assertThat(lock.unlock("2468")).isEqualTo(UnlockResult.Unlocked)
    }
}
