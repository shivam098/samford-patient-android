package com.samford.patient.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.testing.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** End-to-end patient journeys through the real UI, with the fake backend in place of the network. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PatientJourneyTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun signInWithDemoCodeShowsFamilyRecords() {
        app.api.setRecord("patient-a", Fixtures.record("patient-a", Fixtures.document(app.api, "rx-1", "visit-1", "patient-a", versions = 2)))
        app.api.addEvent("patient-a", "DOCUMENT_AVAILABLE", "rx-1", "visit-1", true, "type" to "PRESCRIPTION", "doctorName" to "Dr. Demo A")
        launchApp()

        compose.waitForTag("sign_in").performClick()
        compose.waitForTag("demo_number_9000000001").performClick()
        compose.onNodeWithTag("send_code").performClick()
        compose.waitForTag("demo_code_card").assertIsDisplayed()
        compose.onNodeWithText("123456").assertIsDisplayed()
        compose.onNodeWithText("Use demo code").performClick()
        compose.onNodeWithTag("verify_code").performClick()

        compose.waitForTag("home_feed")
        runBlocking { app.container.syncCoordinator.run(force = true, reason = "test") }
        compose.waitForText("New prescription from Dr. Demo A")
        compose.onNodeWithTag("profile_switcher").assertIsDisplayed()
        compose.onNodeWithTag("nav_records").performClick()
        compose.waitForTag("document_card").assertIsDisplayed()
        compose.onNodeWithText("Sent to Samford OPD Pharmacy").assertIsDisplayed()
    }

    @Test
    fun wrongCodeAndUnknownNumberAreExplainedInPlainLanguage() {
        launchApp()
        compose.waitForTag("sign_in").performClick()
        compose.waitForTag("mobile_input").performTextInput("9123456789")
        compose.onNodeWithTag("send_code").performClick()
        compose.waitForText("isn't registered at Samford Hospital")

        compose.onNodeWithTag("mobile_input").performClick()
        compose.waitForTag("demo_number_9000000001").performClick()
        compose.onNodeWithTag("send_code").performClick()
        compose.waitForTag("code_input").performTextInput("000000")
        compose.onNodeWithTag("verify_code").performClick()
        compose.waitForText("2 tries left")
    }

    @Test
    fun sampleModeShowsHandwritingWithAmendmentAndOriginalVersion() {
        launchApp()
        compose.enterSampleMode()
        compose.onNodeWithTag("nav_records").performClick()
        compose.waitForTag("records_list")
        compose.onNodeWithTag("allergies_banner").assertIsDisplayed()

        compose.waitForCount("document_card", 2).performClick() // prescription of the latest visit
        compose.waitForTag("document_pager")
        compose.waitForTag("amended_banner").assertIsDisplayed()
        compose.onNodeWithText("Reason: Paracetamol dose clarified", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("versions_button").performClick()
        compose.waitForTag("version_1").performClick()
        compose.waitForTag("older_version_banner").assertIsDisplayed()
        compose.onNodeWithText("View latest").performClick()
        compose.waitForTag("amended_banner")
    }

    @Test
    fun switchingFamilyProfileShowsOnlyThatPersonsRecords() {
        launchApp()
        compose.enterSampleMode()
        compose.onNodeWithTag("nav_records").performClick()
        compose.waitForText("General Medicine")

        compose.onNodeWithTag("profile_switcher").performClick()
        compose.waitForTag("profile_sample-rohan").performClick()
        compose.waitForText("Paediatrics")
        assertThat(compose.onAllNodesWithTag("visit_card").fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithText("General Medicine", substring = true).assertDoesNotExist()
    }

    @Test
    fun signOutReturnsToWelcomeAndEmptiesThePhone() {
        launchApp()
        compose.enterSampleMode()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.waitForTag("sign_out").performScrollTo().performClick()
        compose.waitForTag("confirm_sign_out").performClick()
        compose.waitForTag("explore_sample")
        runBlocking {
            assertThat(app.container.database.records().profiles()).isEmpty()
            assertThat(app.container.database.blobs().count()).isEqualTo(0)
            assertThat(app.container.settings.current().sampleMode).isFalse()
        }
    }

    @Test
    fun appLockHidesRecordsUntilThePinIsEntered() {
        launchApp()
        compose.enterSampleMode()
        runBlocking {
            app.container.appLock.enable("2468")
            app.container.appLock.onLaunch()
        }
        compose.waitForTag("lock_screen")
        compose.onNodeWithTag("lock_pin").performTextInput("1111")
        compose.onNodeWithTag("lock_unlock").performClick()
        compose.waitForText("Wrong PIN")
        compose.onNodeWithTag("lock_pin").performTextInput("2468")
        compose.onNodeWithTag("lock_unlock").performClick()
        compose.settle()
        compose.onNodeWithTag("lock_screen").assertDoesNotExist()
    }
}
