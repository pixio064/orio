package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ReminderFrequency {
    ONE_TIME,
    INTERVAL_MINUTES, // repeats every X minutes (e.g. 15, 30, 45, 60m)
    DAILY,
    WEEKDAYS,
    WEEKLY
}

enum class ReminderLogAction {
    DONE,
    SNOOZED,
    SKIPPED,
    MISSED
}

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val category: String = "Hydration", // Hydration, Focus, Stretch, Anti-Scroll, Eye-Rest, Medicine, Custom
    val iconName: String = "WaterDrop",
    val customNotificationMessage: String = "",
    val frequency: ReminderFrequency = ReminderFrequency.INTERVAL_MINUTES,
    val intervalMinutes: Int = 30, // 15, 30, 45, 60, etc.
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = 21, // 9 PM
    val endMinute: Int = 0,
    val specificHour: Int = 9,
    val specificMinute: Int = 0,
    val snoozeMinutes: Int = 10,
    val isEnabled: Boolean = true,
    val priorityLevel: Int = 2, // 1: Low, 2: High (Heads-up)
    val nextTriggerTimestamp: Long = 0L,
    val lastTriggerTimestamp: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminder_logs")
data class ReminderLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val reminderId: Long,
    val reminderTitle: String,
    val timestamp: Long = System.currentTimeMillis(),
    val action: ReminderLogAction = ReminderLogAction.DONE,
    val dateString: String // "YYYY-MM-DD"
)
