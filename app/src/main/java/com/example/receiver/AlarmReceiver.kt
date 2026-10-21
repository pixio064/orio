package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.data.local.entity.ReminderFrequency
import com.example.data.local.entity.TaskOutcome
import com.example.data.preferences.UserPreferencesRepository
import com.example.notification.NotificationHelper
import com.example.scheduler.AlarmScheduler
import com.example.usage.AppLimitEnforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TYPE) ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val prefsRepo = UserPreferencesRepository(context)
                val settings = prefsRepo.userSettingsFlow.first()

                val isQuiet = settings.quietHoursEnabled && isQuietHours(settings.quietHoursStart, settings.quietHoursEnd)

                when (type) {
                    AlarmScheduler.TYPE_REMINDER -> {
                        val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
                        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Reminder"
                        val message = intent.getStringExtra(AlarmScheduler.EXTRA_MESSAGE) ?: ""
                        val category = intent.getStringExtra(AlarmScheduler.EXTRA_CATEGORY) ?: "General"
                        val snoozeMinutes = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_MINUTES, 10)

                        if (!isQuiet) {
                            NotificationHelper.showReminderNotification(
                                context = context,
                                reminderId = reminderId,
                                title = title,
                                message = message,
                                category = category,
                                snoozeMinutes = snoozeMinutes
                            )
                        }

                        // Reschedule next occurrence or disable if ONE_TIME
                        if (reminderId != -1L) {
                            val reminder = db.reminderDao().getReminderById(reminderId)
                            if (reminder != null && reminder.isEnabled) {
                                if (reminder.frequency == ReminderFrequency.ONE_TIME) {
                                    val updated = reminder.copy(
                                        isEnabled = false,
                                        lastTriggerTimestamp = System.currentTimeMillis()
                                    )
                                    db.reminderDao().updateReminder(updated)
                                } else {
                                    val nextTrigger = AlarmScheduler.calculateNextReminderTrigger(reminder)
                                    val updated = reminder.copy(
                                        lastTriggerTimestamp = System.currentTimeMillis(),
                                        nextTriggerTimestamp = nextTrigger
                                    )
                                    db.reminderDao().updateReminder(updated)
                                    AlarmScheduler.scheduleReminder(context, updated)
                                }
                            }
                        }
                    }

                    AlarmScheduler.TYPE_TASK -> {
                        val taskId = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
                        if (taskId == -1L) return@launch

                        val task = db.taskDao().getTaskById(taskId)
                        // Verify task still exists and is PENDING
                        if (task == null || task.outcome != TaskOutcome.PENDING) {
                            return@launch
                        }

                        if (!isQuiet) {
                            val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: task.title
                            val message = intent.getStringExtra(AlarmScheduler.EXTRA_MESSAGE) ?: "Task starting soon"
                            val category = intent.getStringExtra(AlarmScheduler.EXTRA_CATEGORY) ?: task.category

                            NotificationHelper.showReminderNotification(
                                context = context,
                                reminderId = 500000L + taskId,
                                title = title,
                                message = message,
                                category = category,
                                snoozeMinutes = 10
                            )
                        }
                    }

                    AlarmScheduler.TYPE_HABIT -> {
                        val habitId = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
                        if (habitId == -1L) return@launch

                        val habit = db.habitDao().getHabitById(habitId)
                        if (habit == null || habit.isPaused) {
                            return@launch
                        }

                        if (!isQuiet) {
                            val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: habit.name
                            val message = intent.getStringExtra(AlarmScheduler.EXTRA_MESSAGE) ?: "Time for your habit"
                            val category = intent.getStringExtra(AlarmScheduler.EXTRA_CATEGORY) ?: habit.category

                            NotificationHelper.showReminderNotification(
                                context = context,
                                reminderId = 200000L + habitId,
                                title = title,
                                message = message,
                                category = category,
                                snoozeMinutes = 10
                            )
                        }

                        // Reschedule for tomorrow
                        AlarmScheduler.scheduleHabit(context, habit)
                    }

                    AlarmScheduler.TYPE_PLANNER -> {
                        if (settings.isPlanningReminderEnabled) {
                            if (!isQuiet) {
                                NotificationHelper.showPlanningReminder(context)
                            }
                            // Reschedule using saved hour and minute
                            AlarmScheduler.scheduleDailyPlannerReminder(
                                context = context,
                                hour = settings.planningReminderHour,
                                minute = settings.planningReminderMinute
                            )
                        }
                    }

                    AlarmScheduler.TYPE_LIMIT_CHECK -> {
                        AppLimitEnforcer.checkLimits(context)
                        AlarmScheduler.scheduleLimitCheckTick(context)
                    }
                }
            } catch (_: Throwable) {
                // Prevent unhandled crashes in broadcast
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isQuietHours(startHour: Int, endHour: Int): Boolean {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            currentHour in startHour until endHour
        } else {
            currentHour >= startHour || currentHour < endHour
        }
    }
}
