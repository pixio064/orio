package com.example.util

import com.example.data.local.entity.ReminderFrequency

data class ParsedSchedule(
    val title: String,
    val intervalMinutes: Int,
    val frequency: ReminderFrequency = ReminderFrequency.INTERVAL_MINUTES
)

object ScheduleParser {

    /**
     * Parses natural language reminder strings.
     * Handles case-insensitive "every", hours ("every 2 hours", "every hour"),
     * minutes ("every 15 mins"), daily/weekdays, and enforces a minimum interval of 5 minutes.
     */
    fun parse(input: String): ParsedSchedule {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return ParsedSchedule("", 30, ReminderFrequency.INTERVAL_MINUTES)
        }

        val everyRegex = Regex("""(?i)\bevery\b""")
        val match = everyRegex.find(trimmed)

        if (match == null) {
            return ParsedSchedule(trimmed, 30, ReminderFrequency.INTERVAL_MINUTES)
        }

        val titlePart = trimmed.substring(0, match.range.first).trim()
        val afterEvery = trimmed.substring(match.range.last + 1).trim()
        val cleanTitle = if (titlePart.isNotBlank()) titlePart else trimmed

        val lowerAfter = afterEvery.lowercase()

        // 1. Check daily / weekdays / weekly
        if (lowerAfter.startsWith("day") || lowerAfter.startsWith("morning") || lowerAfter.startsWith("night")) {
            return ParsedSchedule(cleanTitle, 1440, ReminderFrequency.DAILY)
        }
        if (lowerAfter.startsWith("weekday")) {
            return ParsedSchedule(cleanTitle, 1440, ReminderFrequency.WEEKDAYS)
        }
        if (lowerAfter.startsWith("week")) {
            return ParsedSchedule(cleanTitle, 10080, ReminderFrequency.WEEKLY)
        }

        // 2. Check "hour" / "hours"
        val hourMatch = Regex("""^(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|h)\b""", RegexOption.IGNORE_CASE).find(lowerAfter)
        if (hourMatch != null) {
            val hours = hourMatch.groupValues[1].toDoubleOrNull() ?: 1.0
            val minutes = (hours * 60).toInt().coerceAtLeast(5)
            return ParsedSchedule(cleanTitle, minutes, ReminderFrequency.INTERVAL_MINUTES)
        }

        if (Regex("""^(?:an?\s+)?(?:hour|hr)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowerAfter)) {
            return ParsedSchedule(cleanTitle, 60, ReminderFrequency.INTERVAL_MINUTES)
        }

        // 3. Check minutes
        val minMatch = Regex("""^(\d+)\s*(?:minutes?|mins?|m)\b""", RegexOption.IGNORE_CASE).find(lowerAfter)
        if (minMatch != null) {
            val minutes = minMatch.groupValues[1].toIntOrNull() ?: 30
            return ParsedSchedule(cleanTitle, minutes.coerceAtLeast(5), ReminderFrequency.INTERVAL_MINUTES)
        }

        // 4. Standalone number after "every" (e.g. "every 20")
        val numMatch = Regex("""^(\d+)\b""").find(lowerAfter)
        if (numMatch != null) {
            val num = numMatch.groupValues[1].toIntOrNull() ?: 30
            return ParsedSchedule(cleanTitle, num.coerceAtLeast(5), ReminderFrequency.INTERVAL_MINUTES)
        }

        return ParsedSchedule(cleanTitle, 30, ReminderFrequency.INTERVAL_MINUTES)
    }
}
