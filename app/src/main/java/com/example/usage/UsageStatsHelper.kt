package com.example.usage

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.example.data.local.entity.UsageSessionEntity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val totalTimeInForegroundMs: Long,
    val launchCount: Int,
    val lastTimeUsed: Long,
    val category: String,
    val firstTimeUsed: Long = 0L,
    val averageSessionDurationMs: Long = 0L,
    val sessionCount: Int = 0,
    val weeklyTrendPercent: Float = 0f,
    val appIcon: Drawable? = null
)

data class UsageTimelineEvent(
    val timestamp: Long,
    val eventType: String, // "SCREEN_ON", "SCREEN_OFF", "APP_FOREGROUND", "APP_BACKGROUND", "KEYGUARD_HIDDEN", "PHONE_FREE"
    val description: String,
    val packageName: String = "",
    val appName: String = "",
    val durationMs: Long = 0L,
    val isEstimated: Boolean = false
)

data class DayUsageSummary(
    val dateString: String,
    val totalScreenOnTimeMs: Long,
    val totalScreenOffTimeMs: Long,
    val estimatedPhoneAwayTimeMs: Long,
    val phoneSessionsCount: Int,
    val appLaunchesCount: Int,
    val mostUsedApp: String,
    val mostRecentApp: String,
    val longestSessionMs: Long,
    val longestPhoneFreeIntervalMs: Long,
    val firstUsageTimestamp: Long,
    val lastUsageTimestamp: Long,
    val appUsages: List<AppUsageInfo>,
    val timelineEvents: List<UsageTimelineEvent>,
    val reconstructedSessions: List<UsageSessionEntity>
)

object UsageStatsHelper {

    /**
     * Checks if PACKAGE_USAGE_STATS is granted.
     * Uses unsafeCheckOpNoThrow on API 29+ and handles MODE_DEFAULT fallback reliably.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }

        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Identifies system noise: android framework, SystemUI, the app's own package,
     * and the device's default launcher/home activity.
     */
    fun isSystemNoise(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        val lower = packageName.lowercase()
        if (lower == "android" || lower == "com.android.systemui" || lower == context.packageName.lowercase()) {
            return true
        }

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolveInfo?.activityInfo?.packageName.equals(packageName, ignoreCase = true)) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    fun getDayRange(calendar: Calendar): Pair<Long, Long> {
        val startCal = calendar.clone() as Calendar
        startCal.set(Calendar.HOUR_OF_DAY, 0)
        startCal.set(Calendar.MINUTE, 0)
        startCal.set(Calendar.SECOND, 0)
        startCal.set(Calendar.MILLISECOND, 0)

        val endCal = calendar.clone() as Calendar
        endCal.set(Calendar.HOUR_OF_DAY, 23)
        endCal.set(Calendar.MINUTE, 59)
        endCal.set(Calendar.SECOND, 59)
        endCal.set(Calendar.MILLISECOND, 999)

        val now = System.currentTimeMillis()
        val endTime = if (endCal.timeInMillis > now) now else endCal.timeInMillis
        return Pair(startCal.timeInMillis, endTime)
    }

