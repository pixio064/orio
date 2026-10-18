package com.example.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.ReminderLogAction
import com.example.data.local.entity.ReminderLogEntity
import com.example.notification.NotificationHelper
import com.example.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(NotificationHelper.EXTRA_REMINDER_ID, -1L)
        val title = intent.getStringExtra(NotificationHelper.EXTRA_REMINDER_TITLE) ?: "Reminder"
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, -1)

        // Dismiss the notification immediately
        if (notificationId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notificationId)
        }

        val action = intent.action ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val today = dateFormat.format(Date())

                when (action) {
                    NotificationHelper.ACTION_DONE -> {
                        db.reminderDao().insertReminderLog(
                            ReminderLogEntity(
                                reminderId = reminderId,
                                reminderTitle = title,
                                action = ReminderLogAction.DONE,
                                dateString = today
                            )
                        )
                        // If recurring, advance schedule
                        val reminder = db.reminderDao().getReminderById(reminderId)
                        if (reminder != null && reminder.isEnabled) {
                            val nextTrigger = AlarmScheduler.calculateNextReminderTrigger(reminder)
                            db.reminderDao().updateReminder(reminder.copy(nextTriggerTimestamp = nextTrigger))
                            AlarmScheduler.scheduleReminder(context, reminder.copy(nextTriggerTimestamp = nextTrigger))
                        }
                    }
                    NotificationHelper.ACTION_SNOOZE -> {
                        db.reminderDao().insertReminderLog(
                            ReminderLogEntity(
                                reminderId = reminderId,
                                reminderTitle = title,
                                action = ReminderLogAction.SNOOZED,
                                dateString = today
                            )
                        )
                        // Reschedule alarm for +10 minutes
                        val reminder = db.reminderDao().getReminderById(reminderId)
                        val snoozeMins = reminder?.snoozeMinutes ?: 10
                        val snoozeTrigger = System.currentTimeMillis() + snoozeMins * 60 * 1000L
                        val snoozeReminder = reminder?.copy(nextTriggerTimestamp = snoozeTrigger)
                            ?: ReminderEntity(
                                id = reminderId,
                                title = title,
                                nextTriggerTimestamp = snoozeTrigger
                            )
                        AlarmScheduler.scheduleReminder(context, snoozeReminder)
                    }
                    NotificationHelper.ACTION_SKIP -> {
                        db.reminderDao().insertReminderLog(
                            ReminderLogEntity(
                                reminderId = reminderId,
                                reminderTitle = title,
                                action = ReminderLogAction.SKIPPED,
                                dateString = today
                            )
                        )
                        // Reschedule normal next occurrence
                        val reminder = db.reminderDao().getReminderById(reminderId)
                        if (reminder != null && reminder.isEnabled) {
                            val nextTrigger = AlarmScheduler.calculateNextReminderTrigger(reminder)
                            db.reminderDao().updateReminder(reminder.copy(nextTriggerTimestamp = nextTrigger))
                            AlarmScheduler.scheduleReminder(context, reminder.copy(nextTriggerTimestamp = nextTrigger))
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
