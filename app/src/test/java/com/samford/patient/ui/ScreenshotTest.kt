package com.samford.patient.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots for review (./gradlew :app:recordRoborazziDebug writes docs/screenshots).
 * Sizes: a typical phone (411x891 dp) and a small low-end phone (320x640 dp); English, Hindi and
 * 1.5x large text.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun sampleTour(prefix: String) {
        launchApp()
        compose.settle()
        shot("$prefix-01-welcome")
        compose.enterSampleMode()
        shot("$prefix-02-home")
        compose.onNodeWithTag("nav_records").performClick()
        compose.waitForTag("records_list")
        compose.settle()
        shot("$prefix-03-records")
        compose.onAllNodesWithTag("document_card")[1].performClick()
        compose.waitForTag("document_pager")
        compose.settle(15)
        shot("$prefix-04-prescription-amended")
        compose.onNodeWithTag("versions_button").performClick()
        compose.settle()
        shot("$prefix-05-version-history")
        compose.waitForTag("version_1").performClick()
        compose.settle(15)
        shot("$prefix-06-original-version")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneEnglish() {
        sampleTour("phone-en")
        compose.onNodeWithTag("lock_screen").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "hi-rIN-w411dp-h891dp-xxhdpi")
    fun phoneHindi() = sampleTour("phone-hi")

    @Test
    @Config(qualifiers = "w320dp-h640dp-hdpi")
    fun smallPhoneEnglish() = sampleTour("small-en")

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun largeTextEnglish() {
        RuntimeEnvironment.setFontScale(1.5f)
        launchApp()
        compose.settle()
        shot("large-text-01-welcome")
        compose.enterSampleMode()
        shot("large-text-02-home")
        compose.onNodeWithTag("nav_records").performClick()
        compose.waitForTag("records_list")
        compose.settle()
        shot("large-text-03-records")
        compose.onNodeWithTag("nav_settings").performClick()
        compose.settle()
        shot("large-text-04-settings")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun signInWithDemoCode() {
        launchApp()
        compose.waitForTag("sign_in").performClick()
        compose.waitForTag("demo_number_9000000001").performClick()
        compose.settle()
        shot("phone-en-10-sign-in-mobile")
        compose.onNodeWithTag("send_code").performClick()
        compose.waitForTag("demo_code_card")
        compose.settle()
        shot("phone-en-11-sign-in-demo-code")
    }

    @Test
    @Config(qualifiers = "hi-rIN-w411dp-h891dp-xxhdpi")
    fun signInWithDemoCodeHindi() {
        launchApp()
        compose.waitForTag("sign_in").performClick()
        compose.waitForTag("demo_number_9000000001").performClick()
        compose.onNodeWithTag("send_code").performClick()
        compose.waitForTag("demo_code_card")
        compose.settle()
        shot("phone-hi-11-sign-in-demo-code")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun labResultSettingsAndDetails() {
        launchApp()
        compose.enterSampleMode()
        compose.onNodeWithTag("nav_records").performClick()
        compose.waitForTag("records_list")
        compose.onAllNodesWithTag("lab_result_row")[3].performScrollTo().performClick()
        compose.waitForTag("lab_result_card")
        compose.settle()
        shot("phone-en-07-lab-result")
        compose.onNodeWithTag("nav_settings").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        compose.settle()
        compose.onAllNodesWithTag("document_card")[1].performClick()
        compose.waitForTag("document_pager")
        compose.onNodeWithTag("versions_button").assertExists()
        compose.onNodeWithTag("share_button").assertExists()
        compose.settle(10)
        androidx.test.espresso.Espresso.pressBack()
        compose.settle()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.settle()
        shot("phone-en-08-settings")
    }
}
