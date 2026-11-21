package com.samford.patient.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the startup and critical-journey Baseline Profile on a connected device or emulator:
 * `./gradlew :app:generateBaselineProfile` (API 33+ emulator or rooted API 28+ device).
 * The journey uses the offline sample mode so it needs no server.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        exploreSampleRecords()
    }
}

internal const val TARGET_PACKAGE = "com.samford.patient"

internal fun MacrobenchmarkScope.exploreSampleRecords() {
    device.wait(Until.hasObject(By.res("explore_sample")), 5_000)
    device.findObject(By.res("explore_sample"))?.let {
        it.click()
        device.wait(Until.hasObject(By.res("home_feed")), 15_000)
    }
    device.findObject(By.res("nav_records"))?.click()
    device.wait(Until.hasObject(By.res("records_list")), 5_000)
    device.findObject(By.res("document_card"))?.let {
        it.click()
        device.wait(Until.hasObject(By.res("document_pager")), 10_000)
        device.findObject(By.res("document_pager"))?.let { pager ->
            pager.setGestureMargin(device.displayWidth / 5)
            pager.pinchOpen(0.6f)
            device.waitForIdle()
            pager.pinchClose(0.6f)
            device.waitForIdle()
        }
        device.pressBack()
    }
}
