package com.samford.patient.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samford.patient.AppContainer
import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.db.PageEntity
import com.samford.patient.core.network.ApiError
import com.samford.patient.data.DocumentDetail
import com.samford.patient.data.ExportResult
import com.samford.patient.data.PdfLabels
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PageLoad {
    data object Loading : PageLoad
    data class Ready(val file: File) : PageLoad
    data class Failed(val error: ApiError) : PageLoad
}

sealed interface ExportUi {
    data object Idle : ExportUi
    data object Working : ExportUi
    data class Ready(val file: File, val forSave: Boolean) : ExportUi
    data class Missing(val pages: Int) : ExportUi
}

/** A version that has only a PDF and no page images: pages are rasterised on the phone. */
data class PdfFallback(val file: File, val sha256: String, val pageCount: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentViewerViewModel(
    private val container: AppContainer,
    private val documentId: String,
    initialVersion: Int,
) : ViewModel() {
    private val records = container.records

    val detail: StateFlow<DocumentDetail?> =
        records.document(documentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val requestedVersion = MutableStateFlow(initialVersion)

    /** The version on screen: the one chosen in version history, else the latest. */
    val version: StateFlow<Int> = combine(detail, requestedVersion) { d, v ->
        if (v > 0) v else d?.document?.currentVersion ?: 0
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialVersion)

    val pages: StateFlow<List<PageEntity>?> = version.flatMapLatest { v ->
        if (v <= 0) flowOf(null) else records.pages(documentId, v)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _detailError = MutableStateFlow<ApiError?>(null)
    val detailError: StateFlow<ApiError?> = _detailError.asStateFlow()

    private val _pdfFallback = MutableStateFlow<PdfFallback?>(null)
    val pdfFallback: StateFlow<PdfFallback?> = _pdfFallback.asStateFlow()

    private val _export = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val export: StateFlow<ExportUi> = _export.asStateFlow()

    init {
        viewModelScope.launch {
            val d = detail.filterNotNull().first()
            val v = if (initialVersion > 0) initialVersion else d.document.currentVersion
            _detailError.value = records.ensureDocumentDetail(documentId, v)
        }
    }

    fun selectVersion(version: Int) {
        requestedVersion.value = version
        _pdfFallback.value = null
        viewModelScope.launch {
            val v = if (version > 0) version else detail.value?.document?.currentVersion ?: return@launch
            _detailError.value = records.ensureDocumentDetail(documentId, v)
        }
    }

    fun showLatest() = selectVersion(0)

    suspend fun loadPage(page: PageEntity): PageLoad = when (val r = records.pageFile(page)) {
        is BlobResult.Ready -> PageLoad.Ready(r.file)
        is BlobResult.Failed -> PageLoad.Failed(r.error)
    }

    /** Used only when a version has a PDF but no page images. */
    fun loadPdfFallback() {
        val d = detail.value ?: return
        val v = d.versions.firstOrNull { it.version == version.value } ?: return
        val sha = v.pdfSha256 ?: return
        viewModelScope.launch {
            when (val r = records.blobFile(sha, "application/pdf")) {
                is BlobResult.Ready -> _pdfFallback.value = PdfFallback(r.file, sha, container.rasterizer.pageCount(r.file))
                is BlobResult.Failed -> _detailError.value = r.error
            }
        }
    }

    suspend fun renderPdfPage(index: Int): File? {
        val f = _pdfFallback.value ?: return null
        return container.rasterizer.render(f.file, f.sha256, index)
    }

    fun onVersionShown(version: Int) {
        val d = detail.value ?: return
        if (version == d.document.currentVersion) viewModelScope.launch { records.markViewed(documentId, version) }
    }

    fun exportPdf(title: String, labels: PdfLabels, forSave: Boolean) {
        val d = detail.value ?: return
        val v = d.versions.firstOrNull { it.version == version.value } ?: return
        val pageList = pages.value.orEmpty()
        _export.value = ExportUi.Working
        viewModelScope.launch {
            _export.value = when (val r = container.exporter.export(d.document, v, pageList, title, labels, allowDownload = true)) {
                is ExportResult.Ready -> ExportUi.Ready(r.file, forSave)
                is ExportResult.Missing -> ExportUi.Missing(r.pagesNotDownloaded)
            }
        }
    }

    fun exportHandled() {
        _export.value = ExportUi.Idle
    }
}
