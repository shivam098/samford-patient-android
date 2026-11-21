package com.samford.patient

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real emulator: offline sample mode, the handwriting viewer with pinch-zoom, and sign-out. */
@RunWith(AndroidJUnit4::class)
class SampleModeDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SamfordApp).container

    @After
    fun cleanUp() = runBlocking { container.signOut() }

    @Test
    fun sampleRecordsOpenZoomAndSignOutWipes() {
        compose.awaitTag("explore_sample").performClick()
        compose.awaitTag("home_feed", 90_000)
        compose.saveScreenshot("device-01-home")

        compose.onNodeWithTag("nav_records").performClick()
        compose.awaitTag("document_card")
        compose.saveScreenshot("device-02-records")
        compose.onAllNodesWithTag("document_card")[1].performClick()
        compose.awaitTag("amended_banner")
        compose.awaitTag("page_image", 30_000)
        Thread.sleep(1_500)
        compose.saveScreenshot("device-03-prescription")

        compose.onNodeWithTag("document_pager").performTouchInput {
            pinch(center - Offset(50f, 0f), center - Offset(300f, 0f), center + Offset(50f, 0f), center + Offset(300f, 0f), 400L)
        }
        Thread.sleep(1_500)
        compose.saveScreenshot("device-04-prescription-zoomed")

        compose.onNodeWithTag("versions_button").performClick()
        compose.awaitTag("version_1").performClick()
        compose.awaitTag("older_version_banner")
        compose.saveScreenshot("device-05-original-version")

        // Keystore-backed token storage and the real database are exercised on the device.
        runBlocking { container.signOut() }
        compose.awaitTag("explore_sample")
        runBlocking { assertThat(container.database.records().profiles()).isEmpty() }
    }

    @Test
    fun settingsShowServerAndLanguage() {
        compose.awaitTag("explore_sample").performClick()
        compose.awaitTag("home_feed", 90_000)
        compose.onNodeWithTag("nav_settings").performClick()
        compose.awaitTag("language_hi").performScrollTo()
        compose.saveScreenshot("device-06-settings")
    }
}
