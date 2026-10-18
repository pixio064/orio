package com.example.ai

import com.example.data.local.entity.HabitEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskOutcome
import com.example.usage.DayUsageSummary
import java.util.*

data class HabitCoachInsight(
    val id: String,
    val title: String,
    val summary: String,
    val type: InsightType,
    val recommendation: String,
    val isFactual: Boolean = true
)

enum class InsightType {
    PEAK_HOURS,
    APP_BURST,
    HABIT_STREAK,
    TASK_COMPLETION,
    NIGHT_USAGE,
    CHALLENGE
}

object HabitCoachEngine {

    fun generateInsights(
        usageSummary: DayUsageSummary,
        screenTimeGoalMinutes: Int,
        tasks: List<TaskEntity>,
        habits: List<HabitEntity>
    ): List<HabitCoachInsight> {
        val insights = mutableListOf<HabitCoachInsight>()
        val screenTimeMinutes = (usageSummary.totalScreenOnTimeMs / (60 * 1000)).toInt()

        // 1. Goal compliance
        if (screenTimeMinutes > 0) {
            if (screenTimeMinutes <= screenTimeGoalMinutes) {
                val remaining = screenTimeGoalMinutes - screenTimeMinutes
                insights.add(
                    HabitCoachInsight(
                        id = "goal_on_track",
                        title = "🎯 Screen Time Within Target",
                        summary = "You've used $screenTimeMinutes min of your $screenTimeGoalMinutes min daily budget ($remaining min remaining).",
                        type = InsightType.CHALLENGE,
                        recommendation = "Maintain your current pace to reach your daily digital wellbeing goal.",
                        isFactual = true
                    )
                )
            } else {
                val excess = screenTimeMinutes - screenTimeGoalMinutes
                insights.add(
                    HabitCoachInsight(
                        id = "goal_exceeded",
                        title = "⏳ Screen Time Goal Exceeded",
                        summary = "Today's screen time ($screenTimeMinutes min) is $excess min over your target ($screenTimeGoalMinutes min).",
                        type = InsightType.CHALLENGE,
                        recommendation = "Consider setting a 15-minute app limit on your most-used application (${usageSummary.mostUsedApp}) to gradually ease back.",
                        isFactual = true
                    )
                )
            }
        }

        // 2. High frequency short-burst apps
        val highLaunchApps = usageSummary.appUsages.filter { it.launchCount >= 8 && it.averageSessionDurationMs < 3 * 60 * 1000L }
        if (highLaunchApps.isNotEmpty()) {
            val app = highLaunchApps.first()
            insights.add(
                HabitCoachInsight(
                    id = "burst_app",
                    title = "⚡ Rapid Check-In Pattern Detected",
                    summary = "${app.appName} was opened ${app.launchCount} times today with an average session of only ${(app.averageSessionDurationMs / 1000)} seconds.",
                    type = InsightType.APP_BURST,
                    recommendation = "Micro-checking often happens reflexively. Try placing ${app.appName} inside a folder or using an Anti-Scroll reminder.",
                    isFactual = true
                )
            )
        }

        // 3. Top time consumer
        val topApp = usageSummary.appUsages.firstOrNull()
        if (topApp != null && topApp.totalTimeInForegroundMs > 20 * 60 * 1000L) {
            val mins = (topApp.totalTimeInForegroundMs / (60 * 1000)).toInt()
            val percent = if (screenTimeMinutes > 0) (mins * 100 / screenTimeMinutes) else 0
            insights.add(
                HabitCoachInsight(
                    id = "top_app_share",
                    title = "📱 Main Attention Focus: ${topApp.appName}",
                    summary = "${topApp.appName} accounted for $mins minutes ($percent% of all active screen time today).",
                    type = InsightType.PEAK_HOURS,
                    recommendation = "If this was unplanned browsing, scheduling a dedicated focus block can prevent unintentional time drift.",
                    isFactual = true
                )
            )
        }

        // 4. Task completion rate
        if (tasks.isNotEmpty()) {
            val completed = tasks.count { it.outcome == TaskOutcome.COMPLETED }
            val rate = (completed * 100) / tasks.size
            insights.add(
                HabitCoachInsight(
                    id = "task_rate",
                    title = "✅ Task Momentum: $rate%",
                    summary = "You've resolved $completed of ${tasks.size} planned items for today.",
                    type = InsightType.TASK_COMPLETION,
                    recommendation = if (rate >= 75) {
                        "Outstanding execution! You're consistently turning plans into reality."
                    } else {
                        "Remember: unfinished tasks can easily be carried forward in Tomorrow's Planner without pressure."
                    },
                    isFactual = true
                )
            )
        }

        // 5. Habit streaks
        val topHabit = habits.maxByOrNull { it.currentStreak }
        if (topHabit != null && topHabit.currentStreak > 0) {
            insights.add(
                HabitCoachInsight(
                    id = "habit_streak",
                    title = "🔥 Streak Spotlight: ${topHabit.name}",
                    summary = "You are on a ${topHabit.currentStreak}-day streak for ${topHabit.name}! (Best: ${topHabit.bestStreak} days)",
                    type = InsightType.HABIT_STREAK,
                    recommendation = "Consistency compounds over time. Keep this healthy routine going strong!",
                    isFactual = true
                )
            )
        }

        // 6. Achievable Wellbeing Challenge
        insights.add(
            HabitCoachInsight(
                id = "weekly_challenge",
                title = "🌱 Today's Micro-Challenge",
                summary = "Take a verified 20-minute phone-free walk or stretch break before evening.",
                type = InsightType.CHALLENGE,
                recommendation = "Physical movement away from screens directly restores cognitive stamina and lowers eye fatigue.",
                isFactual = false
            )
        )

        return insights
    }

