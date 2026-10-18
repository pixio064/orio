package com.example

import com.example.ui.components.SegmentPalette
import com.example.ui.focus.FocusTimerManager
import com.example.usage.AppUsageInfo
import org.junit.Assert.*
import org.junit.Test

class FocusAndSegmentTest {

    @Test
    fun testBuildSegmentsWithSampleData() {
        val apps = listOf(
            AppUsageInfo("com.whatsapp", "WhatsApp", 60 * 60 * 1000L, 20, 0L, "Social"),
            AppUsageInfo("jp.konami.pesam", "eFootball™", 45 * 60 * 1000L, 5, 0L, "Gaming"),
            AppUsageInfo("com.google.android.youtube", "YouTube", 30 * 60 * 1000L, 8, 0L, "Entertainment"),
            AppUsageInfo("com.android.chrome", "Chrome", 15 * 60 * 1000L, 10, 0L, "Productivity"),
            AppUsageInfo("com.spotify.music", "Spotify", 10 * 60 * 1000L, 3, 0L, "Entertainment"),
            AppUsageInfo("com.duolingo", "Duolingo", 10 * 60 * 1000L, 2, 0L, "Education")
        )
        val totalMs = apps.sumOf { it.totalTimeInForegroundMs }

        val segments = SegmentPalette.buildSegments(apps, totalMs)

        // Should have top 4 apps + "Other Apps"
        assertEquals(5, segments.size)
        assertEquals("WhatsApp", segments[0].appName)
        assertEquals("eFootball™", segments[1].appName)
        assertEquals("YouTube", segments[2].appName)
        assertEquals("Chrome", segments[3].appName)
        assertEquals("Other Apps", segments[4].appName)

        // Total percentage should be approximately 100%
        val totalPercent = segments.sumOf { it.percentOfTotal }
        assertTrue("Total percent should be close to 100: $totalPercent", totalPercent in 95..105)
    }

    @Test
    fun testFocusTimerManagerPresets() {
        FocusTimerManager.setPreset(45)
        assertEquals(45, FocusTimerManager.selectedMinutes.value)
        assertEquals(45 * 60, FocusTimerManager.remainingSeconds.value)
        assertEquals(45 * 60, FocusTimerManager.totalSeconds.value)

        FocusTimerManager.setPreset(25)
        assertEquals(25, FocusTimerManager.selectedMinutes.value)
        assertEquals(25 * 60, FocusTimerManager.remainingSeconds.value)
    }
}
