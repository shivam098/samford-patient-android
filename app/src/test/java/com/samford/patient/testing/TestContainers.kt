package com.samford.patient.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.samford.patient.AppContainer
import com.samford.patient.SamfordApp
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.security.TokenCipher
import com.samford.patient.core.time.Clock
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
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
    fun create(context: Context, api: FakeMobileApi, clock: Clock = MutableClock()): AppContainer = build(context, api, clock)

    /** Real HTTP client and sync stack (for UI tests against the isolated mock backend). */
    fun createReal(context: Context): AppContainer = build(context, null, Clock.System)

    private fun build(context: Context, api: FakeMobileApi?, clock: Clock): AppContainer {
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
        NativeGraphicsPrimer.prime(this)
        container = TestContainers.create(this, api, clock)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(this).build())
        super.onCreate()
    }
}

/**
 * Robolectric's native graphics fills its JNI class cache lazily, on whichever thread first needs it.
 * When that is a Coil decode thread interrupted by cancellation, the class lookup fails and the test
 * JVM aborts ("JniConstants: Class not found: java/nio/DoubleBuffer", seen on Linux CI). Loading those
 * classes and decoding one image on the main thread first removes the race.
 */
internal object NativeGraphicsPrimer {
    @Suppress("UNUSED_VARIABLE")
    fun prime(context: Context) {
        val preloaded = listOf(
            ByteBuffer::class.java, CharBuffer::class.java, ShortBuffer::class.java, IntBuffer::class.java,
            LongBuffer::class.java, FloatBuffer::class.java, DoubleBuffer::class.java, FileDescriptor::class.java,
        )
        runCatching {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsToBuffer(ByteBuffer.allocate(bitmap.byteCount))
            val file = File(context.cacheDir, "graphics-primer.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)).recycle()
            file.delete()
            bitmap.recycle()
        }
    }
}
