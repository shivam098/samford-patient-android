package com.samford.patient.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.network.ApiError
import com.samford.patient.testing.FakeMobileApi
import com.samford.patient.testing.MutableClock
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlobStoreTest {
    private lateinit var db: SamfordDatabase
    private lateinit var api: FakeMobileApi
    private lateinit var clock: MutableClock
    private val root = Files.createTempDirectory("blobs").toFile()

    @Before
    fun setUp() {
        db = SamfordDatabase.inMemory(ApplicationProvider.getApplicationContext())
        api = FakeMobileApi()
        clock = MutableClock()
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun store(max: Long = BlobStore.DEFAULT_MAX_BYTES) = BlobStore(root, db.blobs(), api, clock, max)

    @Test
    fun downloadIsVerifiedAndThenServedFromCacheWithoutNetwork() = runTest {
        val sha = api.putBlob(ByteArray(10_000) { (it % 251).toByte() })
        val blobs = store()

        val first = blobs.fetch(sha) as BlobResult.Ready
        api.calls.clear()
        val second = blobs.fetch(sha) as BlobResult.Ready

        assertThat(second.file).isEqualTo(first.file)
        assertThat(api.calls).isEmpty()
        assertThat(BlobStore.sha256Hex(first.file.readBytes())).isEqualTo(sha)
        assertThat(blobs.verify(sha)).isTrue()
    }

    @Test
    fun corruptedDownloadIsRejectedAndNeverCached() = runTest {
        val sha = api.putBlob("handwriting".toByteArray())
        api.corruptBlobs = true
        val blobs = store()

        val result = blobs.fetch(sha)

        assertThat(result).isInstanceOf(BlobResult.Failed::class.java)
        assertThat((result as BlobResult.Failed).error.code).isEqualTo(ApiError.CODE_INTEGRITY)
        assertThat(blobs.cached(sha)).isNull()
        assertThat(blobs.fileFor(sha).exists()).isFalse()
    }

    @Test
    fun leastRecentlyUsedPagesAreEvictedButPinnedSamplesStay() = runTest {
        val blobs = store(max = 50_000)
        val pinned = blobs.put(ByteArray(10_000) { 1 }, "image/png", pinned = true)
        val shas = (1..6).map { i -> api.putBlob(ByteArray(10_000) { (i * 7 + it % 13).toByte() }) }
        for (sha in shas) {
            clock.advanceSeconds(10)
            assertThat(blobs.fetch(sha)).isInstanceOf(BlobResult.Ready::class.java)
        }
        // Total would be 70 kB: the oldest unpinned pages go first, keeping the cache within the 50 kB cap.
        assertThat(db.blobs().totalBytes()).isAtMost(50_000)
        assertThat(blobs.cached(pinned)).isNotNull()
        assertThat(blobs.cached(shas.last())).isNotNull()
        assertThat(blobs.cached(shas.first())).isNull()

        // Reopening the oldest remaining page makes it recently used, so it survives the next eviction.
        val survivor = shas[2]
        clock.advanceSeconds(10)
        assertThat(blobs.cached(survivor)).isNotNull()
        clock.advanceSeconds(10)
        blobs.fetch(api.putBlob(ByteArray(10_000) { 99 }))
        assertThat(blobs.cached(survivor)).isNotNull()
        assertThat(blobs.cached(shas[3])).isNull()
    }

    @Test
    fun concurrentRequestsForOnePageShareOneDownload() = runTest {
        val sha = api.putBlob(ByteArray(50_000) { 3 })
        val blobs = store()

        val results = (1..8).map { async { blobs.fetch(sha) } }.awaitAll()

        assertThat(results.all { it is BlobResult.Ready }).isTrue()
        assertThat(api.count("blob")).isEqualTo(1)
    }

    @Test
    fun clearingDownloadsKeepsPinnedSamplePages() = runTest {
        val blobs = store()
        val pinned = blobs.put("sample".toByteArray(), "image/png", pinned = true)
        val downloaded = api.putBlob("server".toByteArray())
        blobs.fetch(downloaded)

        blobs.clearUnpinned()

        assertThat(blobs.cached(pinned)).isNotNull()
        assertThat(blobs.cached(downloaded)).isNull()
    }
}
