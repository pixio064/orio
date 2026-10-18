package com.example.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.ReminderFrequency
import com.example.data.local.entity.TaskEntity
import com.example.receiver.AlarmReceiver
import java.util.*

object AlarmScheduler {

    const val EXTRA_TYPE = "EXTRA_TYPE"
    const val TYPE_REMINDER = "TYPE_REMINDER"
    const val TYPE_TASK = "TYPE_TASK"
    const val TYPE_PLANNER = "TYPE_PLANNER"
    const val EXTRA_ID = "EXTRA_ID"
    const val EXTRA_TITLE = "EXTRA_TITLE"
    const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
    const val EXTRA_CATEGORY = "EXTRA_CATEGORY"

    fun scheduleReminder(context: Context, reminder: ReminderEntity) {
        if (!reminder.isEnabled) return

        val triggerTime = calculateNextReminderTrigger(reminder)
        if (triggerTime <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_REMINDER)
            putExtra(EXTRA_ID, reminder.id)
            putExtra(EXTRA_TITLE, reminder.title)
            putExtra(EXTRA_MESSAGE, reminder.customNotificationMessage)
            putExtra(EXTRA_CATEGORY, reminder.category)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(context, alarmManager, triggerTime, pendingIntent)
    }

    fun cancelReminder(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun scheduleTask(context: Context, task: TaskEntity) {
        if (task.reminderMinutesBefore < 0) return

        // Calculate task start time
        val calendar = Calendar.getInstance().apply {
            val parts = task.dateString.split("-")
            if (parts.size == 3) {
                set(Calendar.YEAR, parts[0].toInt())
                set(Calendar.MONTH, parts[1].toInt() - 1)
                set(Calendar.DAY_OF_MONTH, parts[2].toInt())
            }
            set(Calendar.HOUR_OF_DAY, task.startTimeHour)
            set(Calendar.MINUTE, task.startTimeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, -task.reminderMinutesBefore)
        }

        val triggerTime = calendar.timeInMillis
        if (triggerTime <= System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_TASK)
            putExtra(EXTRA_ID, task.id)
            putExtra(EXTRA_TITLE, task.title)
            putExtra(EXTRA_MESSAGE, if (task.reminderMinutesBefore == 0) "Starting now!" else "Starts in ${task.reminderMinutesBefore} minutes.")
            putExtra(EXTRA_CATEGORY, task.category)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (100000 + task.id).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(context, alarmManager, triggerTime, pendingIntent)
    }

    fun cancelTask(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (100000 + taskId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun scheduleDailyPlannerReminder(context: Context, hour: Int = 23, minute: Int = 0) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_PLANNER)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            99901,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(context, alarmManager, calendar.timeInMillis, pendingIntent)
    }

    private fun scheduleAlarm(
        context: Context,
        alarmManager: AlarmManager,
        triggerTime: Long,
        pendingIntent: PendingIntent
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun calculateNextReminderTrigger(reminder: ReminderEntity): Long {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        when (reminder.frequency) {
            ReminderFrequency.INTERVAL_MINUTES -> {
                // If within active window (startHour:startMinute to endHour:endMinute)
                val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                val currentMin = calendar.get(Calendar.MINUTE)
                val currentMinsOfDay = currentHour * 60 + currentMin
                val startMinsOfDay = reminder.startHour * 60 + reminder.startMinute
                val endMinsOfDay = reminder.endHour * 60 + reminder.endMinute

                return if (currentMinsOfDay in startMinsOfDay until endMinsOfDay) {
                    now + reminder.intervalMinutes * 60 * 1000L
                } else {
                    // Schedule for start of next window
                    val nextStart = calendar.clone() as Calendar
                    if (currentMinsOfDay >= endMinsOfDay) {
                        nextStart.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    nextStart.set(Calendar.HOUR_OF_DAY, reminder.startHour)
                    nextStart.set(Calendar.MINUTE, reminder.startMinute)
                    nextStart.set(Calendar.SECOND, 0)
                    nextStart.timeInMillis
                }
            }
            ReminderFrequency.DAILY -> {
                calendar.set(Calendar.HOUR_OF_DAY, reminder.specificHour)
                calendar.set(Calendar.MINUTE, reminder.specificMinute)
                calendar.set(Calendar.SECOND, 0)
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                return calendar.timeInMillis
            }
            ReminderFrequency.WEEKDAYS -> {
                calendar.set(Calendar.HOUR_OF_DAY, reminder.specificHour)
                calendar.set(Calendar.MINUTE, reminder.specificMinute)
                calendar.set(Calendar.SECOND, 0)
                while (calendar.timeInMillis <= now ||
                    calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                    calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                return calendar.timeInMillis
            }
            ReminderFrequency.WEEKLY -> {
                calendar.set(Calendar.HOUR_OF_DAY, reminder.specificHour)
                calendar.set(Calendar.MINUTE, reminder.specificMinute)
                calendar.set(Calendar.SECOND, 0)
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.WEEK_OF_YEAR, 1)
                }
                return calendar.timeInMillis
            }
            ReminderFrequency.ONE_TIME -> {
                return reminder.nextTriggerTimestamp.coerceAtLeast(now + 60 * 1000L)
            }
        }
    }
}
