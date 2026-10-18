package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    // 1. Reschedule all active reminders
                    val activeReminders = db.reminderDao().getActiveRemindersSync()
                    for (reminder in activeReminders) {
                        AlarmScheduler.scheduleReminder(context, reminder)
                    }

                    // 2. Reschedule today's tasks
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val today = dateFormat.format(Date())
                    val tasks = db.taskDao().getTasksForDateSync(today)
                    for (task in tasks) {
                        AlarmScheduler.scheduleTask(context, task)
                    }

                    // 3. Reschedule daily planning reminder (11 PM)
                    val prefs = UserPreferencesRepository(context)
                    val settings = prefs.userSettingsFlow.first()
                    if (settings.isPlanningReminderEnabled) {
                        AlarmScheduler.scheduleDailyPlannerReminder(
                            context,
                            settings.planningReminderHour,
                            settings.planningReminderMinute
                        )
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
