package com.example.usage

import android.content.Context
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

data class DetailedSession(
    val id: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long,
    val lastAppPackage: String,
    val lastAppName: String,
    val precedingInactivityMs: Long,
    val appsUsed: List<String>,
    val isCurrentActive: Boolean = false,
    val isEstimated: Boolean = false
)

data class RecentAppInfo(
    val appName: String,
    val packageName: String,
    val lastUsedTimestamp: Long,
    val relativeTime: String
)

data class AppUsageSlice(
    val appName: String,
    val packageName: String,
    val durationMs: Long,
    val percentOfScreenOn: Int,
    val colorHex: Long // 0xAARRGGBB
)

data class InactivityRestGap(
    val id: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long,
    val label: String
)

data class SessionsDashboardData(
    val dateString: String,
    val screenOnMs: Long,
    val awayTimeMs: Long,
    val screenOnPercent: Int,
    val awayPercent: Int,
    val lastPickedUpTimestamp: Long,
    val lastPickedUpRelative: String,
    val currentSessionDurationMs: Long,
    val isScreenActive: Boolean,
    val inactivityRestMs: Long,
    val lastAppUsedName: String,
    val lastAppUsedPackage: String,
    val lastTwoApps: List<RecentAppInfo>,
    val lastTwoAppsBeforeLock: List<RecentAppInfo> = emptyList(),
    val lastLockTimestamp: Long = 0L,
    val lastLockRelative: String = "",
    val inactivityGaps: List<InactivityRestGap> = emptyList(),
    val topAppsForBar: List<AppUsageSlice>,
    val dailyAppStats: List<AppUsageInfo>,
    val weeklyAppStats: List<AppUsageInfo>,
    val sessionTimeline: List<DetailedSession>,
    val hasPermission: Boolean
)

object SessionsRepository {

    private val APP_SLICE_COLORS = listOf(
        0xFF10B981, // Emerald Green
        0xFF3B82F6, // Vibrant Blue
        0xFFEF4444, // Red
        0xFF06B6D4, // Cyan
        0xFF8B5CF6, // Purple
        0xFFF59E0B, // Amber
        0xFFEC4899  // Pink
    )

