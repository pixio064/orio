package com.example.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.entity.HabitEntity
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.ReminderFrequency
import com.example.data.local.entity.TaskEntity
import com.example.receiver.AlarmReceiver
import java.util.Calendar

object AlarmScheduler {

    const val EXTRA_TYPE = "EXTRA_TYPE"
    const val TYPE_REMINDER = "TYPE_REMINDER"
    const val TYPE_TASK = "TYPE_TASK"
    const val TYPE_PLANNER = "TYPE_PLANNER"
    const val TYPE_HABIT = "TYPE_HABIT"
    const val TYPE_LIMIT_CHECK = "TYPE_LIMIT_CHECK"

    const val EXTRA_ID = "EXTRA_ID"
    const val EXTRA_TITLE = "EXTRA_TITLE"
    const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
    const val EXTRA_CATEGORY = "EXTRA_CATEGORY"
    const val EXTRA_SNOOZE_MINUTES = "EXTRA_SNOOZE_MINUTES"

    const val REQUEST_CODE_PLANNER = 99901
    const val REQUEST_CODE_LIMIT_CHECK = 88801

    fun scheduleAt(
        context: Context,
        id: Long,
        triggerAt: Long,
        extras: Map<String, Any?>
    ) {
        if (triggerAt <= System.currentTimeMillis()) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            extras.forEach { (k, v) ->
                when (v) {
                    is String -> putExtra(k, v)
                    is Long -> putExtra(k, v)
                    is Int -> putExtra(k, v)
                    is Boolean -> putExtra(k, v)
                }
            }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(context, alarmManager, triggerAt, pendingIntent)
    }

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
            putExtra(EXTRA_SNOOZE_MINUTES, reminder.snoozeMinutes)
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
            putExtra(
                EXTRA_MESSAGE,
                if (task.reminderMinutesBefore == 0) "Starting now!" else "Starts in ${task.reminderMinutesBefore} minutes."
            )
            putExtra(EXTRA_CATEGORY, task.category)
            putExtra(EXTRA_SNOOZE_MINUTES, 10)
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

    fun scheduleHabit(context: Context, habit: HabitEntity) {
        if (habit.isPaused) {
            cancelHabit(context, habit.id)
            return
        }

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, habit.reminderHour)
            set(Calendar.MINUTE, habit.reminderMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_HABIT)
            putExtra(EXTRA_ID, habit.id)
            putExtra(EXTRA_TITLE, habit.name)
            putExtra(EXTRA_MESSAGE, habit.description.ifBlank { "Time for your habit: ${habit.name}" })
            putExtra(EXTRA_CATEGORY, habit.category)
            putExtra(EXTRA_SNOOZE_MINUTES, 10)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (200000 + habit.id).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(context, alarmManager, calendar.timeInMillis, pendingIntent)
    }

    fun cancelHabit(context: Context, habitId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (200000 + habitId).toInt(),
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
            REQUEST_CODE_PLANNER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(context, alarmManager, calendar.timeInMillis, pendingIntent)
    }

    fun cancelDailyPlannerReminder(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_PLANNER)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PLANNER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun scheduleLimitCheckTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerTime = System.currentTimeMillis() + 15 * 60 * 1000L // 15 minutes
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, TYPE_LIMIT_CHECK)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_LIMIT_CHECK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )
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
        } catch (_: SecurityException) {
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
                val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
                val currentMin = calendar.get(Calendar.MINUTE)
                val currentMinsOfDay = currentHour * 60 + currentMin
                val startMinsOfDay = reminder.startHour * 60 + reminder.startMinute
                val endMinsOfDay = reminder.endHour * 60 + reminder.endMinute

                val intervalMs = reminder.intervalMinutes.coerceAtLeast(5) * 60 * 1000L
                val nextCandidateMs = now + intervalMs

                val candidateCal = Calendar.getInstance().apply { timeInMillis = nextCandidateMs }
                val candidateMins = candidateCal.get(Calendar.HOUR_OF_DAY) * 60 + candidateCal.get(Calendar.MINUTE)

                val isDaytime = startMinsOfDay <= endMinsOfDay

                val isCandidateInside = if (isDaytime) {
                    candidateMins in startMinsOfDay until endMinsOfDay
                } else {
                    candidateMins >= startMinsOfDay || candidateMins < endMinsOfDay
                }

                if (isCandidateInside) {
                    return nextCandidateMs
                } else {
                    // Schedule at next window start
                    val nextStartCal = Calendar.getInstance()
                    if (isDaytime) {
                        if (currentMinsOfDay >= endMinsOfDay) {
                            nextStartCal.add(Calendar.DAY_OF_YEAR, 1)
                        }
                    } else {
                        // Overnight: if current is between endMinsOfDay and startMinsOfDay, it's daytime outside window
                        if (currentMinsOfDay in endMinsOfDay until startMinsOfDay) {
                            // starts today at startMinsOfDay
                        } else {
                            // We are inside window now but candidate fell past morning end: next window starts tonight!
                            // Already today, startMinsOfDay is tonight.
                        }
                    }
                    nextStartCal.set(Calendar.HOUR_OF_DAY, reminder.startHour)
                    nextStartCal.set(Calendar.MINUTE, reminder.startMinute)
                    nextStartCal.set(Calendar.SECOND, 0)
                    nextStartCal.set(Calendar.MILLISECOND, 0)
                    if (nextStartCal.timeInMillis <= now) {
                        nextStartCal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    return nextStartCal.timeInMillis
                }
            }
            ReminderFrequency.DAILY -> {
                calendar.set(Calendar.HOUR_OF_DAY, reminder.specificHour)
                calendar.set(Calendar.MINUTE, reminder.specificMinute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                return calendar.timeInMillis
            }
            ReminderFrequency.WEEKDAYS -> {
                calendar.set(Calendar.HOUR_OF_DAY, reminder.specificHour)
                calendar.set(Calendar.MINUTE, reminder.specificMinute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
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
                calendar.set(Calendar.MILLISECOND, 0)
                if (calendar.timeInMillis <= now) {
                    calendar.add(Calendar.WEEK_OF_YEAR, 1)
                }
                return calendar.timeInMillis
            }
            ReminderFrequency.ONE_TIME -> {
                return if (reminder.nextTriggerTimestamp > now) {
                    reminder.nextTriggerTimestamp
                } else {
                    now + 60 * 1000L
                }
            }
        }
    }
}
