package com.samford.patient

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.discovery.ManualServerResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real sign-in on an emulator against the isolated mock backend on the host:
 * `-Pandroid.testInstrumentationRunnerArguments.itBase=http://10.0.2.2:4321`. Skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class BackendDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val itBase: String = InstrumentationRegistry.getArguments().getString("itBase").orEmpty()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SamfordApp).container

    @Before
    fun connect() {
        assumeTrue("Pass -Pandroid.testInstrumentationRunnerArguments.itBase", itBase.isNotEmpty())
        runBlocking {
            val result = container.serverDirectory.setManual(itBase)
            assertThat(result).isInstanceOf(ManualServerResult.Saved::class.java)
            assertThat((result as ManualServerResult.Saved).reachable).isTrue()
        }
    }

    @After
    fun cleanUp() = runBlocking {
        container.signOut()
        container.serverDirectory.clearManual()
        Unit
    }

    @Test
    fun signInWithDemoCodeAndOpenTheHandwrittenPrescription() {
        compose.awaitTag("sign_in").performClick()
        compose.awaitTag("demo_number_9000000001").performClick()
        compose.onNodeWithTag("send_code").performClick()
        compose.awaitTag("demo_code_card")
        compose.saveScreenshot("device-backend-01-demo-code")
        compose.onNodeWithText("Use demo code").performClick()
        compose.onNodeWithTag("verify_code").performClick()

        compose.awaitTag("home_feed", 60_000)
        compose.awaitTag("feed_item", 60_000)
        compose.saveScreenshot("device-backend-02-home")
        compose.onNodeWithTag("nav_records").performClick()
        compose.awaitTag("document_card", 60_000)
        compose.saveScreenshot("device-backend-03-records")
        compose.onNodeWithTag("document_card").performClick()
        compose.awaitTag("page_image", 60_000)
        Thread.sleep(2_000)
        compose.saveScreenshot("device-backend-04-document")

        runBlocking {
            val tokenStoredEncrypted = container.tokens.token()
            assertThat(tokenStoredEncrypted).isNotEmpty()
            assertThat(container.database.records().profiles().map { it.id }).contains("patient-a")
        }
    }
}
