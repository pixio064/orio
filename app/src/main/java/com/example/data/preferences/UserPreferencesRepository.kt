package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

data class UserSettings(
    val isOnboardingCompleted: Boolean = false,
    val dailyScreenTimeGoalMinutes: Int = 180, // 3 hours
    val planningReminderHour: Int = 23, // 11 PM
    val planningReminderMinute: Int = 0,
    val isPlanningReminderEnabled: Boolean = true,
    val isDarkMode: Boolean? = null,
    val language: String = "en", // "en" or "bn"
    val isDemoModeEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: Int = 22,
    val quietHoursEnd: Int = 7
)

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SCREEN_TIME_GOAL = intPreferencesKey("screen_time_goal_minutes")
        val PLAN_REMINDER_HOUR = intPreferencesKey("plan_reminder_hour")
        val PLAN_REMINDER_MIN = intPreferencesKey("plan_reminder_min")
        val PLAN_REMINDER_ENABLED = booleanPreferencesKey("plan_reminder_enabled")
        val DARK_MODE = stringPreferencesKey("dark_mode_setting")
        val LANGUAGE = stringPreferencesKey("app_language")
        val DEMO_MODE = booleanPreferencesKey("demo_mode_enabled")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
    }

    val userSettingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val darkModeStr = prefs[PreferencesKeys.DARK_MODE]
        val darkModeVal = when (darkModeStr) {
            "light" -> false
            "dark" -> true
            else -> null
        }
        UserSettings(
            isOnboardingCompleted = prefs[PreferencesKeys.ONBOARDING_COMPLETED] ?: false,
            dailyScreenTimeGoalMinutes = prefs[PreferencesKeys.SCREEN_TIME_GOAL] ?: 180,
            planningReminderHour = prefs[PreferencesKeys.PLAN_REMINDER_HOUR] ?: 23,
            planningReminderMinute = prefs[PreferencesKeys.PLAN_REMINDER_MIN] ?: 0,
            isPlanningReminderEnabled = prefs[PreferencesKeys.PLAN_REMINDER_ENABLED] ?: true,
            isDarkMode = darkModeVal,
            language = prefs[PreferencesKeys.LANGUAGE] ?: "en",
            isDemoModeEnabled = prefs[PreferencesKeys.DEMO_MODE] ?: false,
            quietHoursEnabled = prefs[PreferencesKeys.QUIET_HOURS_ENABLED] ?: false,
            quietHoursStart = prefs[PreferencesKeys.QUIET_HOURS_START] ?: 22,
            quietHoursEnd = prefs[PreferencesKeys.QUIET_HOURS_END] ?: 7
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setScreenTimeGoal(minutes: Int) {
        context.dataStore.edit { it[PreferencesKeys.SCREEN_TIME_GOAL] = minutes }
    }

    suspend fun setPlanningReminder(hour: Int, minute: Int, enabled: Boolean) {
        context.dataStore.edit {
            it[PreferencesKeys.PLAN_REMINDER_HOUR] = hour
            it[PreferencesKeys.PLAN_REMINDER_MIN] = minute
            it[PreferencesKeys.PLAN_REMINDER_ENABLED] = enabled
        }
    }

    suspend fun setDarkMode(mode: Boolean?) {
        context.dataStore.edit {
            when (mode) {
                true -> it[PreferencesKeys.DARK_MODE] = "dark"
                false -> it[PreferencesKeys.DARK_MODE] = "light"
                null -> it[PreferencesKeys.DARK_MODE] = "system"
            }
        }
    }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { it[PreferencesKeys.LANGUAGE] = lang }
    }

    suspend fun setDemoMode(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DEMO_MODE] = enabled }
    }

    suspend fun setQuietHours(enabled: Boolean, start: Int, end: Int) {
        context.dataStore.edit {
            it[PreferencesKeys.QUIET_HOURS_ENABLED] = enabled
            it[PreferencesKeys.QUIET_HOURS_START] = start
            it[PreferencesKeys.QUIET_HOURS_END] = end
        }
    }
}
