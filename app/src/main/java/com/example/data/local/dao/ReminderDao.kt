package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.ReminderLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY createdAt DESC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isEnabled = 1")
    suspend fun getActiveRemindersSync(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: Long): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Update
    suspend fun updateReminder(reminder: ReminderEntity)

    @Delete
    suspend fun deleteReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Long)

    // Logs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminderLog(log: ReminderLogEntity)

    @Query("SELECT * FROM reminder_logs WHERE dateString = :dateString ORDER BY timestamp DESC")
    fun getReminderLogsForDate(dateString: String): Flow<List<ReminderLogEntity>>

    @Query("SELECT * FROM reminder_logs ORDER BY timestamp DESC LIMIT 100")
    fun getRecentReminderLogs(): Flow<List<ReminderLogEntity>>
}
