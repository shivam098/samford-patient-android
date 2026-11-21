package com.samford.patient

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.samford.patient.core.settings.LocaleStore
import com.samford.patient.ui.SamfordAppRoot
import com.samford.patient.ui.theme.SamfordTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleStore.wrap(newBase))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SamfordApp).container
        if (savedInstanceState == null) consumeServerLink(intent, container)
        setContent {
            SamfordTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    SamfordAppRoot(container, onLanguageChanged = { recreate() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeServerLink(intent, (application as SamfordApp).container)
    }

    /** `samfordpatient://connect?...` opens server settings pre-filled; the person confirms it there. */
    private fun consumeServerLink(intent: Intent?, container: AppContainer) {
        val data = intent?.data ?: return
        if (data.scheme == "samfordpatient" && data.host == "connect") {
            container.pendingServerLink.value = data.toString()
        }
    }
}
