package com.samford.patient.testing

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.samford.patient.AppContainer
import com.samford.patient.SamfordApp
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.security.TokenCipher
import com.samford.patient.core.time.Clock
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Reversible stand-in for the Android Keystore, which Robolectric does not provide. */
class FakeCipher : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(blob: ByteArray): ByteArray = blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray().reversedArray()
}

class MutableClock(var now: Instant = Instant.parse("2026-11-21T04:30:00Z")) : Clock {
    override fun now(): Instant = now
    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

object TestContainers {
    fun create(context: Context, api: FakeMobileApi, clock: Clock = MutableClock()): AppContainer {
        val dir: File = Files.createTempDirectory("samford-test").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return AppContainer(
            context = context,
            clock = clock,
            tokenCipher = FakeCipher(),
            apiOverride = api,
            database = SamfordDatabase.inMemory(context),
            sessionDataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "session.preferences_pb") },
            settingsDataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") },
            enableBackgroundWork = false,
        )
    }
}

/** Application used by Robolectric UI tests: fakes instead of network, Keystore and WorkManager. */
class TestSamfordApp : SamfordApp() {
    val api = FakeMobileApi()
    val clock = MutableClock()

    override fun onCreate() {
        container = TestContainers.create(this, api, clock)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(this).build())
        super.onCreate()
    }
}
