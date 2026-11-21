package com.samford.patient.ui.viewer

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samford.patient.R
import com.samford.patient.core.db.DocumentVersionEntity
import com.samford.patient.core.db.PageEntity
import com.samford.patient.data.DocumentDetail
import com.samford.patient.data.PdfLabels
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.LocalSampleMode
import com.samford.patient.ui.common.SampleBanner
import com.samford.patient.ui.common.SectionTitle
import com.samford.patient.ui.common.formatDate
import com.samford.patient.ui.common.formatDateTime
import com.samford.patient.ui.common.toMessage
import com.samford.patient.ui.home.documentKindLabel
import com.samford.patient.ui.records.RecipientRow
import com.samford.patient.ui.theme.Amber
import com.samford.patient.ui.theme.SampleRed
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(documentId: String, initialVersion: Int, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: DocumentViewerViewModel = viewModel(key = "doc-$documentId") { DocumentViewerViewModel(container, documentId, initialVersion) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val detailError by vm.detailError.collectAsStateWithLifecycle()
    val pdfFallback by vm.pdfFallback.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val sample = LocalSampleMode.current
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showVersions by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val d = detail
    val title = d?.let { documentKindLabel(it.document.kind) } ?: stringResource(R.string.kind_other)
    val labels = PdfLabels(stringResource(R.string.pdf_watermark), stringResource(R.string.pdf_footer))
    val missingText = stringResource(R.string.viewer_export_missing)
    val savedText = stringResource(R.string.viewer_saved)
    val shareTitle = stringResource(R.string.viewer_share_title)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val ready = vm.export.value as? ExportUi.Ready
        if (uri != null && ready != null) {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> ready.file.inputStream().use { it.copyTo(out) } }
                }
                snackbar.showSnackbar(savedText)
            }
        }
        vm.exportHandled()
    }

    LaunchedEffect(export) {
        when (val e = export) {
            is ExportUi.Ready -> if (e.forSave) {
                saveLauncher.launch(e.file.name)
            } else {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", e.file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, shareTitle))
                vm.exportHandled()
            }
            is ExportUi.Missing -> {
                snackbar.showSnackbar(missingText)
                vm.exportHandled()
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        d?.let {
                            Text(
                                listOfNotNull(it.visit?.dateMillis?.let { ms -> formatDate(ms) }, it.document.authorName).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showVersions = true }, modifier = Modifier.testTag("versions_button")) {
                        Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.viewer_versions))
                    }
                    IconButton(
                        onClick = { vm.exportPdf(title, labels, forSave = false) },
                        enabled = export !is ExportUi.Working,
                        modifier = Modifier.testTag("share_button"),
                    ) {
                        if (export is ExportUi.Working) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.viewer_share))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("viewer_menu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_details)) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showInfo = true
                                },
                                modifier = Modifier.testTag("details_item"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_save_pdf)) },
                                onClick = {
                                    menuOpen = false
                                    vm.exportPdf(title, labels, forSave = true)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (sample) SampleBanner()
            if (d != null) VersionBanner(d, version, onShowVersions = { showVersions = true }, onShowLatest = vm::showLatest)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF2B3134)),
            ) {
                val list = pages
                when {
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    list.isNotEmpty() -> PagePager(list, version, vm)
                    pdfFallback != null -> PdfPager(pdfFallback!!.pageCount, version, vm)
                    detailError != null -> ErrorPanel(detailError!!.toMessage(res), Modifier.align(Alignment.Center)) {
                        vm.selectVersion(version)
                    }
                    d?.versions?.firstOrNull { it.version == version }?.pdfSha256 != null -> {
                        LaunchedEffect(version) { vm.loadPdfFallback() }
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    else -> Text(
                        stringResource(R.string.viewer_no_pages),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                }
            }
        }
    }

    if (showVersions && d != null) {
        ModalBottomSheet(onDismissRequest = { showVersions = false }) {
            VersionHistory(d, version) {
                showVersions = false
                vm.selectVersion(it)
            }
        }
    }
    if (showInfo && d != null) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            DocumentInfo(d, version)
        }
    }
}

@Composable
private fun VersionBanner(d: DocumentDetail, version: Int, onShowVersions: () -> Unit, onShowLatest: () -> Unit) {
    val current = d.current
    val shown = d.versions.firstOrNull { it.version == version }
    when {
        version < d.document.currentVersion && shown != null -> CompactBanner(
            text = stringResource(
                if (version == 1) R.string.viewer_viewing_original else R.string.viewer_viewing_older,
                version,
                formatDateTime(shown.signedAt ?: shown.createdAt),
            ),
            tone = SampleRed.copy(alpha = 0.12f),
            actionLabel = stringResource(R.string.viewer_view_latest),
            onAction = onShowLatest,
            tag = "older_version_banner",
        )
        d.isAmended && current != null -> CompactBanner(
            text = buildString {
                append(
                    stringResource(
                        R.string.viewer_amended_banner,
                        formatDateTime(current.signedAt ?: current.createdAt),
                        current.authorName ?: d.document.authorName ?: stringResource(R.string.your_doctor),
                    ),
                )
                current.reason?.takeIf { it.isNotBlank() }?.let { append(" ").append(stringResource(R.string.viewer_amended_reason, it)) }
            },
            tone = Amber.copy(alpha = 0.16f),
            actionLabel = stringResource(R.string.viewer_see_versions),
            onAction = onShowVersions,
            tag = "amended_banner",
        )
    }
}

