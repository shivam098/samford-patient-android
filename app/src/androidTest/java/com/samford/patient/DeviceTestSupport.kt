package com.samford.patient

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Screenshots land in the app's external files dir; CI pulls them with adb after the run. */
fun ComposeTestRule.saveScreenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeTestRule.awaitTag(tag: String, timeoutMs: Long = 30_000): SemanticsNodeInteraction {
    waitUntil(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    return onAllNodesWithTag(tag)[0]
}
