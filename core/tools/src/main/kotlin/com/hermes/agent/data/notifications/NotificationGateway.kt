package com.hermes.agent.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CapturedNotification(
    val id: Int,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val key: String,
    val isClearable: Boolean,
)

/**
 * Gateway for posting and reading status-bar notifications.
 * Ported from OpenClaw notification specification (docs/nodes/notifications.md).
 */
@Singleton
class NotificationGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ID_ASSISTANT_ALERTS = "hermes_assistant_alerts"
        const val CHANNEL_NAME_ASSISTANT_ALERTS = "Assistant Notifications"
        private val notificationIdCounter = AtomicInteger(1000)

        private val _activeNotifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val activeNotifications: StateFlow<List<CapturedNotification>> = _activeNotifications.asStateFlow()

        fun updateActiveNotifications(notifications: List<CapturedNotification>) {
            _activeNotifications.value = notifications
        }

        fun onNotificationPosted(notification: CapturedNotification) {
            _activeNotifications.value = (_activeNotifications.value.filter { it.key != notification.key } + notification)
                .takeLast(100)
        }

        fun onNotificationRemoved(key: String) {
            _activeNotifications.value = _activeNotifications.value.filter { it.key != key }
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_ASSISTANT_ALERTS,
                CHANNEL_NAME_ASSISTANT_ALERTS,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Proactive alerts and messages posted by Hermes Assistant"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun postNotification(
        title: String,
        message: String,
        priority: String = "default",
        channelId: String = CHANNEL_ID_ASSISTANT_ALERTS,
    ): Int {
        val safeTitle = title.trim().take(120)
        val safeMessage = message.trim().take(2000)
        if (safeTitle.isEmpty() && safeMessage.isEmpty()) {
            return -1
        }

        val notifId = notificationIdCounter.incrementAndGet()
        val importance = when (priority.lowercase()) {
            "low" -> NotificationCompat.PRIORITY_LOW
            "high", "urgent" -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(safeTitle)
            .setContentText(safeMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeMessage))
            .setPriority(importance)
            .setAutoCancel(true)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        val manager = NotificationManagerCompat.from(context)
        manager.notify(notifId, builder.build())

        return notifId
    }

    fun getActiveNotifications(packageNameFilter: String? = null, limit: Int = 10): List<CapturedNotification> {
        val current = _activeNotifications.value
        return if (packageNameFilter.isNullOrBlank()) {
            current.takeLast(limit)
        } else {
            current.filter { it.packageName.equals(packageNameFilter, ignoreCase = true) }.takeLast(limit)
        }
    }
}