    fun exportReportAsText(
        usageSummary: DayUsageSummary,
        tasks: List<TaskEntity>,
        habits: List<HabitEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("       FOCUSTRACK DIGITAL REPORT        ")
        sb.appendLine("========================================")
        sb.appendLine("Date: ${usageSummary.dateString}")
        sb.appendLine("Total Screen On Time: ${usageSummary.totalScreenOnTimeMs / (60 * 1000)} minutes")
        sb.appendLine("Total Screen Off Time: ${usageSummary.totalScreenOffTimeMs / (60 * 1000)} minutes")
        sb.appendLine("Phone Sessions: ${usageSummary.phoneSessionsCount}")
        sb.appendLine("Total App Launches: ${usageSummary.appLaunchesCount}")
        sb.appendLine("Most Used App: ${usageSummary.mostUsedApp}")
        sb.appendLine("Longest Session: ${usageSummary.longestSessionMs / (60 * 1000)} minutes")
        sb.appendLine("Longest Phone-Free Break: ${usageSummary.longestPhoneFreeIntervalMs / (60 * 1000)} minutes")
        sb.appendLine()
        sb.appendLine("--- TOP APPS ---")
        for (app in usageSummary.appUsages.take(5)) {
            val mins = app.totalTimeInForegroundMs / (60 * 1000)
            sb.appendLine("• ${app.appName}: $mins min (${app.launchCount} launches, avg ${app.averageSessionDurationMs / 1000}s)")
        }
        sb.appendLine()
        sb.appendLine("--- TASKS (${tasks.size}) ---")
        for (task in tasks) {
            sb.appendLine("• [${task.outcome}] ${task.title} (${task.category})")
        }
        sb.appendLine()
        sb.appendLine("--- ACTIVE HABITS (${habits.size}) ---")
        for (habit in habits) {
            sb.appendLine("• ${habit.name}: Current Streak ${habit.currentStreak} days (Best: ${habit.bestStreak})")
        }
        sb.appendLine("========================================")
        return sb.toString()
    }
}
