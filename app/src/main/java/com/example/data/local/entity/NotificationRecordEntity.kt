package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification_records")
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val timestamp: Long,
    val hourOfDay: Int, // 0..23
    val dateString: String // "YYYY-MM-DD"
)

data class HourlyNotificationCount(
    val hourOfDay: Int,
    val count: Int
)
