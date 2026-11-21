package com.samford.patient.core.blob

import com.samford.patient.core.db.BlobDao
import com.samford.patient.core.db.BlobEntity
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiResult
import com.samford.patient.core.network.MobileApi
import com.samford.patient.core.time.Clock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface BlobResult {
    data class Ready(val file: File) : BlobResult
    data class Failed(val error: ApiError) : BlobResult
}

/**
 * Bounded, content-addressed file cache for handwritten page images, PDFs and lab reports.
 *
 * - Every blob is stored under its SHA-256 and verified after download, so a cached page is
 *   byte-for-byte the image the doctor's tablet produced. A mismatch is discarded, never shown.
 * - Blobs are immutable, so a cached copy never needs revalidation (zero requests on reopen).
 * - Least-recently-used, unpinned blobs are evicted above [maxBytes] down to 90% of it.
 * - Concurrent requests for the same blob share one download.
 */
class BlobStore(
    private val root: File,
    private val dao: BlobDao,
    private val api: MobileApi,
    private val clock: Clock,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val inFlight = ConcurrentHashMap<String, Mutex>()
    private val evictLock = Mutex()

    fun fileFor(sha256: String): File = File(File(root, sha256.take(2)), sha256)

    suspend fun cached(sha256: String): File? = withContext(Dispatchers.IO) {
        val file = fileFor(sha256)
        val entry = dao.get(sha256)
        if (entry != null && file.isFile) {
            dao.touch(sha256, clock.now().toEpochMilli())
            file
        } else {
            null
        }
    }

    suspend fun fetch(sha256: String, contentType: String? = null): BlobResult {
        cached(sha256)?.let { return BlobResult.Ready(it) }
        val lock = inFlight.getOrPut(sha256) { Mutex() }
        return try {
            lock.withLock {
                cached(sha256)?.let { return@withLock BlobResult.Ready(it) }
                download(sha256, contentType)
            }
        } finally {
            inFlight.remove(sha256, lock)
        }
    }

    private suspend fun download(sha256: String, contentType: String?): BlobResult = withContext(Dispatchers.IO) {
        val tmpDir = File(root, "tmp").apply { mkdirs() }
        val tmp = File(tmpDir, "$sha256.${UUID.randomUUID()}.part")
        when (val result = api.blob(sha256) { input, _ -> writeAndHash(input, tmp) }) {
            is ApiResult.Ok -> {
                val (digest, size) = result.value
                if (digest != sha256) {
                    tmp.delete()
                    BlobResult.Failed(ApiError(ApiError.CODE_INTEGRITY, retryable = true))
                } else {
                    val target = fileFor(sha256)
                    target.parentFile?.mkdirs()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    dao.upsert(BlobEntity(sha256, size, contentType, clock.now().toEpochMilli(), pinned = false))
                    evictIfNeeded(protect = setOf(sha256))
                    BlobResult.Ready(target)
                }
            }
            is ApiResult.Err -> {
                tmp.delete()
                BlobResult.Failed(result.error)
            }
            ApiResult.NotModified -> {
                tmp.delete()
                BlobResult.Failed(ApiError.badResponse(304))
            }
        }
    }

    /** Stores locally produced bytes (offline sample pages, exported PDFs). Returns the SHA-256. */
    suspend fun put(bytes: ByteArray, contentType: String?, pinned: Boolean): String = withContext(Dispatchers.IO) {
        val sha = sha256Hex(bytes)
        val target = fileFor(sha)
        target.parentFile?.mkdirs()
        if (!target.isFile || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
        val existing = dao.get(sha)
        dao.upsert(
            BlobEntity(
                sha256 = sha,
                bytes = bytes.size.toLong(),
                contentType = contentType,
                lastAccessAt = clock.now().toEpochMilli(),
                pinned = pinned || (existing?.pinned ?: false),
            ),
        )
        if (!pinned) evictIfNeeded(protect = setOf(sha))
        sha
    }

    suspend fun evictIfNeeded(protect: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        evictLock.withLock {
            var total = dao.totalBytes()
            if (total <= maxBytes) return@withLock
            val target = (maxBytes * LOW_WATERMARK).toLong()
            while (total > target) {
                val batch = dao.leastRecentlyUsed(EVICTION_BATCH).filterNot { it.sha256 in protect }
                if (batch.isEmpty()) break
                for (entry in batch) {
                    if (total <= target) break
                    fileFor(entry.sha256).delete()
                    dao.delete(entry.sha256)
                    total -= entry.bytes
                }
            }
        }
    }

    /** Removes downloaded (re-fetchable) files but keeps pinned sample pages. */
    suspend fun clearUnpinned() = withContext(Dispatchers.IO) {
        evictLock.withLock {
            for (entry in dao.unpinned()) {
                fileFor(entry.sha256).delete()
                dao.delete(entry.sha256)
            }
        }
    }

    /** Deletes every cached file. The index is cleared together with the database on sign-out. */
    suspend fun deleteAllFiles() = withContext(Dispatchers.IO) {
        evictLock.withLock { root.deleteRecursively() }
    }

    suspend fun verify(sha256: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileFor(sha256)
        file.isFile && file.inputStream().use { sha256Hex(it) } == sha256
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 200L * 1024 * 1024
        private const val LOW_WATERMARK = 0.9
        private const val EVICTION_BATCH = 64

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().toHex()
        }

        /** Copies [input] to [target] while hashing; returns (sha256 hex, byte count). */
        fun writeAndHash(input: InputStream, target: File): Pair<String, Long> {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            target.outputStream().buffered().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    total += read
                }
            }
            return digest.digest().toHex() to total
        }

        private fun ByteArray.toHex(): String {
            val chars = CharArray(size * 2)
            forEachIndexed { i, b ->
                val v = b.toInt() and 0xff
                chars[i * 2] = HEX[v ushr 4]
                chars[i * 2 + 1] = HEX[v and 0x0f]
            }
            return String(chars)
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
