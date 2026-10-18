package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.receiver.NotificationActionReceiver

object NotificationHelper {

    const val CHANNEL_REMINDERS = "focus_track_reminders"
    const val CHANNEL_LIMITS = "focus_track_limits"
    const val CHANNEL_FOCUS = "focus_track_focus"
    const val CHANNEL_PLANNER = "focus_track_planner"
    const val CHANNEL_TIMER_SERVICE = "focus_track_timer_service"

    const val ACTION_DONE = "com.example.ACTION_DONE"
    const val ACTION_SNOOZE = "com.example.ACTION_SNOOZE"
    const val ACTION_SKIP = "com.example.ACTION_SKIP"

    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()

            // 1. Reminders (Heads-up banner)
            val reminderChannel = NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_reminders_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setSound(soundUri, audioAttributes)
            }

            // 2. App Limits
            val limitChannel = NotificationChannel(
                CHANNEL_LIMITS,
                context.getString(R.string.channel_limits_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_limits_desc)
                enableVibration(true)
            }

            // 3. Focus Sessions
            val focusChannel = NotificationChannel(
                CHANNEL_FOCUS,
                context.getString(R.string.channel_focus_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_focus_desc)
                enableVibration(true)
            }

            // 4. Daily Planner (11 PM)
            val plannerChannel = NotificationChannel(
                CHANNEL_PLANNER,
                "Daily Planner Reminder",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders to review today and plan tomorrow"
                enableVibration(true)
            }

            // 5. Silent Ongoing Focus Timer Service
            val timerServiceChannel = NotificationChannel(
                CHANNEL_TIMER_SERVICE,
                "Focus Timer Running",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification while focus timer is active"
                enableVibration(false)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(
                listOf(reminderChannel, limitChannel, focusChannel, plannerChannel, timerServiceChannel)
            )
        }
    }

    fun showReminderNotification(
        context: Context,
        reminderId: Long,
        title: String,
        message: String,
        category: String
    ) {
        val notificationId = (reminderId.toInt() * 1000) + (System.currentTimeMillis() % 1000).toInt()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // Open App Intent
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Done Intent
        val doneIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DONE
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Snooze Intent
        val snoozeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Skip Intent
        val skipIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_SKIP
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_REMINDER_TITLE, title)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 3,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayMsg = if (message.isNotBlank()) message else "Time to focus on: $title"

        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⏰ $title")
            .setContentText(displayMsg)
            .setSubText(category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(R.mipmap.ic_launcher, context.getString(R.string.action_done), donePendingIntent)
            .addAction(R.mipmap.ic_launcher, context.getString(R.string.action_snooze), snoozePendingIntent)
            .addAction(R.mipmap.ic_launcher, context.getString(R.string.action_skip), skipPendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    fun showAppLimitAlert(
        context: Context,
        appName: String,
        usedMinutes: Int,
        limitMinutes: Int,
        isWarning: Boolean
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val title = if (isWarning) "⚠️ Approaching Limit: $appName" else "🛑 Limit Reached: $appName"
        val message = if (isWarning) {
            "You have used $usedMinutes min of your $limitMinutes min daily limit (80%)."
        } else {
            "You've reached your daily limit of $limitMinutes min on $appName. Time for a break!"
        }

        val contentIntent = Intent(context, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            appName.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_LIMITS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(appName.hashCode(), notification)
    }

    fun showFocusSessionCompleted(context: Context, durationMinutes: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val contentIntent = Intent(context, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            8881,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_FOCUS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🎉 Focus Session Completed!")
            .setContentText("Great work! You stayed focused for $durationMinutes minutes. Take a relaxing break.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(8881, notification)
    }

    fun showPlanningReminder(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NAVIGATE_TO", "planner")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            9991,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PLANNER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🌙 Plan Tomorrow, One Step at a Time")
            .setContentText("Your next day starts here. Take 2 minutes to organize your tasks.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(9991, notification)
    }

    fun sendTestNotification(context: Context) {
        showReminderNotification(
            context = context,
            reminderId = 9999L,
            title = "Drink Water & Hydrate",
            message = "Remember to stay hydrated! Have a glass of fresh water.",
            category = "Hydration"
        )
    }
}
