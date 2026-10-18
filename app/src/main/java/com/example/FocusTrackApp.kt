package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.notification.NotificationHelper
import com.example.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FocusTrackApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        preferencesRepository = UserPreferencesRepository(this)

        // Initialize notification channels with high priority heads-up popups
        NotificationHelper.createNotificationChannels(this)

        // Schedule daily planning reminder (default 11:00 PM)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = preferencesRepository.userSettingsFlow.first()
                if (settings.isPlanningReminderEnabled) {
                    AlarmScheduler.scheduleDailyPlannerReminder(
                        this@FocusTrackApp,
                        settings.planningReminderHour,
                        settings.planningReminderMinute
                    )
                }
            } catch (e: Exception) {
                // Graceful ignore
            }
        }
    }

    companion object {
        lateinit var instance: FocusTrackApp
            private set
    }
}
