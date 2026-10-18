package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.HourlyNotificationCount
import com.example.data.local.entity.NotificationRecordEntity

@Dao
interface NotificationRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: NotificationRecordEntity)

    @Query("SELECT COUNT(*) FROM notification_records WHERE packageName = :packageName AND dateString = :dateString")
    suspend fun getTotalForAppOnDate(packageName: String, dateString: String): Int

    @Query("SELECT hourOfDay, COUNT(*) as count FROM notification_records WHERE packageName = :packageName AND dateString = :dateString GROUP BY hourOfDay")
    suspend fun getHourlyCountsForApp(packageName: String, dateString: String): List<HourlyNotificationCount>

    @Query("SELECT hourOfDay, COUNT(*) as count FROM notification_records WHERE packageName = :packageName AND dateString = :dateString GROUP BY hourOfDay")
    fun getHourlyCountsForAppSync(packageName: String, dateString: String): List<HourlyNotificationCount>

    @Query("DELETE FROM notification_records WHERE timestamp < :cutoffTimestamp")
    suspend fun pruneOldRecords(cutoffTimestamp: Long)
}
