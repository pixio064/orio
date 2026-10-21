package com.example.util

import java.util.Calendar
import java.util.Locale

object HabitStreakCalculator {

    /**
     * Computes consecutive active streak from completed date strings ("YYYY-MM-DD").
     * - Evaluates backwards from today (or yesterday if today is not logged yet).
     * - Resets to 0 if yesterday was missed and today is unlogged.
     * - Respects targetDaysPerWeek: when target <= 5 days/week, unlogged weekends (Saturday/Sunday)
     *   are recognized as rest days and do not reset the streak.
     */
    fun computeStreak(
        completedDates: Set<String>,
        targetDaysPerWeek: Int,
        todayDateString: String = DateKeys.today()
    ): Int {
        if (completedDates.isEmpty()) return 0

        val cal = Calendar.getInstance(Locale.US)
        DateKeys.parse(todayDateString)?.let { cal.time = it }

        var streak = 0
        val isTodayCompleted = completedDates.contains(todayDateString)
        if (isTodayCompleted) {
            streak++
        }

        // Walk backwards day by day starting from yesterday
        cal.add(Calendar.DAY_OF_YEAR, -1)

        while (true) {
            val dateStr = DateKeys.format(cal.time)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)
            val isRestDayAllowed = (targetDaysPerWeek <= 5 && isWeekend)

            if (completedDates.contains(dateStr)) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else if (isRestDayAllowed) {
                // Allowed rest day does not reset the streak
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                // Missed day: streak broken
                break
            }
        }

        return streak
    }
}
