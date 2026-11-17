package dev.tim.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.tim.assistant.model.NotificationDto
import dev.tim.assistant.notify.NotifySink

const val REMINDER_CHANNEL_ID = "wally"
const val REMINDER_CHANNEL_NAME = "Wally reminders"
const val EXTRA_TARGET = "wally.target"
const val EXTRA_BODY = "wally.body"
const val EXTRA_ROW_ID = "wally.rowId"
const val ACTION_REMINDER = "dev.tim.assistant.REMINDER"

/**
 * Shows reminder notifications on the `wally` channel and schedules the later ones with the system clock
 * (setAndAllowWhileIdle, RTC_WAKEUP). Every PendingIntent is immutable and unique per row.
 */
class Notifier(private val context: Context) : NotifySink {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun ensureChannel() {
        if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL_ID, REMINDER_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Tap: opens the app on the row's target. Request codes 2n (tap) and 2n+1 (alarm) never collide between rows. */
    private fun tapIntent(row: NotificationDto): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("wally://reminder/${row.id}")
            putExtra(EXTRA_TARGET, row.target)
            putExtra(EXTRA_BODY, row.body)
            putExtra(EXTRA_ROW_ID, row.id)
        }
        return PendingIntent.getActivity(context, (row.id * 2).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun alarmIntent(row: NotificationDto): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.parse("wally://alarm/${row.id}")
            putExtra(EXTRA_ROW_ID, row.id)
        }
        return PendingIntent.getBroadcast(context, (row.id * 2 + 1).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun show(row: NotificationDto) {
        // Without the permission (Android 13+) Tim simply gets no notification; asking again is not our business here.
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val notification = Notification.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(row.title)
            .setContentText(row.body)
            .setStyle(Notification.BigTextStyle().bigText(row.body))
            .setContentIntent(tapIntent(row))
            .setAutoCancel(true)
            .build()
        manager.notify(row.id.toInt(), notification)
    }

    override fun schedule(row: NotificationDto, at: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(row))
    }
}
