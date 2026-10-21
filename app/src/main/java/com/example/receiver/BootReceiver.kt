package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.scheduler.AlarmScheduler
import com.example.util.DateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)

                    // 1. Reschedule all active reminders
                    val activeReminders = db.reminderDao().getActiveRemindersSync()
                    for (reminder in activeReminders) {
                        AlarmScheduler.scheduleReminder(context, reminder)
                    }

                    // 2. Reschedule all future and today's pending tasks
                    val today = DateKeys.today()
                    val tasks = db.taskDao().getPendingTasksFromDateSync(today)
                    for (task in tasks) {
                        AlarmScheduler.scheduleTask(context, task)
                    }

                    // 3. Reschedule habit reminders
                    val habits = db.habitDao().getAllActiveHabitsSync()
                    for (habit in habits) {
                        AlarmScheduler.scheduleHabit(context, habit)
                    }

                    // 4. Reschedule daily planning reminder
                    val prefs = UserPreferencesRepository(context)
                    val settings = prefs.userSettingsFlow.first()
                    if (settings.isPlanningReminderEnabled) {
                        AlarmScheduler.scheduleDailyPlannerReminder(
                            context = context,
                            hour = settings.planningReminderHour,
                            minute = settings.planningReminderMinute
                        )
                    } else {
                        AlarmScheduler.cancelDailyPlannerReminder(context)
                    }

                    // 5. Schedule periodic limit check
                    AlarmScheduler.scheduleLimitCheckTick(context)
                } catch (_: Throwable) {
                    // Fail-safe against crashes during boot recovery
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
