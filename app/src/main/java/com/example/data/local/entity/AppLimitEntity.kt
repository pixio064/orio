package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_limits")
data class AppLimitEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val limitMinutes: Int, // e.g. 30 minutes
    val warningThresholdPercent: Int = 80, // default 80%
    val isEnabled: Boolean = true,
    val category: String = "General", // Social, Entertainment, Productivity, Education, Gaming, General
    val notifyOnLimit: Boolean = true,
    val notifyOnWarning: Boolean = true,
    val lastNotifiedDate: String = "" // "YYYY-MM-DD"
)
