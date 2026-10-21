package com.example.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.data.local.entity.ReminderFrequency
import com.example.data.local.entity.ReminderLogAction
import com.example.data.local.entity.ReminderLogEntity
import com.example.data.local.entity.TaskOutcome
import com.example.notification.NotificationHelper
import com.example.scheduler.AlarmScheduler
import com.example.util.DateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                val today = DateKeys.today()

                val isTask = reminderId >= 500000L
                val taskId = if (isTask) reminderId - 500000L else -1L

                when (action) {
                    NotificationHelper.ACTION_DONE -> {
                        if (isTask) {
                            val task = db.taskDao().getTaskById(taskId)
                            if (task != null) {
                                db.taskDao().updateTask(task.copy(outcome = TaskOutcome.COMPLETED))
                                AlarmScheduler.cancelTask(context, taskId)
                            }
                        } else {
                            db.reminderDao().insertReminderLog(
                                ReminderLogEntity(
                                    reminderId = reminderId,
                                    reminderTitle = title,
                                    action = ReminderLogAction.DONE,
                                    dateString = today
                                )
                            )
                            val reminder = db.reminderDao().getReminderById(reminderId)
                            if (reminder != null && reminder.isEnabled) {
                                if (reminder.frequency == ReminderFrequency.ONE_TIME) {
                                    db.reminderDao().updateReminder(reminder.copy(isEnabled = false))
                                } else {
                                    val nextTrigger = AlarmScheduler.calculateNextReminderTrigger(reminder)
                                    val updated = reminder.copy(nextTriggerTimestamp = nextTrigger)
                                    db.reminderDao().updateReminder(updated)
                                    AlarmScheduler.scheduleReminder(context, updated)
                                }
                            }
                        }
                    }

                    NotificationHelper.ACTION_SNOOZE -> {
                        if (isTask) {
                            val task = db.taskDao().getTaskById(taskId)
                            if (task != null && task.outcome == TaskOutcome.PENDING) {
                                val snoozeMinutes = 10
                                val snoozeTrigger = System.currentTimeMillis() + snoozeMinutes * 60 * 1000L
                                AlarmScheduler.scheduleAt(
                                    context = context,
                                    id = 600000L + taskId,
                                    triggerAt = snoozeTrigger,
                                    extras = mapOf(
                                        AlarmScheduler.EXTRA_TYPE to AlarmScheduler.TYPE_TASK,
                                        AlarmScheduler.EXTRA_ID to taskId,
                                        AlarmScheduler.EXTRA_TITLE to task.title,
                                        AlarmScheduler.EXTRA_MESSAGE to "Snoozed task reminder",
                                        AlarmScheduler.EXTRA_CATEGORY to task.category,
                                        AlarmScheduler.EXTRA_SNOOZE_MINUTES to snoozeMinutes
                                    )
                                )
                                db.reminderDao().insertReminderLog(
                                    ReminderLogEntity(
                                        reminderId = reminderId,
                                        reminderTitle = task.title,
                                        action = ReminderLogAction.SNOOZED,
                                        dateString = today
                                    )
                                )
                            }
                        } else {
                            val reminder = db.reminderDao().getReminderById(reminderId)
                            if (reminder != null) {
                                val snoozeMins = reminder.snoozeMinutes.coerceAtLeast(1)
                                val snoozeTrigger = System.currentTimeMillis() + snoozeMins * 60 * 1000L
                                AlarmScheduler.scheduleAt(
                                    context = context,
                                    id = 700000L + reminderId,
                                    triggerAt = snoozeTrigger,
                                    extras = mapOf(
                                        AlarmScheduler.EXTRA_TYPE to AlarmScheduler.TYPE_REMINDER,
                                        AlarmScheduler.EXTRA_ID to reminderId,
                                        AlarmScheduler.EXTRA_TITLE to reminder.title,
                                        AlarmScheduler.EXTRA_MESSAGE to reminder.customNotificationMessage,
                                        AlarmScheduler.EXTRA_CATEGORY to reminder.category,
                                        AlarmScheduler.EXTRA_SNOOZE_MINUTES to snoozeMins
                                    )
                                )
                                db.reminderDao().insertReminderLog(
                                    ReminderLogEntity(
                                        reminderId = reminderId,
                                        reminderTitle = reminder.title,
                                        action = ReminderLogAction.SNOOZED,
                                        dateString = today
                                    )
                                )
                            }
                        }
                    }

                    NotificationHelper.ACTION_SKIP -> {
                        if (isTask) {
                            val task = db.taskDao().getTaskById(taskId)
                            if (task != null) {
                                db.taskDao().updateTask(task.copy(outcome = TaskOutcome.SKIPPED))
                                AlarmScheduler.cancelTask(context, taskId)
                            }
                        } else {
                            db.reminderDao().insertReminderLog(
                                ReminderLogEntity(
                                    reminderId = reminderId,
                                    reminderTitle = title,
                                    action = ReminderLogAction.SKIPPED,
                                    dateString = today
                                )
                            )
                            val reminder = db.reminderDao().getReminderById(reminderId)
                            if (reminder != null && reminder.isEnabled) {
                                if (reminder.frequency == ReminderFrequency.ONE_TIME) {
                                    db.reminderDao().updateReminder(reminder.copy(isEnabled = false))
                                } else {
                                    val nextTrigger = AlarmScheduler.calculateNextReminderTrigger(reminder)
                                    val updated = reminder.copy(nextTriggerTimestamp = nextTrigger)
                                    db.reminderDao().updateReminder(updated)
                                    AlarmScheduler.scheduleReminder(context, updated)
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Safeguard against background crashes
            } finally {
                pendingResult.finish()
            }
        }
    }
}
