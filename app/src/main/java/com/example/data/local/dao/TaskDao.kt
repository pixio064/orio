package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskOutcome
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE dateString = :date ORDER BY taskType ASC, startTimeHour ASC, startTimeMinute ASC")
    fun getTasksForDate(date: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE dateString = :date ORDER BY taskType ASC, startTimeHour ASC, startTimeMinute ASC")
    suspend fun getTasksForDateSync(date: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("UPDATE tasks SET outcome = :outcome, outcomeTimestamp = :timestamp WHERE id = :taskId")
    suspend fun updateTaskOutcome(taskId: Long, outcome: TaskOutcome, timestamp: Long)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("SELECT * FROM tasks WHERE outcome = 'COMPLETED'")
    fun getAllCompletedTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>
}
