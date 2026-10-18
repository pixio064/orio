package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "Focus Session",
    val startTimestamp: Long,
    val endTimestamp: Long,
    val targetMinutes: Int,
    val completedMinutes: Int,
    val isCompleted: Boolean,
    val linkedTaskId: Long? = null,
    val mode: String = "POMODORO", // POMODORO, DEEP_WORK, CUSTOM
    val notes: String = "",
    val dateString: String // "YYYY-MM-DD"
)

@Entity(tableName = "usage_sessions")
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long,
    val screenState: String, // SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE
    val mostRecentApp: String, // package name or app name
    val appsObservedJson: String = "[]",
    val appTransitions: Int = 1,
    val isEstimated: Boolean = false,
    val source: String = "UsageEvents", // "UsageEvents", "Reconstructed", "SampleDemo"
    val dateString: String // "YYYY-MM-DD"
)
