package com.samford.patient.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.samford.patient.core.db.FeedItemEntity
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.notify.Notifier
import com.samford.patient.core.sync.FeedKinds
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = SamfordDatabase.inMemory(context)

    @org.junit.After
    fun closeDb() = db.close()

    private fun item(id: String, kind: String, actor: String?, subject: String?) = FeedItemEntity(
        id = id, profileId = "patient-a", kind = kind, occurredAt = 1L, refType = "document", refId = "rx-1",
        actor = actor, subject = subject, notified = false, read = false,
    )

    @Test
    fun notificationNeverShowsNamesOrClinicalDetails() = runTest {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = Notifier(context).apply { ensureChannel() }
        db.records().insertFeedItems(
            listOf(
                item("a", FeedKinds.DOCUMENT_NEW, "Dr. Meera Sinha", "PRESCRIPTION"),
                item("b", FeedKinds.LAB_RESULT_READY, "Central lab", "Haemoglobin"),
            ),
        )

        assertThat(notifier.notifyPending(db.records(), enabled = true)).isEqualTo(2)

        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.single()
        val visibleText = listOf(
            posted.extras.getCharSequence(Notification.EXTRA_TITLE),
            posted.extras.getCharSequence(Notification.EXTRA_TEXT),
            posted.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" ")
        assertThat(visibleText).contains("2 new updates")
        for (secret in listOf("Meera", "Haemoglobin", "Prescription", "PRESCRIPTION", "lab", "Demo patient")) {
            assertThat(visibleText).doesNotContain(secret)
        }
        assertThat(posted.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        assertThat(posted.publicVersion).isNotNull()
        // Items are marked so the same news never notifies twice.
        assertThat(db.records().unnotifiedFeedItems()).isEmpty()
        assertThat(notifier.notifyPending(db.records(), enabled = true)).isEqualTo(0)
    }

    @Test
    fun disabledNotificationsStillMarkItemsWithoutPosting() = runTest {
        val notifier = Notifier(context).apply { ensureChannel() }
        db.records().insertFeedItems(listOf(item("c", FeedKinds.MEDICINES_DISPENSED, "Samford OPD Pharmacy", null)))

        notifier.notifyPending(db.records(), enabled = false)

        assertThat(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications).isEmpty()
        assertThat(db.records().unnotifiedFeedItems()).isEmpty()
    }
}
