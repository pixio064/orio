package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notification_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `packageName` TEXT NOT NULL,
                `appName` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `hourOfDay` INTEGER NOT NULL,
                `dateString` TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_dateString` ON `tasks` (`dateString`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_logs_habitId` ON `habit_logs` (`habitId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_records_packageName_dateString` ON `notification_records` (`packageName`, `dateString`)")
    }
}

@Database(
    entities = [
        AppLimitEntity::class,
        TaskEntity::class,
        HabitEntity::class,
        HabitLogEntity::class,
        ReminderEntity::class,
        ReminderLogEntity::class,
        FocusSessionEntity::class,
        UsageSessionEntity::class,
        NotificationRecordEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appLimitDao(): AppLimitDao
    abstract fun taskDao(): TaskDao
    abstract fun habitDao(): HabitDao
    abstract fun reminderDao(): ReminderDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun usageSessionDao(): UsageSessionDao
    abstract fun notificationRecordDao(): NotificationRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focustrack_database.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