    /**
     * Builds SessionsDashboardData using UsageCalculator as the single source of truth.
     * All values match the Dashboard, Usage, and App Detail screens.
     */
    fun getSessionsDashboardData(context: Context, targetCalendar: Calendar): SessionsDashboardData {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateString = dateFormat.format(targetCalendar.time)
        val zoneId = ZoneId.systemDefault()

        val date = LocalDate.of(
            targetCalendar.get(Calendar.YEAR),
            targetCalendar.get(Calendar.MONTH) + 1,
            targetCalendar.get(Calendar.DAY_OF_MONTH)
        )

        val dayUsage = UsageCalculator.calculateDayUsage(context, date, zoneId)

        if (!dayUsage.hasPermission) {
            return emptySessionsData(dateString, hasPermission = false)
        }

        val pm = context.packageManager
        val totalScreenOnMs = dayUsage.totalScreenOnMs
        val totalDaySpan = (dayUsage.dayEndMs - dayUsage.dayStartMs).coerceAtLeast(1L)
        val awayTimeMs = (totalDaySpan - totalScreenOnMs).coerceAtLeast(0L)

        val screenOnPercent = ((totalScreenOnMs.toFloat() / totalDaySpan) * 100).toInt().coerceIn(0, 100)
        val awayPercent = (100 - screenOnPercent).coerceIn(0, 100)

        // Map daily apps to AppUsageInfo
        val dailyAppList = dayUsage.appUsages.map { calcApp ->
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

        // Top Apps slices for the breakdown bar
        val topAppsSlices = mutableListOf<AppUsageSlice>()
        if (totalScreenOnMs > 0) {
            val significantApps = dayUsage.appUsages.filter { it.totalTimeInForegroundMs >= 30_000L }
            val topApps = significantApps.take(4)
            var accountedMs = 0L

            topApps.forEachIndexed { index, app ->
                val sliceFraction = (app.totalTimeInForegroundMs.toFloat() / totalScreenOnMs * 100).toInt().coerceAtLeast(1)
                accountedMs += app.totalTimeInForegroundMs
                topAppsSlices.add(
                    AppUsageSlice(
                        appName = app.appName,
                        packageName = app.packageName,
                        durationMs = app.totalTimeInForegroundMs,
                        percentOfScreenOn = sliceFraction,
                        colorHex = APP_SLICE_COLORS[index % APP_SLICE_COLORS.size]
                    )
                )
            }

            val remainingMs = totalScreenOnMs - accountedMs
            if (remainingMs > 60_000L) {
                val remainingFraction = (remainingMs.toFloat() / totalScreenOnMs * 100).toInt().coerceAtLeast(1)
                topAppsSlices.add(
                    AppUsageSlice(
                        appName = "Other Apps",
                        packageName = "other",
                        durationMs = remainingMs,
                        percentOfScreenOn = remainingFraction,
                        colorHex = 0xFF8B5CF6
                    )
                )
            }
        }

        // Reconstructed sessions
        val latestSession = dayUsage.screenSessions.lastOrNull()
        val isScreenActive = latestSession?.isCurrentActive ?: false
        val currentSessionDur = if (isScreenActive && latestSession != null) latestSession.durationMs else 0L
        val inactivityRest = latestSession?.precedingInactivityMs ?: 0L

        val lastTwoBeforeLock = dayUsage.lastTwoAppsBeforeLock
        val lastUsedApp = lastTwoBeforeLock.firstOrNull()?.appName
            ?: dayUsage.appUsages.firstOrNull()?.appName
            ?: "None"
        val lastUsedPkg = lastTwoBeforeLock.firstOrNull()?.packageName
            ?: dayUsage.appUsages.firstOrNull()?.packageName
            ?: ""

        val lastPickedUp = latestSession?.startTimestamp ?: 0L
        val relativePickedUp = if (lastPickedUp > 0) getRelativeTimeString(lastPickedUp) else "No sessions"

        val lockTimestamp = dayUsage.lastLockTimestamp
        val lockRelative = if (lockTimestamp > 0) getRelativeTimeString(lockTimestamp) else "No lock events"

        // Weekly app stats: aggregate past 7 days
        val weeklyAppStatsMap = mutableMapOf<String, Long>()
        for (dayOffset in 0..6) {
            val pastDate = date.minusDays(dayOffset.toLong())
            val pastDayUsage = if (dayOffset == 0) dayUsage else UsageCalculator.calculateDayUsage(context, pastDate, zoneId)
            for (app in pastDayUsage.appUsages) {
                weeklyAppStatsMap[app.packageName] = (weeklyAppStatsMap[app.packageName] ?: 0L) + app.totalTimeInForegroundMs
            }
        }

        val weeklyAppList = weeklyAppStatsMap.map { (pkg, totalMs) ->
            val cleanName = getCleanAppName(pm, pkg)
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            AppUsageInfo(
                packageName = pkg,
                appName = cleanName,
                totalTimeInForegroundMs = totalMs,
                launchCount = 0,
                lastTimeUsed = 0L,
                category = "General",
                appIcon = icon
            )
        }.sortedByDescending { it.totalTimeInForegroundMs }

        return SessionsDashboardData(
            dateString = dateString,
            screenOnMs = totalScreenOnMs,
            awayTimeMs = awayTimeMs,
            screenOnPercent = screenOnPercent,
            awayPercent = awayPercent,
            lastPickedUpTimestamp = lastPickedUp,
            lastPickedUpRelative = relativePickedUp,
            currentSessionDurationMs = currentSessionDur,
            isScreenActive = isScreenActive,
            inactivityRestMs = inactivityRest,
            lastAppUsedName = lastUsedApp,
            lastAppUsedPackage = lastUsedPkg,
            lastTwoApps = lastTwoBeforeLock,
            lastTwoAppsBeforeLock = lastTwoBeforeLock,
            lastLockTimestamp = lockTimestamp,
            lastLockRelative = lockRelative,
            inactivityGaps = dayUsage.inactivityGaps,
            topAppsForBar = topAppsSlices,
            dailyAppStats = dailyAppList.distinctBy { it.packageName },
            weeklyAppStats = weeklyAppList.distinctBy { it.packageName },
            sessionTimeline = dayUsage.screenSessions.reversed(),
            hasPermission = true
        )
    }

    private fun emptySessionsData(dateString: String, hasPermission: Boolean): SessionsDashboardData {
        return SessionsDashboardData(
            dateString = dateString,
            screenOnMs = 0L,
            awayTimeMs = 0L,
            screenOnPercent = 0,
            awayPercent = 100,
            lastPickedUpTimestamp = 0L,
            lastPickedUpRelative = "No data",
            currentSessionDurationMs = 0L,
            isScreenActive = false,
            inactivityRestMs = 0L,
            lastAppUsedName = "None",
            lastAppUsedPackage = "",
            lastTwoApps = emptyList(),
            lastTwoAppsBeforeLock = emptyList(),
            lastLockTimestamp = 0L,
            lastLockRelative = "",
            inactivityGaps = emptyList(),
            topAppsForBar = emptyList(),
            dailyAppStats = emptyList(),
            weeklyAppStats = emptyList(),
            sessionTimeline = emptyList(),
            hasPermission = hasPermission
        )
    }

    fun getCleanAppName(pm: PackageManager, packageName: String): String {
        val lower = packageName.lowercase()

        // Explicit clean mappings for popular apps
        when {
            lower == "com.whatsapp" -> return "WhatsApp"
            lower == "com.whatsapp.w4b" -> return "WhatsApp Business"
            lower == "jp.konami.pesam" -> return "eFootball™"
            lower.contains("pesam") || lower.contains("efootball") -> return "eFootball™"
            lower == "com.facebook.katana" -> return "Facebook"
            lower == "com.facebook.orca" -> return "Messenger"
            lower == "com.instagram.android" -> return "Instagram"
            lower == "com.google.android.youtube" -> return "YouTube"
            lower == "org.telegram.messenger" -> return "Telegram"
            lower == "com.snapchat.android" -> return "Snapchat"
            lower == "com.android.chrome" -> return "Chrome"
            lower == "com.spotify.music" -> return "Spotify"
            lower == "com.netflix.mediaclient" -> return "Netflix"
            lower == "com.tencent.ig" || lower.contains("pubg") -> return "PUBG Mobile"
            lower == "com.dts.freefireth" || lower.contains("freefire") -> return "Free Fire"
            lower.contains("duolingo") -> return "Duolingo"
            lower.contains("clashofclans") -> return "Clash of Clans"
            lower.contains("roblox") -> return "Roblox"
            lower == "com.google.android.apps.messaging" -> return "Messages"
            lower == "com.google.android.dialer" -> return "Phone"
            lower == "com.google.android.gm" -> return "Gmail"
        }

        // PackageManager label lookup
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank() && !label.equals(packageName, ignoreCase = true)) {
                return label
            }
        } catch (_: Exception) {}

        // Fallback: Clean Title-Cased Name
        val lastSegment = packageName.substringAfterLast(".")
        if (lastSegment.length > 2 && lastSegment != "android" && lastSegment != "app") {
            return lastSegment.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        val segments = packageName.split(".")
        val candidate = segments.dropLast(1).lastOrNull { it != "com" && it != "org" && it != "android" }
        return candidate?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: packageName
    }

    private fun getRelativeTimeString(timestamp: Long): String {
        if (timestamp <= 0L) return "Recently"
        val diffMs = System.currentTimeMillis() - timestamp
        val mins = diffMs / (60 * 1000)
        return when {
            mins <= 1 -> "Just now"
            mins < 60 -> "${mins}m ago"
            else -> "${mins / 60}h ${mins % 60}m ago"
        }
    }
}
