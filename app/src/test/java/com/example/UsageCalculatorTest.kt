package com.example

import android.app.usage.UsageEvents
import com.example.usage.RawEvent
import com.example.usage.UsageCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageCalculatorTest {

    private val zoneId: ZoneId = ZoneId.of("UTC")
    private val testDate: LocalDate = LocalDate.of(2026, 10, 18)
    private val dayBoundaries: Pair<Long, Long> = UsageCalculator.getDayBoundaries(testDate, zoneId)
    private val dayStartMs: Long = dayBoundaries.first
    private val dayEndMs: Long = dayBoundaries.second

    @Test
    fun testAppOpenAcrossMidnight_clippedToDayStart() {
        // App opened 20 minutes before midnight (previous day) and closed 30 minutes after midnight (today)
        val twentyMinsBeforeMidnight = dayStartMs - (20 * 60 * 1000L)
        val thirtyMinsAfterMidnight = dayStartMs + (30 * 60 * 1000L)

        val events = listOf(
            RawEvent(
                timestamp = twentyMinsBeforeMidnight,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                packageName = "com.example.chat"
            ),
            RawEvent(
                timestamp = thirtyMinsAfterMidnight,
                eventType = UsageEvents.Event.ACTIVITY_PAUSED,
                packageName = "com.example.chat"
            )
        )

        val result = UsageCalculator.computeFromEvents(
            rawEvents = events,
            date = testDate,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            isToday = false,
            currentTimeMs = dayEndMs,
            hasPermission = true,
            isSystemNoiseCheck = { false },
            appNameResolver = { "ChatApp" },
            categoryResolver = { "Social" }
        )

        assertEquals(1, result.appUsages.size)
        val app = result.appUsages.first()
        // Must only count the 30 minutes that belong to today
        val expectedDurationMs = 30 * 60 * 1000L
        assertEquals(expectedDurationMs, app.totalTimeInForegroundMs)
        // First hour (00:00 - 01:00) bucket should contain exactly 30 minutes
        assertEquals(expectedDurationMs, app.hourlyScreenTimeMs[0])
    }

    @Test
    fun testAppStillInForeground_countedUntilNow() {
        // App opened at 10:00 AM on today, and currently it is 10:45 AM (no pause event yet)
        val tenAm = dayStartMs + (10 * 3600 * 1000L)
        val tenFortyFiveAm = tenAm + (45 * 60 * 1000L)

        val events = listOf(
            RawEvent(
                timestamp = tenAm,
                eventType = UsageEvents.Event.ACTIVITY_RESUMED,
                packageName = "com.example.notes"
            )
        )

        val result = UsageCalculator.computeFromEvents(
            rawEvents = events,
            date = testDate,
            dayStartMs = dayStartMs,
            dayEndMs = tenFortyFiveAm,
            isToday = true,
            currentTimeMs = tenFortyFiveAm,
            hasPermission = true,
            isSystemNoiseCheck = { false },
            appNameResolver = { "Notes" },
            categoryResolver = { "Productivity" }
        )

        assertEquals(1, result.appUsages.size)
        val app = result.appUsages.first()
        val expectedDurationMs = 45 * 60 * 1000L
        assertEquals(expectedDurationMs, app.totalTimeInForegroundMs)
        assertEquals(expectedDurationMs, app.hourlyScreenTimeMs[10])
    }

    @Test
    fun testOverlappingAndDuplicateEvents_handledGracefully() {
        val t1 = dayStartMs + (1 * 3600 * 1000L)
        val t2 = t1 + (10 * 60 * 1000L)
        val t3 = t2 + (15 * 60 * 1000L)

        val events = listOf(
            // Duplicate resumes
            RawEvent(t1, UsageEvents.Event.ACTIVITY_RESUMED, "com.example.browser"),
            RawEvent(t1 + 1000L, UsageEvents.Event.ACTIVITY_RESUMED, "com.example.browser"),
            // Switch to another app without explicit pause
            RawEvent(t2, UsageEvents.Event.ACTIVITY_RESUMED, "com.example.music"),
            RawEvent(t3, UsageEvents.Event.ACTIVITY_PAUSED, "com.example.music")
        )

        val result = UsageCalculator.computeFromEvents(
            rawEvents = events,
            date = testDate,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            isToday = false,
            currentTimeMs = dayEndMs,
            hasPermission = true,
            isSystemNoiseCheck = { false },
            appNameResolver = { it.substringAfterLast('.') },
            categoryResolver = { "General" }
        )

        assertEquals(2, result.appUsages.size)
        val browser = result.appUsages.find { it.packageName == "com.example.browser" }
        val music = result.appUsages.find { it.packageName == "com.example.music" }

        assertTrue(browser != null)
        assertTrue(music != null)
        assertEquals(10 * 60 * 1000L, browser!!.totalTimeInForegroundMs)
        assertEquals(15 * 60 * 1000L, music!!.totalTimeInForegroundMs)
    }

    @Test
    fun testEmptyDay_returnsZeroUsage() {
        val result = UsageCalculator.computeFromEvents(
            rawEvents = emptyList(),
            date = testDate,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            isToday = false,
            currentTimeMs = dayEndMs,
            hasPermission = true,
            isSystemNoiseCheck = { false },
            appNameResolver = { it },
            categoryResolver = { "General" }
        )

        assertEquals(0L, result.totalScreenOnMs)
        assertTrue(result.appUsages.isEmpty())
        assertTrue(result.screenSessions.isEmpty())
        assertTrue(result.lastTwoAppsBeforeLock.isEmpty())
        assertTrue(result.inactivityGaps.isEmpty())
    }

    @Test
    fun testDstDayBoundaries() {
        // Test date around DST changes (e.g., US Fall transition in November)
        val dstDate = LocalDate.of(2026, 11, 1)
        val nyZone = ZoneId.of("America/New_York")
        val (startMs, endMs) = UsageCalculator.getDayBoundaries(dstDate, nyZone)

        assertTrue(endMs > startMs)
        val durationHours = (endMs - startMs + 1) / (3600 * 1000.0)
        // DST fall-back day in NY has 25 hours
        assertEquals(25.0, durationHours, 0.1)
    }
}
