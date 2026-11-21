package com.samford.patient.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.samford.patient.R
import com.samford.patient.core.security.PinHasher
import com.samford.patient.data.UnlockResult
import com.samford.patient.ui.common.LocalAppContainer
import com.samford.patient.ui.common.formatDateTime
import kotlinx.coroutines.launch

/** Full-screen PIN prompt drawn over the app. Records stay hidden until the PIN is entered. */
@Composable
fun LockScreen() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UnlockResult?>(null) }
    var confirmForgot by remember { mutableStateOf(false) }

    fun submit() {
        if (pin.length < PinHasher.MIN_LENGTH) return
        val entered = pin
        pin = ""
        scope.launch { result = container.appLock.unlock(entered) }
    }

    Surface(Modifier.fillMaxSize().testTag("lock_screen"), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(PinHasher.MAX_LENGTH) },
                label = { Text(stringResource(R.string.pin_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .testTag("lock_pin"),
            )
            when (val r = result) {
                is UnlockResult.Wrong -> Text(
                    pluralStringResource(R.plurals.lock_wrong_pin, r.attemptsBeforePause, r.attemptsBeforePause),
                    color = MaterialTheme.colorScheme.error,
                )
                is UnlockResult.Paused -> Text(
                    stringResource(R.string.lock_paused, formatDateTime(r.untilMillis)),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
            Button(onClick = ::submit, enabled = pin.length >= PinHasher.MIN_LENGTH, modifier = Modifier.testTag("lock_unlock")) {
                Text(stringResource(R.string.lock_unlock))
            }
            TextButton(onClick = { confirmForgot = true }) { Text(stringResource(R.string.lock_forgot)) }
        }
    }

    if (confirmForgot) {
        AlertDialog(
            onDismissRequest = { confirmForgot = false },
            title = { Text(stringResource(R.string.lock_forgot)) },
            text = { Text(stringResource(R.string.lock_forgot_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForgot = false
                    scope.launch {
                        container.signOut()
                        container.appLock.reset()
                    }
                }) { Text(stringResource(R.string.action_confirm_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmForgot = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