/** A short banner (at most three lines) so the handwriting keeps most of a small screen. */
@Composable
private fun CompactBanner(text: String, tone: Color, actionLabel: String, onAction: () -> Unit, tag: String) {
    Surface(
        color = tone,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onAction),
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun PagePager(pages: List<PageEntity>, version: Int, vm: DocumentViewerViewModel) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    LaunchedEffect(version, pagerState.currentPage) {
        delay(1_000)
        vm.onVersionShown(version)
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { pages[it].sha256 + ":" + it },
            modifier = Modifier
                .fillMaxSize()
                .testTag("document_pager"),
        ) { index ->
            val page = pages[index]
            var attempt by remember(page.sha256) { mutableIntStateOf(0) }
            val load by produceState<PageLoad>(PageLoad.Loading, page.sha256, attempt) { value = vm.loadPage(page) }
            PageContent(load, index, pages.size) { attempt++ }
        }
        PageIndicator(pagerState.currentPage, pages.size, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PdfPager(count: Int, version: Int, vm: DocumentViewerViewModel) {
    val pagerState = rememberPagerState(pageCount = { count })
    LaunchedEffect(version, pagerState.currentPage) {
        delay(1_000)
        vm.onVersionShown(version)
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag("document_pager")) { index ->
            val file by produceState<File?>(null, index) { value = vm.renderPdfPage(index) }
            PageContent(file?.let { PageLoad.Ready(it) } ?: PageLoad.Loading, index, count) {}
        }
        PageIndicator(pagerState.currentPage, count, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PageContent(load: PageLoad, index: Int, count: Int, onRetry: () -> Unit) {
    val description = stringResource(R.string.viewer_page_description, index + 1, count)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (load) {
            PageLoad.Loading -> CircularProgressIndicator(color = Color.White)
            is PageLoad.Ready -> ZoomableAsyncImage(
                model = load.file,
                contentDescription = description,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("page_image"),
            )
            is PageLoad.Failed -> ErrorPanel(load.error.toMessage(LocalResources.current), onRetry = onRetry)
        }
    }
}

@Composable
private fun PageIndicator(current: Int, count: Int, modifier: Modifier) {
    val label = stringResource(R.string.viewer_page_of, current + 1, count)
    Column(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color(0x99000000), MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = label }
                .testTag("page_indicator"),
        )
        Spacer(Modifier.height(4.dp))
        // A short tip that disappears so it never covers the handwriting for long.
        var showHint by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(HINT_VISIBLE_MS)
            showHint = false
        }
        if (showHint) {
            Text(
                stringResource(R.string.viewer_zoom_hint),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .background(Color(0x99000000), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private const val HINT_VISIBLE_MS = 5_000L

@Composable
private fun ErrorPanel(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun VersionHistory(d: DocumentDetail, shown: Int, onSelect: (Int) -> Unit) {
    LazyColumn(Modifier.padding(bottom = 24.dp).testTag("version_history")) {
        item {
            Text(
                stringResource(R.string.viewer_versions_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.viewer_versions_explainer),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        items(d.versions, key = { it.version }) { v -> VersionRow(v, d, v.version == shown) { onSelect(v.version) } }
    }
}

@Composable
private fun VersionRow(v: DocumentVersionEntity, d: DocumentDetail, selected: Boolean, onClick: () -> Unit) {
    val label = when {
        v.version == 1 -> stringResource(R.string.viewer_version_original)
        else -> stringResource(R.string.viewer_version_amended)
    }
    ListItem(
        headlineContent = {
            Text(
                stringResource(R.string.viewer_version_title, v.version, label) +
                    if (v.version == d.document.currentVersion) " · " + stringResource(R.string.viewer_version_latest) else "",
            )
        },
        supportingContent = {
            Column {
                Text(formatDateTime(v.signedAt ?: v.createdAt) + (v.authorName?.let { " · $it" } ?: ""))
                v.reason?.let { Text(stringResource(R.string.viewer_amended_reason, it)) }
                v.recordHash?.let { Text(stringResource(R.string.viewer_record_hash, it.take(16)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
            }
        },
        tonalElevation = if (selected) 4.dp else 0.dp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("version_${v.version}"),
    )
    HorizontalDivider()
}

@Composable
private fun DocumentInfo(d: DocumentDetail, version: Int) {
    val hints = d.hints
    LazyColumn(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        item { SectionTitle(stringResource(R.string.viewer_sent_to_title)) }
        val others = d.recipients.filter { it.kind != "PATIENT" }
        if (others.isEmpty()) {
            item { Text(stringResource(R.string.viewer_sent_to_none), style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(others, key = { it.recipientKey }) { RecipientRow(it, showNote = true) }
        }
        if (hints != null && (hints.medicines.isNotEmpty() || hints.tests.isNotEmpty() || hints.diagnosis != null || hints.notes != null)) {
            item {
                SectionTitle(stringResource(R.string.viewer_typed_title))
                Text(
                    stringResource(R.string.viewer_typed_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            hints.diagnosis?.let { item { Text(stringResource(R.string.viewer_typed_diagnosis, it), modifier = Modifier.padding(top = 8.dp)) } }
            items(hints.medicines) { m ->
                val detail = listOfNotNull(m.strength, m.dose, m.frequency, m.durationDays?.let { days -> pluralStringResource(R.plurals.viewer_typed_days, days, days) }, m.notes)
                Column(Modifier.padding(top = 8.dp)) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge)
                    if (detail.isNotEmpty()) Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(hints.tests) { t -> Text("• ${t.name}", modifier = Modifier.padding(top = 6.dp)) }
            hints.notes?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) } }
        }
        item {
            SectionTitle(stringResource(R.string.viewer_integrity_title))
            val v = d.versions.firstOrNull { it.version == version }
            Text(stringResource(R.string.viewer_integrity_body), style = MaterialTheme.typography.bodySmall)
            v?.recordHash?.let {
                Text(stringResource(R.string.viewer_record_hash, it), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
