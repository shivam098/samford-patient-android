package com.samford.patient.integration

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.discovery.ManualServerResult
import com.samford.patient.testing.TestContainers
import com.samford.patient.ui.app
import com.samford.patient.ui.launchApp
import com.samford.patient.ui.settle
import com.samford.patient.ui.waitForTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real screens, HTTP client and sync stack against the isolated mock backend
 * (`-Psamford.itBase=http://127.0.0.1:4321`): sign in with the server's demo code, reach Home and
 * open a handwritten document. Skipped when no backend is configured.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BackendUiJourneyTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val origin = System.getProperty("samford.itBase").orEmpty().trimEnd('/')

    @Before
    fun connect() {
        assumeTrue("Set -Psamford.itBase to run backend UI tests", origin.isNotEmpty())
        System.setProperty("java.awt.headless", "true")
        app.container = TestContainers.createReal(app)
        runBlocking {
            val result = app.container.serverDirectory.setManual(origin)
            assertThat(result).isInstanceOf(ManualServerResult.Saved::class.java)
            assertThat((result as ManualServerResult.Saved).reachable).isTrue()
        }
    }

    @After
    fun signOut() {
        if (origin.isEmpty()) return
        runBlocking { app.container.signOut() }
        app.container.database.close()
    }

    @Test
    fun signInWithServerDemoCodeReachesHomeAndOpensHandwriting() {
        launchApp()
        compose.waitForTag("sign_in").performClick()
        compose.waitForTag("demo_number_9000000001").performClick()
        compose.onNodeWithTag("send_code").performClick()
        compose.waitForTag("demo_code_card")
        compose.onNodeWithText("Use demo code").performClick()
        compose.onNodeWithTag("verify_code").performClick()

        compose.waitForTag("home_feed", 60_000)
        compose.waitForTag("feed_item", 60_000)
        compose.onNodeWithTag("nav_records").performClick()
        compose.waitForTag("document_card", 60_000).performClick()
        compose.waitForTag("page_image", 60_000)
        compose.settle()

        runBlocking {
            assertThat(app.container.tokens.token()).isNotEmpty()
            assertThat(app.container.database.records().profiles().map { it.id }).contains("patient-a")
        }
    }
}
