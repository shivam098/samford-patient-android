package com.samford.patient.ui

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.samford.patient.MainActivity
import com.samford.patient.testing.TestSamfordApp
import org.robolectric.shadows.ShadowLooper

val app: TestSamfordApp get() = ApplicationProvider.getApplicationContext<Context>() as TestSamfordApp

fun launchApp(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java)

/** Lets coroutines on background dispatchers (sample rendering, Room, Coil) finish, then idles Compose. */
fun ComposeTestRule.settle(rounds: Int = 6) {
    repeat(rounds) {
        Thread.sleep(150)
        ShadowLooper.idleMainLooper()
        waitForIdle()
    }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 20_000): SemanticsNodeInteraction {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) {
        check(System.currentTimeMillis() < deadline) { "Timed out waiting for $tag" }
        Thread.sleep(100)
        ShadowLooper.idleMainLooper()
        waitForIdle()
    }
    return onAllNodesWithTag(tag)[0]
}

@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 20_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()) {
        check(System.currentTimeMillis() < deadline) { "Timed out waiting for text: $text" }
        Thread.sleep(100)
        ShadowLooper.idleMainLooper()
        waitForIdle()
    }
}

/** Enters offline sample mode from the welcome screen and waits for Home. */
fun ComposeTestRule.enterSampleMode() {
    waitForTag("explore_sample").performClick()
    waitForTag("home_feed", 60_000)
    settle()
}

fun ComposeTestRule.scrollRecordsTo(tag: String) {
    onNodeWithTag("records_list").performScrollToNode(hasTestTag(tag))
}

@OptIn(ExperimentalRoborazziApi::class)
fun shot(name: String) {
    captureScreenRoboImage("../docs/screenshots/$name.png")
}
