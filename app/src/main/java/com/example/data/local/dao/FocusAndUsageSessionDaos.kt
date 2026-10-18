package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.FocusSessionEntity
import com.example.data.local.entity.UsageSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions ORDER BY startTimestamp DESC")
    fun getAllFocusSessions(): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE dateString = :dateString ORDER BY startTimestamp DESC")
    fun getFocusSessionsForDate(dateString: String): Flow<List<FocusSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFocusSession(session: FocusSessionEntity): Long

    @Query("SELECT SUM(completedMinutes) FROM focus_sessions WHERE dateString = :dateString")
    fun getTotalFocusMinutesForDate(dateString: String): Flow<Int?>
}

@Dao
interface UsageSessionDao {
    @Query("SELECT * FROM usage_sessions WHERE dateString = :dateString ORDER BY startTimestamp DESC")
    fun getSessionsForDate(dateString: String): Flow<List<UsageSessionEntity>>

    @Query("SELECT * FROM usage_sessions WHERE dateString = :dateString ORDER BY startTimestamp DESC")
    suspend fun getSessionsForDateSync(dateString: String): List<UsageSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<UsageSessionEntity>)

    @Query("DELETE FROM usage_sessions WHERE dateString = :dateString")
    suspend fun deleteSessionsForDate(dateString: String)

    @Query("DELETE FROM usage_sessions")
    suspend fun clearAll()
}
