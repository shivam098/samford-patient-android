package com.samford.patient.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.patient.BuildConfig
import com.samford.patient.R
import com.samford.patient.core.security.PinHasher
import com.samford.patient.core.settings.AppSettings
import com.samford.patient.core.settings.LocaleStore
import com.samford.patient.ui.common.ElevatedSection
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.LocalSampleMode
import com.samford.patient.ui.common.ProfileRow
import com.samford.patient.ui.common.SampleBanner
import com.samford.patient.ui.common.SectionTitle
import com.samford.patient.ui.common.formatDateTime
import com.samford.patient.ui.signin.ServerStatusCard
import com.samford.patient.ui.theme.SampleRed
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onServerSettings: () -> Unit, onLanguageChanged: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val session by container.tokens.session.collectAsStateWithLifecycle(initialValue = null)
    val profiles by container.records.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val server by container.serverDirectory.state.collectAsStateWithLifecycle()
    val cacheBytes by container.records.cacheBytes.collectAsStateWithLifecycle(initialValue = 0L)
    val lastUpdated by container.records.lastUpdated.collectAsStateWithLifecycle(initialValue = null)
    val sample = LocalSampleMode.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(LocaleStore.language(context)) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (sample) SampleBanner()
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.settings_account))
                ElevatedSection {
                    Text(
                        if (sample) stringResource(R.string.settings_sample_account)
                        else stringResource(R.string.settings_signed_in_as, session?.mobileMasked ?: "—"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (profiles.isNotEmpty()) {
                        Text(stringResource(R.string.settings_profiles_linked), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        profiles.forEach { ProfileRow(it, Modifier.padding(top = 8.dp)) }
                    }
                }

                SectionTitle(stringResource(R.string.settings_language))
                ElevatedSection {
                    Column(Modifier.selectableGroup()) {
                        listOf("" to R.string.settings_language_phone, "en" to R.string.settings_language_english, "hi" to R.string.settings_language_hindi)
                            .forEach { (code, label) ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = language == code,
                                            role = Role.RadioButton,
                                            onClick = {
                                                if (language != code) {
                                                    language = code
                                                    LocaleStore.setLanguage(context, code)
                                                    onLanguageChanged()
                                                }
                                            },
                                        )
                                        .padding(vertical = 6.dp)
                                        .testTag("language_${code.ifEmpty { "phone" }}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = language == code, onClick = null)
                                    Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                    }
                }

                SectionTitle(stringResource(R.string.server_title))
                ServerStatusCard(server, onServerSettings)

                SectionTitle(stringResource(R.string.settings_notifications))
                ElevatedSection {
                    SwitchRow(
                        title = stringResource(R.string.settings_notifications_switch),
                        checked = settings.notificationsEnabled,
                        enabled = !sample,
                        tag = "notifications_switch",
                    ) { scope.launch { container.settings.setNotifications(it) } }
                    Text(stringResource(R.string.settings_notifications_explainer), style = MaterialTheme.typography.bodySmall)
                }

                SectionTitle(stringResource(R.string.settings_offline))
                ElevatedSection {
                    SwitchRow(
                        title = stringResource(R.string.settings_auto_download),
                        checked = settings.autoDownloadOnWifi,
                        enabled = !sample,
                        tag = "auto_download_switch",
                    ) { scope.launch { container.settings.setAutoDownload(it) } }
                    Text(
                        stringResource(
                            R.string.settings_storage_used,
                            String.format(Locale.ROOT, "%.1f", cacheBytes / 1048576.0),
                            (container.blobStore.maxBytes / 1048576).toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!sample) {
                        Text(
                            lastUpdated?.let { stringResource(R.string.home_updated_at, formatDateTime(it)) }
                                ?: stringResource(R.string.home_never_updated),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { scope.launch { container.records.clearDownloadedFiles() } }) {
                            Text(stringResource(R.string.settings_clear_downloads))
                        }
                    }
                }

                SectionTitle(stringResource(R.string.settings_app_lock))
                ElevatedSection {
                    SwitchRow(
                        title = stringResource(R.string.settings_app_lock_switch),
                        checked = settings.appLockEnabled,
                        enabled = true,
                        tag = "app_lock_switch",
                    ) { on ->
                        if (on) settingPin = true else scope.launch { container.appLock.disable() }
                    }
                    Text(stringResource(R.string.settings_app_lock_explainer), style = MaterialTheme.typography.bodySmall)
                }

                SectionTitle(stringResource(R.string.settings_about))
                ElevatedSection {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.demo_disclaimer), style = MaterialTheme.typography.bodySmall, color = SampleRed)
                    Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { confirmSignOut = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .testTag("sign_out"),
                ) {
                    Text(if (sample) stringResource(R.string.settings_leave_sample) else stringResource(R.string.settings_sign_out))
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(if (sample) stringResource(R.string.settings_leave_sample) else stringResource(R.string.settings_sign_out_confirm_title)) },
            text = { Text(if (sample) stringResource(R.string.settings_leave_sample_body) else stringResource(R.string.settings_sign_out_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        scope.launch {
                            container.signOut()
                            container.appLock.reset()
                        }
                    },
                    modifier = Modifier.testTag("confirm_sign_out"),
                ) { Text(stringResource(R.string.action_confirm_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (settingPin) {
        PinSetupDialog(
            onDismiss = { settingPin = false },
            onSave = { pin ->
                settingPin = false
                scope.launch { container.appLock.enable(pin) }
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
fun PinSetupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pin_setup_body))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(PinHasher.MAX_LENGTH); error = null },
                    label = { Text(stringResource(R.string.pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.testTag("pin_new"),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(PinHasher.MAX_LENGTH); error = null },
                    label = { Text(stringResource(R.string.pin_confirm_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.testTag("pin_confirm"),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = when {
                        !PinHasher.isValidPin(pin) -> R.string.pin_invalid
                        pin != confirm -> R.string.pin_mismatch
                        else -> null
                    }
                    if (error == null) onSave(pin)
                },
                modifier = Modifier.testTag("pin_save"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Finds the hosting activity, used for language changes that need a recreate. */
fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
