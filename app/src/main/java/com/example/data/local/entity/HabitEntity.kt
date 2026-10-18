package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val category: String = "Health", // Health, Study, Wellbeing, Mind, Fitness
    val iconName: String = "WaterDrop",
    val targetDaysPerWeek: Int = 7,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val lastCompletedDate: String = "", // "YYYY-MM-DD"
    val reminderHour: Int = -1, // -1 if no reminder
    val reminderMinute: Int = -1,
    val isPaused: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "habit_logs", primaryKeys = ["habitId", "dateString"])
data class HabitLogEntity(
    val habitId: Long,
    val dateString: String, // "YYYY-MM-DD"
    val completedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)
