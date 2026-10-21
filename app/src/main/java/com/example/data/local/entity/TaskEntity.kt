package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TaskOutcome {
    PENDING,
    COMPLETED,
    MISSED,
    SKIPPED,
    CARRIED
}

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}

enum class TaskType {
    SCHEDULED, // Has specific start and end time
    FLEXIBLE   // Checklist item without strict hour window
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val dateString: String, // "YYYY-MM-DD" e.g. "2026-10-16"
    val taskType: TaskType = TaskType.SCHEDULED,
    val startTimeHour: Int = 9,
    val startTimeMinute: Int = 0,
    val endTimeHour: Int = 10,
    val endTimeMinute: Int = 0,
    val category: String = "Work", // Work, Study, Health, Personal, Custom
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val outcome: TaskOutcome = TaskOutcome.PENDING,
    val outcomeTimestamp: Long = 0L, // when marked
    val reminderMinutesBefore: Int = 10, // 0 for exact start, -1 for none
    val isRecurring: Boolean = false,
    val repeatSchedule: String = "NONE", // DAILY, WEEKDAYS, WEEKLY
    val estimatedMinutes: Int = 30,
    val subtasksJson: String = "[]",
    val locationOrNote: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isCompleted: Boolean get() = outcome == TaskOutcome.COMPLETED
}
