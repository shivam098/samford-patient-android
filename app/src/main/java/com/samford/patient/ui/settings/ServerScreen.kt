package com.samford.patient.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.samford.patient.R
import com.samford.patient.core.discovery.ManualServerResult
import com.samford.patient.core.discovery.ServerDirectory
import com.samford.patient.core.discovery.ServerSource
import com.samford.patient.core.settings.AppSettings
import com.samford.patient.ui.common.InfoBanner
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.SectionTitle
import com.samford.patient.ui.common.WarningBanner
import com.samford.patient.ui.signin.ServerStatusCard
import kotlinx.coroutines.launch

/**
 * Server discovery and override: the automatic discovery document, a manually typed address, or a
 * QR code (scanned in the app, or with the phone camera via a samfordpatient:// link, which opens
 * this screen pre-filled for confirmation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server by container.serverDirectory.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val pending by container.pendingServerLink.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var discovery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var offerSaveAnyway by remember { mutableStateOf(false) }

    val msgSaved = stringResource(R.string.server_saved)
    val msgInvalid = stringResource(R.string.server_invalid_address)
    val msgHttps = stringResource(R.string.server_https_required)
    val msgUnreachable = stringResource(R.string.server_could_not_reach)
    val msgNotSamford = stringResource(R.string.server_qr_not_samford)
    val msgScanUnavailable = stringResource(R.string.server_scan_unavailable)
    val msgLinkReview = stringResource(R.string.server_link_review)

    fun describe(result: ManualServerResult?): String = when (result) {
        is ManualServerResult.Saved -> if (result.reachable) msgSaved else msgUnreachable
        ManualServerResult.InvalidAddress -> msgInvalid
        ManualServerResult.HttpsRequired -> msgHttps
        is ManualServerResult.Unreachable -> msgUnreachable
        null -> msgNotSamford
    }

    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        val target = ServerDirectory.parseQr(link)
        address = target?.apiRoot?.toString() ?: target?.discoveryUrl ?: ""
        if (target?.discoveryUrl != null && target.apiRoot == null) discovery = target.discoveryUrl
        message = msgLinkReview
        container.pendingServerLink.value = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings)) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerStatusCard(server, null)
            Text(
                when (server.source) {
                    ServerSource.MANUAL -> stringResource(R.string.server_source_manual)
                    ServerSource.DISCOVERY -> stringResource(R.string.server_source_discovery)
                    ServerSource.LAST_GOOD -> stringResource(R.string.server_source_last_good)
                    ServerSource.NONE -> stringResource(R.string.server_source_none)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        container.serverDirectory.refresh()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("check_server"),
            ) { Text(stringResource(R.string.server_check_again)) }

            message?.let { InfoBanner(it, Modifier.testTag("server_message")) }

            SectionTitle(stringResource(R.string.server_manual_title))
            Text(stringResource(R.string.server_manual_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it.trim()
                    offerSaveAnyway = false
                },
                label = { Text(stringResource(R.string.server_address_label)) },
                placeholder = { Text("https://example.trycloudflare.com/api/mobile/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_address"),
            )
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val result = container.serverDirectory.setManual(address)
                        message = describe(result)
                        offerSaveAnyway = result is ManualServerResult.Unreachable
                        busy = false
                    }
                },
                enabled = address.isNotBlank() && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connect_server"),
            ) {
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.server_connect))
            }
            if (offerSaveAnyway) {
                TextButton(onClick = {
                    scope.launch {
                        message = describe(container.serverDirectory.setManual(address, saveEvenIfUnreachable = true))
                        offerSaveAnyway = false
                    }
                }) { Text(stringResource(R.string.server_save_anyway)) }
            }
            OutlinedButton(
                onClick = {
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue.orEmpty()
                            scope.launch { message = describe(container.serverDirectory.applyQr(raw)) }
                        }
                        .addOnFailureListener { message = msgScanUnavailable }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.server_scan_qr)) }
            if (settings.serverOverride != null) {
                TextButton(onClick = {
                    scope.launch {
                        container.serverDirectory.clearManual()
                        message = null
                    }
                }) { Text(stringResource(R.string.server_use_automatic)) }
            }

            SectionTitle(stringResource(R.string.server_discovery_title))
            Text(
                stringResource(R.string.server_discovery_help, settings.discoveryUrlOverride ?: server.discoveryUrl),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = discovery,
                onValueChange = { discovery = it.trim() },
                label = { Text(stringResource(R.string.server_discovery_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row2(
                onSave = { scope.launch { container.serverDirectory.setDiscoveryUrl(discovery) } },
                onReset = {
                    discovery = ""
                    scope.launch { container.serverDirectory.setDiscoveryUrl(null) }
                },
                saveEnabled = discovery.startsWith("https://") || discovery.startsWith("http://"),
            )
            WarningBanner(stringResource(R.string.demo_disclaimer))
        }
    }
}

@Composable
private fun Row2(onSave: () -> Unit, onReset: () -> Unit, saveEnabled: Boolean) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSave, enabled = saveEnabled) { Text(stringResource(R.string.server_discovery_save)) }
        TextButton(onClick = onReset) { Text(stringResource(R.string.server_discovery_reset)) }
    }
}
