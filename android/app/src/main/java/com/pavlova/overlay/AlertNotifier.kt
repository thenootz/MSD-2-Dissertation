package com.pavlova.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pavlova.MainActivity
import com.pavlova.R
import kotlin.math.absoluteValue

/**
 * Posts wellbeing alerts as **system notifications**.
 *
 * Used as a fallback by [com.pavlova.ml.FeedAnalyzer] when the
 * draw-over-other-apps permission is missing, so [com.pavlova.analysis.FeedAlerts]
 * alerts still reach the user even though the [OverlayManager] heads-up banner
 * can't be drawn over the social-media app.
 *
 * Notifications go to a dedicated "Wellbeing alerts" channel (separate from the
 * low-importance foreground-capture channel) and reuse a stable id per
 * [com.pavlova.analysis.FeedAlerts.Alert.key] so repeats of the same alert type
 * update in place rather than stack.
 */
class AlertNotifier(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "AlertNotifier"
        private const val CHANNEL_ID = "wellbeing_alerts"
        // Base offset keeps these ids clear of the foreground-service
        // notification (id 1001 in ScreenCaptureService).
        private const val NOTIFICATION_ID_BASE = 2_000
    }

    init {
        ensureChannel()
    }

    /** True when the app is currently allowed to post notifications. */
    fun canNotify(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    /**
     * Post (or update) a wellbeing alert notification. No-op when notifications
     * are disabled. [key] drives a stable notification id so the same alert type
     * updates instead of stacking.
     */
    fun notify(key: String, title: String, body: String, level: OverlayManager.Level) {
        if (!canNotify()) {
            Log.w(TAG, "Notifications disabled — skipping alert: $title")
            return
        }

        val tapIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val priority = when (level) {
            OverlayManager.Level.CRITICAL -> NotificationCompat.PRIORITY_HIGH
            OverlayManager.Level.WARNING -> NotificationCompat.PRIORITY_DEFAULT
            OverlayManager.Level.INFO -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(priority)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .build()

        try {
            val id = NOTIFICATION_ID_BASE + (key.hashCode().absoluteValue % 1_000)
            NotificationManagerCompat.from(appContext).notify(id, notification)
            Log.d(TAG, "Alert notification posted: $key ($level)")
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS can be revoked between the check and the post.
            Log.e(TAG, "Failed to post alert notification", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wellbeing alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Heads-up nudges about your feed when the on-screen banner can't be shown"
                setShowBadge(true)
            }
            appContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

