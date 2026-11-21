package com.samford.patient.ui.labs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.patient.R
import com.samford.patient.core.blob.BlobResult
import com.samford.patient.ui.common.ElevatedSection
import com.samford.patient.ui.common.InfoBanner
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.LocalSampleMode
import com.samford.patient.ui.common.SampleBanner
import com.samford.patient.ui.common.StatusPill
import com.samford.patient.ui.common.formatDateTime
import com.samford.patient.ui.common.toMessage
import com.samford.patient.ui.records.resultFlag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabResultScreen(resultId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val flow = remember(resultId) { container.records.labResult(resultId) }
    val result by flow.collectAsStateWithLifecycle(initialValue = null)
    val sample = LocalSampleMode.current
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val openTitle = stringResource(R.string.lab_open_report)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lab_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (sample) SampleBanner()
            val r = result ?: return@Column
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ElevatedSection(Modifier.testTag("lab_result_card")) {
                    Text(r.testName, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                        Text(r.value, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                        r.unit?.let { Text(" $it", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp)) }
                    }
                    val (label, color) = resultFlag(r.flag)
                    StatusPill(label, color, Modifier.padding(top = 8.dp))
                    r.refRange?.let {
                        Text(stringResource(R.string.lab_reference_range, it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    r.reportedAt?.let {
                        Text(stringResource(R.string.lab_reported_at, formatDateTime(it)), style = MaterialTheme.typography.bodySmall)
                    }
                    r.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                }
                InfoBanner(stringResource(R.string.lab_explainer))
                r.reportPdfSha256?.let { sha ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            when (val blob = container.records.blobFile(sha, "application/pdf")) {
                                is BlobResult.Ready -> {
                                    val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
                                    val copy = File(shareDir, "Samford-lab-report-${r.id}.pdf")
                                    withContext(Dispatchers.IO) { blob.file.copyTo(copy, overwrite = true) }
                                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", copy)
                                    val view = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/pdf")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching { context.startActivity(Intent.createChooser(view, openTitle)) }
                                }
                                is BlobResult.Failed -> snackbar.showSnackbar(blob.error.toMessage(res))
                            }
                        }
                    }) { Text(openTitle) }
                }
            }
        }
    }
}
