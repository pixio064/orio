package com.example

import com.example.ai.HabitCoachEngine
import com.example.data.local.entity.*
import com.example.scheduler.AlarmScheduler
import com.example.usage.AppUsageInfo
import com.example.usage.DayUsageSummary
import com.example.usage.UsageStatsHelper
import org.junit.Assert.*
import org.junit.Test
import java.util.*

class FocusTrackLogicTest {

    @Test
    fun testDayRangeCalculation() {
        val calendar = Calendar.getInstance()
        val (start, end) = UsageStatsHelper.getDayRange(calendar)

        assertTrue("End time must be greater than or equal to start time", end >= start)

        val calStart = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, calStart.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calStart.get(Calendar.MINUTE))
        assertEquals(0, calStart.get(Calendar.SECOND))
    }

    @Test
    fun testCalculateNextReminderTrigger_Interval() {
        val reminder = ReminderEntity(
            id = 1L,
            title = "Hydration",
            frequency = ReminderFrequency.INTERVAL_MINUTES,
            intervalMinutes = 30,
            startHour = 9,
            endHour = 21,
            isEnabled = true
        )

        val triggerTime = AlarmScheduler.calculateNextReminderTrigger(reminder)
        assertTrue("Trigger time must be in the future", triggerTime > System.currentTimeMillis() - 1000)
    }

    @Test
    fun testHabitCoachInsightsGeneration() {
        val now = System.currentTimeMillis()
        val appList = listOf(
            AppUsageInfo(
                packageName = "com.test.app",
                appName = "Test App",
                totalTimeInForegroundMs = 45 * 60 * 1000L,
                launchCount = 12,
                lastTimeUsed = now,
                category = "Social",
                averageSessionDurationMs = 2 * 60 * 1000L
            )
        )

        val summary = DayUsageSummary(
            dateString = "2026-10-16",
            totalScreenOnTimeMs = 50 * 60 * 1000L,
            totalScreenOffTimeMs = 100 * 60 * 1000L,
            estimatedPhoneAwayTimeMs = 60 * 60 * 1000L,
            phoneSessionsCount = 10,
            appLaunchesCount = 15,
            mostUsedApp = "Test App",
            mostRecentApp = "Test App",
            longestSessionMs = 20 * 60 * 1000L,
            longestPhoneFreeIntervalMs = 45 * 60 * 1000L,
            firstUsageTimestamp = now - 3600000,
            lastUsageTimestamp = now,
            appUsages = appList,
            timelineEvents = emptyList(),
            reconstructedSessions = emptyList()
        )

        val tasks = listOf(
            TaskEntity(
                id = 1L,
                title = "Study Math",
                dateString = "2026-10-16",
                outcome = TaskOutcome.COMPLETED
            )
        )

        val habits = listOf(
            HabitEntity(
                id = 1L,
                name = "Drink Water",
                currentStreak = 5,
                bestStreak = 7
            )
        )

        val insights = HabitCoachEngine.generateInsights(
            usageSummary = summary,
            screenTimeGoalMinutes = 180,
            tasks = tasks,
            habits = habits
        )

        assertFalse("Insights must not be empty", insights.isEmpty())
        assertTrue("Must detect screen time on track", insights.any { it.id == "goal_on_track" })
        assertTrue("Must detect streak spotlight", insights.any { it.id == "habit_streak" })
    }

    @Test
    fun testUsageCalculatorSummary() {
        val testDate = java.time.LocalDate.of(2026, 10, 16)
        val zoneId = java.time.ZoneId.of("UTC")
        val dayBoundaries = com.example.usage.UsageCalculator.getDayBoundaries(testDate, zoneId)
        val data = com.example.usage.UsageCalculator.computeFromEvents(
            rawEvents = emptyList(),
            date = testDate,
            dayStartMs = dayBoundaries.first,
            dayEndMs = dayBoundaries.second,
            isToday = false,
            currentTimeMs = dayBoundaries.second,
            hasPermission = true,
            isSystemNoiseCheck = { false },
            appNameResolver = { it },
            categoryResolver = { "General" }
        )
        assertEquals(0L, data.totalScreenOnMs)
        assertTrue("Empty events produce empty app usages", data.appUsages.isEmpty())
    }
}
