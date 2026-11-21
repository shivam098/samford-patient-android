package com.samford.patient.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordsDao {
    // Profiles
    @Upsert
    suspend fun upsertProfiles(profiles: List<ProfileEntity>)

    @Query("DELETE FROM profiles WHERE id NOT IN (:keep)")
    suspend fun deleteProfilesNotIn(keep: List<String>)

    @Query("SELECT * FROM profiles ORDER BY sortOrder, displayName")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY sortOrder, displayName")
    suspend fun profiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun profile(id: String): ProfileEntity?

    // Visits
    @Upsert
    suspend fun upsertVisits(visits: List<VisitEntity>)

    @Query("SELECT * FROM visits WHERE profileId = :profileId ORDER BY dateMillis DESC")
    fun observeVisits(profileId: String): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE id = :id")
    fun observeVisit(id: String): Flow<VisitEntity?>

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun visit(id: String): VisitEntity?

    @Query("DELETE FROM visits WHERE profileId = :profileId AND id NOT IN (:keep)")
    suspend fun deleteVisitsNotIn(profileId: String, keep: List<String>)

    // Documents
    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE profileId = :profileId ORDER BY updatedAt DESC")
    fun observeDocumentsForProfile(profileId: String): Flow<List<DocumentEntity>>

    @Query("SELECT id FROM documents WHERE profileId = :profileId")
    suspend fun documentIds(profileId: String): List<String>

    @Query("UPDATE documents SET viewedVersion = MAX(viewedVersion, :version) WHERE id = :id")
    suspend fun markViewed(id: String, version: Int)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Upsert
    suspend fun upsertVersions(versions: List<DocumentVersionEntity>)

    @Query("SELECT * FROM document_versions WHERE documentId = :documentId ORDER BY version DESC")
    fun observeVersions(documentId: String): Flow<List<DocumentVersionEntity>>

    @Query("SELECT * FROM document_versions WHERE documentId = :documentId ORDER BY version DESC")
    suspend fun versions(documentId: String): List<DocumentVersionEntity>

    @Query("DELETE FROM document_versions WHERE documentId = :documentId")
    suspend fun deleteVersions(documentId: String)

    @Upsert
    suspend fun upsertPages(pages: List<PageEntity>)

    @Query("DELETE FROM pages WHERE documentId = :documentId AND version = :version")
    suspend fun deletePages(documentId: String, version: Int)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deleteAllPages(documentId: String)

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND version = :version ORDER BY pageIndex")
    fun observePages(documentId: String, version: Int): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId AND version = :version ORDER BY pageIndex")
    suspend fun pages(documentId: String, version: Int): List<PageEntity>

    @Query(
        "SELECT p.* FROM pages p JOIN documents d ON d.id = p.documentId AND d.currentVersion = p.version " +
            "WHERE d.updatedAt >= :since ORDER BY d.updatedAt DESC, p.pageIndex",
    )
    suspend fun currentPagesUpdatedSince(since: Long): List<PageEntity>

    @Query(
        "SELECT p.* FROM pages p JOIN documents d ON d.id = p.documentId AND d.currentVersion = p.version " +
            "WHERE d.visitId = :visitId AND p.pageIndex = 0",
    )
    fun observeFirstPagesForVisit(visitId: String): Flow<List<PageEntity>>

    // Recipients
    @Upsert
    suspend fun upsertRecipients(recipients: List<RecipientEntity>)

    @Query("DELETE FROM recipients WHERE documentId = :documentId")
    suspend fun deleteRecipients(documentId: String)

    @Query("SELECT r.* FROM recipients r JOIN documents d ON d.id = r.documentId WHERE d.profileId = :profileId")
    fun observeRecipientsForProfile(profileId: String): Flow<List<RecipientEntity>>

    @Query("SELECT * FROM recipients WHERE documentId = :documentId ORDER BY kind, name")
    fun observeRecipients(documentId: String): Flow<List<RecipientEntity>>

    @Query("SELECT * FROM recipients WHERE documentId = :documentId")
    suspend fun recipients(documentId: String): List<RecipientEntity>

    // Lab results
    @Upsert
    suspend fun upsertLabResults(results: List<LabResultEntity>)

    @Query("DELETE FROM lab_results WHERE profileId = :profileId AND id NOT IN (:keep)")
    suspend fun deleteLabResultsNotIn(profileId: String, keep: List<String>)

    @Query("SELECT * FROM lab_results WHERE id = :id")
    suspend fun labResult(id: String): LabResultEntity?

    @Query("SELECT * FROM lab_results WHERE id = :id")
    fun observeLabResult(id: String): Flow<LabResultEntity?>

    @Query("SELECT * FROM lab_results WHERE profileId = :profileId ORDER BY reportedAt DESC")
    fun observeLabResults(profileId: String): Flow<List<LabResultEntity>>

    // Home feed
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedItems(items: List<FeedItemEntity>): List<Long>

    @Query("SELECT * FROM feed_items WHERE profileId = :profileId ORDER BY occurredAt DESC LIMIT :limit")
    fun observeFeed(profileId: String, limit: Int = 100): Flow<List<FeedItemEntity>>

    @Query("SELECT * FROM feed_items WHERE notified = 0 ORDER BY occurredAt DESC")
    suspend fun unnotifiedFeedItems(): List<FeedItemEntity>

    @Query("UPDATE feed_items SET notified = 1 WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<String>)

    @Query("UPDATE feed_items SET notified = 1 WHERE notified = 0")
    suspend fun markAllNotified()

    @Query("UPDATE feed_items SET read = 1 WHERE profileId = :profileId")
    suspend fun markFeedRead(profileId: String)

    @Query("SELECT COUNT(*) FROM feed_items WHERE profileId = :profileId AND read = 0")
    fun observeUnreadCount(profileId: String): Flow<Int>
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_state WHERE scope = :scope")
    suspend fun get(scope: String): SyncStateEntity?

    @Query("SELECT MAX(lastSuccessAt) FROM sync_state")
    fun observeLastSuccess(): Flow<Long?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state")
    suspend fun all(): List<SyncStateEntity>
}

@Dao
interface BlobDao {
    @Query("SELECT * FROM blobs WHERE sha256 = :sha256")
    suspend fun get(sha256: String): BlobEntity?

    @Upsert
    suspend fun upsert(blob: BlobEntity)

    @Query("UPDATE blobs SET lastAccessAt = :at WHERE sha256 = :sha256")
    suspend fun touch(sha256: String, at: Long)

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM blobs")
    suspend fun totalBytes(): Long

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM blobs")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT * FROM blobs WHERE pinned = 0 ORDER BY lastAccessAt ASC LIMIT :limit")
    suspend fun leastRecentlyUsed(limit: Int): List<BlobEntity>

    @Query("SELECT * FROM blobs WHERE pinned = 0")
    suspend fun unpinned(): List<BlobEntity>

    @Query("DELETE FROM blobs WHERE sha256 = :sha256")
    suspend fun delete(sha256: String)

    @Query("SELECT COUNT(*) FROM blobs")
    suspend fun count(): Int
}

@Dao
interface AckDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ack: PendingAckEntity)

    @Query("SELECT * FROM pending_acks ORDER BY viewedAt LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<PendingAckEntity>

    @Query("DELETE FROM pending_acks WHERE documentId = :documentId AND version = :version")
    suspend fun delete(documentId: String, version: Int)
}
