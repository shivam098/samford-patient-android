package com.samford.patient.data

import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.DocumentEntity
import com.samford.patient.core.db.DocumentVersionEntity
import com.samford.patient.core.db.FeedItemEntity
import com.samford.patient.core.db.LabResultEntity
import com.samford.patient.core.db.PageEntity
import com.samford.patient.core.db.PendingAckEntity
import com.samford.patient.core.db.ProfileEntity
import com.samford.patient.core.db.RecipientEntity
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.db.VisitEntity
import com.samford.patient.core.network.AllergyDto
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.HintsDto
import com.samford.patient.core.network.SamfordJson
import com.samford.patient.core.settings.SettingsStore
import com.samford.patient.core.sync.SyncEngine
import com.samford.patient.core.time.Clock
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

data class VisitRecord(
    val visit: VisitEntity,
    val documents: List<DocumentEntity>,
    val recipients: Map<String, List<RecipientEntity>>,
    val labResults: List<LabResultEntity>,
)

data class DocumentDetail(
    val document: DocumentEntity,
    val versions: List<DocumentVersionEntity>,
    val visit: VisitEntity?,
    val recipients: List<RecipientEntity>,
) {
    val isAmended: Boolean get() = document.currentVersion > 1
    val current: DocumentVersionEntity? get() = versions.firstOrNull { it.version == document.currentVersion }
    val hints: HintsDto? get() = document.hintsJson?.let { runCatching { SamfordJson.decodeFromString(HintsDto.serializer(), it) }.getOrNull() }
}

/** Document types in the order a visit lists them. */
object DocumentKinds {
    const val VISIT_SUMMARY = "VISIT_SUMMARY"
    const val PRESCRIPTION = "PRESCRIPTION"
    const val INVESTIGATIONS = "INVESTIGATIONS"
    const val ADVICE_REFERRAL = "ADVICE_REFERRAL"
    val order = listOf(VISIT_SUMMARY, PRESCRIPTION, INVESTIGATIONS, ADVICE_REFERRAL)
    fun rank(kind: String) = order.indexOf(kind).let { if (it < 0) order.size else it }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordsRepository(
    private val db: SamfordDatabase,
    private val engine: SyncEngine,
    private val blobs: BlobStore,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    private val dao = db.records()

    val profiles: Flow<List<ProfileEntity>> = dao.observeProfiles()

    /** The chosen family profile, falling back to the first linked profile. */
    val activeProfile: Flow<ProfileEntity?> =
        combine(profiles, settings.settings.map { it.activeProfileId }.distinctUntilChanged()) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.distinctUntilChanged()

    val lastUpdated: Flow<Long?> = db.sync().observeLastSuccess()

    val cacheBytes: Flow<Long> = db.blobs().observeTotalBytes()

    suspend fun setActiveProfile(id: String) = settings.setActiveProfile(id)

    fun feed(profileId: String): Flow<List<FeedItemEntity>> = dao.observeFeed(profileId)

    fun unreadCount(profileId: String): Flow<Int> = dao.observeUnreadCount(profileId)

    suspend fun markFeedRead(profileId: String) = dao.markFeedRead(profileId)

    fun timeline(profileId: String): Flow<List<VisitRecord>> = combine(
        dao.observeVisits(profileId),
        dao.observeDocumentsForProfile(profileId),
        dao.observeRecipientsForProfile(profileId),
        dao.observeLabResults(profileId),
    ) { visits, docs, recipients, results ->
        val docsByVisit = docs.groupBy { it.visitId }
        val recipientsByDoc = recipients.groupBy { it.documentId }
        val visitByDoc = docs.associate { it.id to it.visitId }
        val resultsByVisit = results.groupBy { r -> r.documentId?.let { visitByDoc[it] } }
        visits
            .filter { v -> docsByVisit[v.id].orEmpty().isNotEmpty() || v.status !in HIDDEN_EMPTY_STATUSES }
            .map { v ->
                val visitDocs = docsByVisit[v.id].orEmpty().sortedBy { DocumentKinds.rank(it.kind) }
                VisitRecord(
                    visit = v,
                    documents = visitDocs,
                    recipients = visitDocs.associate { it.id to recipientsByDoc[it.id].orEmpty() },
                    labResults = resultsByVisit[v.id].orEmpty(),
                )
            }
    }

    /** Released results that could not be tied to a visit (for example ordered outside an OPD visit). */
    fun unlinkedResults(profileId: String): Flow<List<LabResultEntity>> =
        combine(dao.observeLabResults(profileId), dao.observeDocumentsForProfile(profileId)) { results, docs ->
            val ids = docs.map { it.id }.toSet()
            results.filter { it.documentId == null || it.documentId !in ids }
        }

    fun visit(visitId: String): Flow<VisitRecord?> = dao.observeVisit(visitId).flatMapLatest { visit ->
        if (visit == null) flowOf(null) else timeline(visit.profileId).map { list -> list.firstOrNull { it.visit.id == visitId } }
    }

    fun document(documentId: String): Flow<DocumentDetail?> =
        combine(dao.observeDocument(documentId), dao.observeVersions(documentId), dao.observeRecipients(documentId)) { doc, versions, recipients ->
            Triple(doc, versions, recipients)
        }.flatMapLatest { (doc, versions, recipients) ->
            if (doc == null) flowOf(null) else dao.observeVisit(doc.visitId).map { DocumentDetail(doc, versions, it, recipients) }
        }

    fun pages(documentId: String, version: Int): Flow<List<PageEntity>> = dao.observePages(documentId, version)

    fun firstPages(visitId: String): Flow<List<PageEntity>> = dao.observeFirstPagesForVisit(visitId)

    fun labResult(id: String): Flow<LabResultEntity?> = dao.observeLabResult(id)

    /** Fetches document detail when a version's pages are missing locally (rare: the record carries every version). */
    suspend fun ensureDocumentDetail(documentId: String, version: Int): ApiError? {
        val doc = dao.document(documentId) ?: return null
        if (dao.pages(documentId, version).isNotEmpty()) return null
        if (settings.current().sampleMode) return null
        return engine.refreshDocument(doc.profileId, documentId)
    }

    suspend fun pageFile(page: PageEntity): BlobResult = blobs.fetch(page.sha256, "image/png")

    suspend fun blobFile(sha256: String, contentType: String?): BlobResult = blobs.fetch(sha256, contentType)

    suspend fun cachedFile(sha256: String): File? = blobs.cached(sha256)

    /** Records locally that this version was seen, and queues the acknowledgement for the hospital. */
    suspend fun markViewed(documentId: String, version: Int) {
        val doc = dao.document(documentId) ?: return
        if (doc.viewedVersion >= version) return
        dao.markViewed(documentId, version)
        if (!settings.current().sampleMode) {
            db.acks().insert(PendingAckEntity(documentId, version, clock.now().toEpochMilli(), UUID.randomUUID().toString()))
        }
    }

    suspend fun clearDownloadedFiles() = blobs.clearUnpinned()

    companion object {
        private val HIDDEN_EMPTY_STATUSES = setOf("CANCELLED", "MISSED")

        fun allergies(profile: ProfileEntity?): List<AllergyDto> =
            profile?.allergiesJson?.let {
                runCatching { SamfordJson.decodeFromString(ListSerializer(AllergyDto.serializer()), it) }.getOrNull()
            }.orEmpty()
    }
}
