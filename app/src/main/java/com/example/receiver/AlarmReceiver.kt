package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.notification.NotificationHelper
import com.example.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TYPE) ?: return

        when (type) {
            AlarmScheduler.TYPE_REMINDER -> {
                val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
                val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Reminder"
                val message = intent.getStringExtra(AlarmScheduler.EXTRA_MESSAGE) ?: ""
                val category = intent.getStringExtra(AlarmScheduler.EXTRA_CATEGORY) ?: "General"

                NotificationHelper.showReminderNotification(
                    context = context,
                    reminderId = reminderId,
                    title = title,
                    message = message,
                    category = category
                )

                // Reschedule next occurrence
                if (reminderId != -1L) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = AppDatabase.getInstance(context)
                            val reminder = db.reminderDao().getReminderById(reminderId)
                            if (reminder != null && reminder.isEnabled) {
                                val nextTrigger = AlarmScheduler.calculateNextReminderTrigger(reminder)
                                val updated = reminder.copy(
                                    lastTriggerTimestamp = System.currentTimeMillis(),
                                    nextTriggerTimestamp = nextTrigger
                                )
                                db.reminderDao().updateReminder(updated)
                                AlarmScheduler.scheduleReminder(context, updated)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            AlarmScheduler.TYPE_TASK -> {
                val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
                val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Task Reminder"
                val message = intent.getStringExtra(AlarmScheduler.EXTRA_MESSAGE) ?: "Task starting soon"
                val category = intent.getStringExtra(AlarmScheduler.EXTRA_CATEGORY) ?: "Task"

                NotificationHelper.showReminderNotification(
                    context = context,
                    reminderId = 500000L + taskId,
                    title = title,
                    message = message,
                    category = category
                )
            }
            AlarmScheduler.TYPE_PLANNER -> {
                NotificationHelper.showPlanningReminder(context)
                // Reschedule for next day 11 PM
                AlarmScheduler.scheduleDailyPlannerReminder(context)
            }
        }
    }
}
