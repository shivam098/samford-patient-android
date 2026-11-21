package com.samford.patient.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Local, read-mostly mirror of what the hospital has released to this phone (one PatientRecord per
 * profile plus feed events). Rows are keyed by server ids, so applying the same snapshot twice is
 * harmless. Page images and PDFs are not stored here, only their SHA-256 content addresses.
 */

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val ageYears: Int?,
    val sex: String?,
    val uhid: String?,
    /** JSON array of AllergyDto from the latest record. */
    val allergiesJson: String?,
    val sortOrder: Int,
    val isSample: Boolean,
)

@Entity(tableName = "visits", indices = [Index("profileId")])
data class VisitEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    /** IST calendar date, YYYY-MM-DD. */
    val visitDate: String?,
    /** Sort/display instant: completion time, else IST midnight of [visitDate]. */
    val dateMillis: Long?,
    val completedAt: Long?,
    val doctorName: String?,
    val department: String?,
    val status: String?,
)

@Entity(tableName = "documents", indices = [Index("profileId"), Index("visitId")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val visitId: String,
    val profileId: String,
    /** VISIT_SUMMARY, PRESCRIPTION, INVESTIGATIONS, ADVICE_REFERRAL or OTHER. */
    val kind: String,
    val title: String?,
    val currentVersion: Int,
    val authorName: String?,
    val updatedAt: Long?,
    /** Typed hints JSON (unverified; the handwriting is authoritative). */
    val hintsJson: String?,
    /** Highest version the patient has opened on this phone (0 = never opened). */
    val viewedVersion: Int = 0,
)

@Entity(tableName = "document_versions", primaryKeys = ["documentId", "version"])
data class DocumentVersionEntity(
    val documentId: String,
    val version: Int,
    val createdAt: Long?,
    val signedAt: Long?,
    val authorName: String?,
    val title: String?,
    /** Addendum reason; null for the original. */
    val reason: String?,
    val pdfSha256: String?,
    val inkSha256: String?,
    val inkFormat: String?,
    val recordHash: String?,
)

@Entity(tableName = "pages", primaryKeys = ["documentId", "version", "pageIndex"])
data class PageEntity(
    val documentId: String,
    val version: Int,
    val pageIndex: Int,
    val sha256: String,
    val width: Int,
    val height: Int,
)

/** Who received the latest version (pharmacy, each lab, the patient) and how far they got. */
@Entity(tableName = "recipients", primaryKeys = ["documentId", "recipientKey"])
data class RecipientEntity(
    val documentId: String,
    /** kind + ":" + labId, so each lab has its own row. */
    val recipientKey: String,
    val kind: String,
    val labId: String?,
    val name: String?,
    val state: String,
    val version: Int,
    val updatedAt: Long?,
    val note: String?,
)

@Entity(tableName = "lab_results", indices = [Index("profileId"), Index("documentId")])
data class LabResultEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val documentId: String?,
    val labId: String?,
    val testName: String,
    val value: String,
    val unit: String?,
    val refRange: String?,
    /** NORMAL, ABNORMAL or CRITICAL. */
    val flag: String?,
    val reportedAt: Long?,
    val summary: String?,
    val reportPdfSha256: String?,
)

/** Home-screen events derived from the server feed. Ids are deterministic, so re-delivery never duplicates. */
@Entity(tableName = "feed_items", indices = [Index(value = ["profileId", "occurredAt"])])
data class FeedItemEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val kind: String,
    val occurredAt: Long,
    val refType: String,
    val refId: String,
    val actor: String?,
    val subject: String?,
    val notified: Boolean,
    val read: Boolean,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val scope: String,
    val cursor: String? = null,
    val etag: String? = null,
    /** Feed: true after a page with hasMore=false (only then is If-None-Match sent). Record: up to date. */
    val caughtUp: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val failureCount: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastErrorCode: String? = null,
)

/** Index of the bounded, content-addressed file cache. */
@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey val sha256: String,
    val bytes: Long,
    val contentType: String?,
    val lastAccessAt: Long,
    /** Pinned blobs (offline sample pages) are never evicted. */
    val pinned: Boolean,
)

/** "Patient viewed this version" acknowledgements waiting to be sent. */
@Entity(tableName = "pending_acks", primaryKeys = ["documentId", "version"])
data class PendingAckEntity(
    val documentId: String,
    val version: Int,
    val viewedAt: Long,
    /** Generated once, so a retry after a lost response replays instead of duplicating. */
    val idempotencyKey: String,
)