    /**
     * Single source of truth calculation for a full day's usage.
     * Uses UsageCalculator to ensure Dashboard, Usage, and Sessions screens all match.
     */
    fun queryRealDayUsage(context: Context, targetCalendar: Calendar): DayUsageSummary {
        val zoneId = ZoneId.systemDefault()
        val date = LocalDate.of(
            targetCalendar.get(Calendar.YEAR),
            targetCalendar.get(Calendar.MONTH) + 1,
            targetCalendar.get(Calendar.DAY_OF_MONTH)
        )
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateString = dateFormat.format(targetCalendar.time)

        val dayUsage = UsageCalculator.calculateDayUsage(context, date, zoneId)
        val pm = context.packageManager

        val appUsageList = dayUsage.appUsages.map { calcApp ->
            val icon = try { pm.getApplicationIcon(calcApp.packageName) } catch (_: Exception) { null }
            val avgDur = if (calcApp.sessions.isNotEmpty()) {
                calcApp.sessions.map { it.durationMs }.average().toLong()
            } else {
                calcApp.totalTimeInForegroundMs / calcApp.launchCount.coerceAtLeast(1)
            }
            AppUsageInfo(
                packageName = calcApp.packageName,
                appName = calcApp.appName,
                totalTimeInForegroundMs = calcApp.totalTimeInForegroundMs,
                launchCount = calcApp.launchCount,
                lastTimeUsed = calcApp.lastTimeUsed,
                firstTimeUsed = calcApp.firstTimeUsed,
                averageSessionDurationMs = avgDur,
                sessionCount = calcApp.sessions.size.coerceAtLeast(calcApp.launchCount),
                category = calcApp.category,
                appIcon = icon
            )
        }

        val topApp = appUsageList.firstOrNull()?.appName ?: "None"
        val mostRecent = dayUsage.timelineEvents.firstOrNull { it.eventType == "APP_FOREGROUND" }?.appName ?: "None"
        val longestSession = dayUsage.screenSessions.maxOfOrNull { it.durationMs } ?: (appUsageList.firstOrNull()?.totalTimeInForegroundMs ?: 0L)
        val longestAway = dayUsage.inactivityGaps.maxOfOrNull { it.durationMs } ?: 0L

        return DayUsageSummary(
            dateString = dateString,
            totalScreenOnTimeMs = dayUsage.totalScreenOnMs,
            totalScreenOffTimeMs = dayUsage.totalScreenOffMs,
            estimatedPhoneAwayTimeMs = dayUsage.estimatedPhoneAwayMs,
            phoneSessionsCount = dayUsage.screenSessions.size,
            appLaunchesCount = appUsageList.sumOf { it.launchCount },
            mostUsedApp = topApp,
            mostRecentApp = mostRecent,
            longestSessionMs = longestSession,
            longestPhoneFreeIntervalMs = longestAway,
            firstUsageTimestamp = dayUsage.screenSessions.firstOrNull()?.startTimestamp ?: 0L,
            lastUsageTimestamp = dayUsage.screenSessions.lastOrNull()?.endTimestamp ?: 0L,
            appUsages = appUsageList,
            timelineEvents = dayUsage.timelineEvents,
            reconstructedSessions = dayUsage.screenSessions.map { s ->
                UsageSessionEntity(
                    sessionId = s.id,
                    startTimestamp = s.startTimestamp,
                    endTimestamp = s.endTimestamp,
                    durationMs = s.durationMs,
                    screenState = "SCREEN_INTERACTIVE",
                    mostRecentApp = s.lastAppName,
                    appsObservedJson = s.appsUsed.joinToString(",", "[", "]"),
                    appTransitions = s.appsUsed.size.coerceAtLeast(1),
                    isEstimated = false,
                    source = "UsageEvents",
                    dateString = dateString
                )
            }
        )
    }

    /**
     * Suspend calculation for a single app's 24-hour detailed screen time and notifications.
     */
    suspend fun queryAppHourlyUsage(
        context: Context,
        packageName: String,
        targetCalendar: Calendar
    ): AppHourlyUsageData {
        return UsageCalculator.calculateAppUsage(
            context = context,
            packageName = packageName,
            calendar = targetCalendar
        )
    }
}

data class AppHourlyUsageData(
    val packageName: String,
    val appName: String,
    val dateString: String,
    val totalScreenTimeMs: Long,
    val hourlyScreenTimeMs: List<Long>,
    val hourlyNotifications: List<Int>,
    val totalNotifications: Int,
    val launchCount: Int,
    val lastTimeUsed: Long,
    val peakHour: Int,
    val hasRealUsageData: Boolean,
    val hasNotificationAccess: Boolean,
    val detectedSessions: List<AppSessionInterval> = emptyList(),
    val appIcon: Drawable? = null
) {
    /**
     * Returns the exact session intervals when the app was used during a given hour of the day.
     */
    fun getSessionsForHour(hour: Int, targetCalendar: Calendar): List<AppSessionInterval> {
        val hStartCal = (targetCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val hStart = hStartCal.timeInMillis
        val hEnd = hStart + 3600_000L

        val overlapping = detectedSessions.filter { s ->
            s.endTimestamp > hStart && s.startTimestamp < hEnd
        }.map { s ->
            val overlapStart = maxOf(s.startTimestamp, hStart)
            val overlapEnd = minOf(s.endTimestamp, hEnd)
            AppSessionInterval(
                startTimestamp = overlapStart,
                endTimestamp = overlapEnd,
                durationMs = (overlapEnd - overlapStart).coerceAtLeast(0L)
            )
        }.filter { it.durationMs >= 5_000L }

        return overlapping.sortedBy { it.startTimestamp }
    }
}

data class AppSessionInterval(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long
)
