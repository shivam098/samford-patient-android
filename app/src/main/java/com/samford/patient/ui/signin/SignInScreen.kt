package com.samford.patient.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samford.patient.R
import com.samford.patient.core.discovery.ServerState
import com.samford.patient.core.discovery.ServerStatus
import com.samford.patient.core.network.ApiError
import com.samford.patient.core.network.ApiRoot
import com.samford.patient.ui.common.InfoBanner
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.StatusPill
import com.samford.patient.ui.common.WarningBanner
import com.samford.patient.ui.common.toMessage
import com.samford.patient.ui.theme.Amber
import com.samford.patient.ui.theme.OkGreen
import com.samford.patient.ui.theme.SampleRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun WelcomeScreen(onSignIn: () -> Unit, onServerSettings: () -> Unit) {
    val container = LocalAppContainer.current
    val server by container.serverDirectory.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var preparingSample by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (server.status == ServerStatus.UNKNOWN && container.backgroundEnabled) container.serverDirectory.refresh()
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.welcome_tagline), style = MaterialTheme.typography.bodyLarge)
            ServerStatusCard(server, onServerSettings)
            Button(
                onClick = onSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sign_in"),
            ) { Text(stringResource(R.string.welcome_sign_in)) }
            OutlinedButton(
                onClick = {
                    preparingSample = true
                    scope.launch {
                        try {
                            container.enterSampleMode()
                        } finally {
                            preparingSample = false
                        }
                    }
                },
                enabled = !preparingSample,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("explore_sample"),
            ) {
                if (preparingSample) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.welcome_preparing_sample))
                } else {
                    Text(stringResource(R.string.welcome_explore_sample))
                }
            }
            Text(
                stringResource(R.string.welcome_sample_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.demo_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = SampleRed,
            )
        }
    }
}

@Composable
fun ServerStatusCard(server: ServerState, onServerSettings: (() -> Unit)?) {
    val (label, color) = when (server.status) {
        ServerStatus.ONLINE -> stringResource(R.string.server_online) to OkGreen
        ServerStatus.CHECKING -> stringResource(R.string.server_checking) to Amber
        ServerStatus.UNREACHABLE -> stringResource(R.string.server_unreachable) to SampleRed
        ServerStatus.OFFLINE -> stringResource(R.string.server_offline) to SampleRed
        ServerStatus.UPDATE_REQUIRED -> stringResource(R.string.server_update_required) to SampleRed
        ServerStatus.UNKNOWN -> stringResource(R.string.server_unknown) to Amber
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.server_title), style = MaterialTheme.typography.titleSmall)
            StatusPill(label, color)
            server.root?.toHttpUrlOrNull()?.let {
                Text(ApiRoot.displayOrigin(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (server.status == ServerStatus.UNREACHABLE || server.status == ServerStatus.OFFLINE) {
                Text(stringResource(R.string.server_unreachable_help), style = MaterialTheme.typography.bodySmall)
            }
            if (server.status == ServerStatus.UPDATE_REQUIRED) {
                Text(stringResource(R.string.server_update_required_body), style = MaterialTheme.typography.bodySmall)
            }
            server.message?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.server_operator_message, it), style = MaterialTheme.typography.bodySmall)
            }
            if (onServerSettings != null) {
                TextButton(onClick = onServerSettings, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.server_settings))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(onBack: () -> Unit, onServerSettings: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SignInViewModel = viewModel { SignInViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val res = LocalResources.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sign_in_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (state.step == SignInStep.CODE) vm.changeNumber() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state.step) {
                SignInStep.MOBILE -> MobileStep(state, vm)
                SignInStep.CODE -> CodeStep(state, vm)
            }
            state.error?.let { error ->
                WarningBanner(
                    text = error.toMessage(res),
                    modifier = Modifier.testTag("sign_in_error"),
                    actionLabel = if (error.code == ApiError.CODE_NOT_CONFIGURED || error.isNetwork) stringResource(R.string.server_settings) else null,
                    onAction = onServerSettings,
                )
            }
            Text(stringResource(R.string.demo_disclaimer), style = MaterialTheme.typography.bodySmall, color = SampleRed)
        }
    }
}

@Composable
private fun MobileStep(state: SignInUiState, vm: SignInViewModel) {
    Text(stringResource(R.string.sign_in_mobile_intro), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = state.mobile,
        onValueChange = vm::onMobileChange,
        label = { Text(stringResource(R.string.sign_in_mobile_label)) },
        prefix = { Text("+91 ") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.requestCode() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mobile_input"),
    )
    Button(
        onClick = vm::requestCode,
        enabled = state.mobileValid && !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("send_code"),
    ) {
        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.sign_in_send_code))
    }
    InfoBanner(text = stringResource(R.string.sign_in_demo_numbers_intro))
    state.demoNumbers.forEach { number ->
        AssistChip(
            onClick = { vm.useDemoNumber(number) },
            label = {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(number.mobile, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(number.label, style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("demo_number_${number.mobile}"),
        )
    }
}

@Composable
private fun CodeStep(state: SignInUiState, vm: SignInViewModel) {
    val container = LocalAppContainer.current
    var now by remember { mutableLongStateOf(container.clock.now().toEpochMilli()) }
    LaunchedEffect(state.resendAtMillis) {
        while (now < state.resendAtMillis) {
            delay(1_000)
            now = container.clock.now().toEpochMilli()
        }
    }
    val waitSeconds = ((state.resendAtMillis - now) / 1000).coerceAtLeast(0)

    Text(
        stringResource(R.string.sign_in_code_intro, "+91 " + state.mobile),
        style = MaterialTheme.typography.bodyLarge,
    )
    state.challenge?.demoCode?.let { code ->
        Surface(
            color = Amber.copy(alpha = 0.15f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("demo_code_card"),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sign_in_demo_code_title), style = MaterialTheme.typography.titleSmall, color = Amber)
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                Text(stringResource(R.string.sign_in_demo_code_explainer), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::useDemoCode, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.sign_in_fill_demo_code))
                }
            }
        }
    }
    OutlinedTextField(
        value = state.code,
        onValueChange = vm::onCodeChange,
        label = { Text(stringResource(R.string.sign_in_code_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.verify() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("code_input"),
    )
    Button(
        onClick = vm::verify,
        enabled = state.codeValid && !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verify_code"),
    ) {
        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.sign_in_verify))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (waitSeconds > 0) {
            Text(stringResource(R.string.sign_in_resend_in, waitSeconds.toInt()), style = MaterialTheme.typography.bodySmall)
        } else {
            TextButton(onClick = vm::requestCode, enabled = !state.busy) { Text(stringResource(R.string.sign_in_resend)) }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = vm::changeNumber) { Text(stringResource(R.string.sign_in_change_number)) }
    }
}
