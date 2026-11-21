package com.samford.patient.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.samford.patient.MainActivity
import com.samford.patient.R
import com.samford.patient.core.db.RecordsDao
import com.samford.patient.core.settings.LocaleStore

/**
 * Local notifications for records that arrived through background polling (simulated push; no FCM).
 * The text is always generic: no names, doctors, tests or medicines, on the lock screen or anywhere.
 */
class Notifier(private val context: Context) {

    fun ensureChannel() {
        val localized = LocaleStore.wrap(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = localized.getString(R.string.notification_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts one generic notification for all unnotified feed items, then marks them notified. */
    suspend fun notifyPending(dao: RecordsDao, enabled: Boolean): Int {
        val pending = dao.unnotifiedFeedItems()
        if (pending.isEmpty()) return 0
        if (enabled && canPost()) post(pending.size)
        dao.markNotified(pending.map { it.id })
        return pending.size
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(count: Int) {
        val localized = LocaleStore.wrap(context)
        val title = localized.getString(R.string.app_name)
        val text = localized.resources.getQuantityString(R.plurals.notification_new_updates, count, count)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(localized.getString(R.string.notification_generic))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(text)
            .setNumber(count)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancelAll() {
        NotificationManagerCompat.from(context).cancelAll()
    }

    companion object {
        const val CHANNEL_ID = "records"
        private const val NOTIFICATION_ID = 1001
    }
}
