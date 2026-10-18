package com.example.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.example.data.local.entity.HourlyNotificationCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

data class RawEvent(
    val timestamp: Long,
    val eventType: Int,
    val packageName: String
)

data class CalculatedAppUsage(
    val packageName: String,
    val appName: String,
    val totalTimeInForegroundMs: Long,
    val hourlyScreenTimeMs: List<Long>,
    val launchCount: Int,
    val lastTimeUsed: Long,
    val firstTimeUsed: Long,
    val sessions: List<AppSessionInterval>,
    val category: String = "General"
)

data class CalculatedDayUsage(
    val date: LocalDate,
    val dayStartMs: Long,
    val dayEndMs: Long,
    val totalScreenOnMs: Long,
    val totalScreenOffMs: Long,
    val estimatedPhoneAwayMs: Long,
    val appUsages: List<CalculatedAppUsage>,
    val timelineEvents: List<UsageTimelineEvent>,
    val screenSessions: List<DetailedSession>,
    val lastTwoAppsBeforeLock: List<RecentAppInfo>,
    val lastLockTimestamp: Long,
    val inactivityGaps: List<InactivityRestGap>,
    val hasPermission: Boolean
)

object UsageCalculator {

    private const val TAG = "UsageCalc"

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] WARN: $msg")
        }
    }

    fun getDayBoundaries(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val nextDayStartMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = nextDayStartMs - 1L
        return Pair(startMs, endMs)
    }

    fun isFutureDate(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        return date.isAfter(LocalDate.now(zoneId))
    }

    /**
     * Single source of truth calculation for a full day's usage.
     */
    fun calculateDayUsage(
        context: Context,
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        currentTimeMs: Long = System.currentTimeMillis()
    ): CalculatedDayUsage {
        val (dayStartMs, dayEndMs) = getDayBoundaries(date, zoneId)
        val isToday = (date == LocalDate.now(zoneId))
        val isFuture = isFutureDate(date, zoneId)

        if (isFuture) {
            logD(TAG, "Future date requested ($date). Returning 0 usage.")
            return CalculatedDayUsage(
                date = date,
                dayStartMs = dayStartMs,
                dayEndMs = dayEndMs,
                totalScreenOnMs = 0L,
                totalScreenOffMs = 0L,
                estimatedPhoneAwayMs = 0L,
                appUsages = emptyList(),
                timelineEvents = emptyList(),
                screenSessions = emptyList(),
                lastTwoAppsBeforeLock = emptyList(),
                lastLockTimestamp = 0L,
                inactivityGaps = emptyList(),
                hasPermission = UsageStatsHelper.hasUsageStatsPermission(context)
            )
        }

        val hasPermission = UsageStatsHelper.hasUsageStatsPermission(context)
        if (!hasPermission) {
            logD(TAG, "Usage access permission not granted for date ($date).")
            return CalculatedDayUsage(
                date = date,
                dayStartMs = dayStartMs,
                dayEndMs = dayEndMs,
                totalScreenOnMs = 0L,
                totalScreenOffMs = 0L,
                estimatedPhoneAwayMs = 0L,
                appUsages = emptyList(),
                timelineEvents = emptyList(),
                screenSessions = emptyList(),
                lastTwoAppsBeforeLock = emptyList(),
                lastLockTimestamp = 0L,
                inactivityGaps = emptyList(),
                hasPermission = false
            )
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) {
            return CalculatedDayUsage(
                date = date,
                dayStartMs = dayStartMs,
                dayEndMs = dayEndMs,
                totalScreenOnMs = 0L,
                totalScreenOffMs = 0L,
                estimatedPhoneAwayMs = 0L,
                appUsages = emptyList(),
                timelineEvents = emptyList(),
                screenSessions = emptyList(),
                lastTwoAppsBeforeLock = emptyList(),
                lastLockTimestamp = 0L,
                inactivityGaps = emptyList(),
                hasPermission = false
            )
        }

        // Effective end of day for querying: if today, clamp to current time
        val effectiveEndMs = if (isToday) minOf(currentTimeMs, dayEndMs) else dayEndMs

        // Query events starting 1 hour before midnight so apps open across midnight are caught
        val queryStartMs = dayStartMs - (60 * 60 * 1000L)
        val events = usageStatsManager.queryEvents(queryStartMs, effectiveEndMs)

        val rawEventsList = mutableListOf<RawEvent>()
        val rawEvent = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(rawEvent)
            rawEventsList.add(
                RawEvent(
                    timestamp = rawEvent.timeStamp,
                    eventType = rawEvent.eventType,
                    packageName = rawEvent.packageName ?: ""
                )
            )
        }

        // Sort events chronologically to guarantee correct pairing
        rawEventsList.sortBy { it.timestamp }

        val pm = context.packageManager

        return computeFromEvents(
            rawEvents = rawEventsList,
            date = date,
            dayStartMs = dayStartMs,
            dayEndMs = effectiveEndMs,
            isToday = isToday,
            currentTimeMs = currentTimeMs,
            hasPermission = true,
            isSystemNoiseCheck = { pkg -> UsageStatsHelper.isSystemNoise(context, pkg) },
            appNameResolver = { pkg -> SessionsRepository.getCleanAppName(pm, pkg) },
            categoryResolver = { pkg -> inferAppCategory(pkg, pm) }
        )
    }

    /**
     * Pure function that calculates per-app usage and sessions from raw events.
     * Easily unit testable without requiring Android system services or emulators.
     */
    fun computeFromEvents(
        rawEvents: List<RawEvent>,
        date: LocalDate,
        dayStartMs: Long,
        dayEndMs: Long,
        isToday: Boolean,
        currentTimeMs: Long,
        hasPermission: Boolean,
        isSystemNoiseCheck: (String) -> Boolean,
        appNameResolver: (String) -> String,
        categoryResolver: (String) -> String
    ): CalculatedDayUsage {
        val appForegroundTimes = mutableMapOf<String, Long>()
        val appHourlyTimes = mutableMapOf<String, LongArray>()
        val appSessions = mutableMapOf<String, MutableList<AppSessionInterval>>()
        val appLaunchCounts = mutableMapOf<String, Int>()
        val appFirstUsed = mutableMapOf<String, Long>()
        val appLastUsed = mutableMapOf<String, Long>()

        val timelineEvents = mutableListOf<UsageTimelineEvent>()
        val reconstructedSessions = mutableListOf<DetailedSession>()
        val chronologicalAppsList = mutableListOf<Pair<String, Long>>()

        var currentResumedPkg: String? = null
        var currentResumeTime: Long = -1L

        var screenInteractiveStart: Long = -1L
        var totalScreenOnMs: Long = 0L
        var lastLockTimestamp: Long = 0L
        var lastUnlockTimestamp: Long = 0L
        var previousScreenOffTime: Long = dayStartMs
        var currentSessionApps = mutableListOf<String>()

        val detectedInactivityGaps = mutableListOf<InactivityRestGap>()
        val lastTwoAppsBeforeLockPackages = mutableListOf<String>()

        fun closeAppInterval(pkg: String, start: Long, end: Long) {
            if (end <= start || isSystemNoiseCheck(pkg)) return

            // Clip interval strictly to [dayStartMs, dayEndMs]
            val clippedStart = maxOf(start, dayStartMs)
            val clippedEnd = minOf(end, dayEndMs)
            val duration = clippedEnd - clippedStart

            if (duration > 0) {
                appForegroundTimes[pkg] = (appForegroundTimes[pkg] ?: 0L) + duration

                // Distribute into 24 hourly buckets
                val hourly = appHourlyTimes.getOrPut(pkg) { LongArray(24) { 0L } }
                for (h in 0..23) {
                    val hStart = dayStartMs + (h * 3600_000L)
                    val hEnd = hStart + 3600_000L
                    val overlapStart = maxOf(clippedStart, hStart)
                    val overlapEnd = minOf(clippedEnd, hEnd)
                    if (overlapEnd > overlapStart) {
                        hourly[h] += (overlapEnd - overlapStart)
                    }
                }

                // Record session interval if at least 5 seconds
                if (duration >= 5000L) {
                    val sessionsList = appSessions.getOrPut(pkg) { mutableListOf() }
                    sessionsList.add(AppSessionInterval(clippedStart, clippedEnd, duration))
                }
            }
        }

        for (event in rawEvents) {
            val t = event.timestamp
            val pkg = event.packageName

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg.isNotBlank() && !isSystemNoiseCheck(pkg)) {
                        val isSameAppDuplicate = (currentResumedPkg == pkg)

                        // If another app was resumed without an explicit pause, close its session
                        if (currentResumedPkg != null && currentResumedPkg != pkg) {
                            closeAppInterval(currentResumedPkg!!, currentResumeTime, t)
                            currentResumedPkg = pkg
                            currentResumeTime = t
                        } else if (currentResumedPkg == null) {
                            currentResumedPkg = pkg
                            currentResumeTime = t
                        }

                        // Count launch if within the target day and not a duplicate resume of the currently active app
                        if (!isSameAppDuplicate && t in dayStartMs..dayEndMs) {
                            appLaunchCounts[pkg] = (appLaunchCounts[pkg] ?: 0) + 1
                            val currentLast = appLastUsed[pkg] ?: 0L
                            if (t > currentLast) appLastUsed[pkg] = t
                            val currentFirst = appFirstUsed[pkg] ?: Long.MAX_VALUE
                            if (t < currentFirst) appFirstUsed[pkg] = t

                            val appName = appNameResolver(pkg)
                            timelineEvents.add(
                                UsageTimelineEvent(
                                    timestamp = t,
                                    eventType = "APP_FOREGROUND",
                                    description = "Opened $appName",
                                    packageName = pkg,
                                    appName = appName
                                )
                            )
                            currentSessionApps.add(pkg)
                            chronologicalAppsList.add(Pair(pkg, t))
                        }
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (currentResumedPkg != null && currentResumedPkg == pkg) {
                        closeAppInterval(pkg, currentResumeTime, t)
                        currentResumedPkg = null
                        currentResumeTime = -1L
                    }
                }

                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    lastUnlockTimestamp = t
                    if (screenInteractiveStart == -1L) {
                        screenInteractiveStart = t
                        currentSessionApps = mutableListOf()
                        val inactivityBefore = (t - previousScreenOffTime).coerceAtLeast(0L)

                        if (inactivityBefore >= 3 * 60 * 1000L && t >= dayStartMs) {
                            val gapStart = maxOf(dayStartMs, previousScreenOffTime)
                            val gapEnd = minOf(dayEndMs, t)
                            val gapDur = gapEnd - gapStart
                            if (gapDur >= 3 * 60 * 1000L) {
                                val gapLabel = when {
                                    gapDur >= 4 * 3600 * 1000L -> "Overnight Sleep & Deep Rest"
                                    gapDur >= 90 * 60 * 1000L -> "Extended Phone-Free Interval"
                                    gapDur >= 45 * 60 * 1000L -> "Midday Rest Break"
                                    else -> "Screen-Off Rest Pause"
                                }
                                detectedInactivityGaps.add(
                                    InactivityRestGap(
                                        id = gapStart,
                                        startTimestamp = gapStart,
                                        endTimestamp = gapEnd,
                                        durationMs = gapDur,
                                        label = gapLabel
                                    )
                                )
                            }
                        }
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    lastLockTimestamp = t
                    if (currentResumedPkg != null) {
                        closeAppInterval(currentResumedPkg!!, currentResumeTime, t)
                        currentResumedPkg = null
                        currentResumeTime = -1L
                    }

                    if (screenInteractiveStart != -1L) {
                        val sessionDur = t - screenInteractiveStart
                        if (sessionDur > 0) {
                            val clippedOnStart = maxOf(screenInteractiveStart, dayStartMs)
                            val clippedOnEnd = minOf(t, dayEndMs)
                            if (clippedOnEnd > clippedOnStart) {
                                totalScreenOnMs += (clippedOnEnd - clippedOnStart)
                            }

                            if (sessionDur >= 20 * 1000L && t >= dayStartMs) {
                                val inactBefore = (screenInteractiveStart - previousScreenOffTime).coerceAtLeast(0L)
                                val distinctApps = currentSessionApps.distinct().map { appNameResolver(it) }
                                val lastPkg = currentSessionApps.lastOrNull() ?: ""
                                reconstructedSessions.add(
                                    DetailedSession(
                                        id = screenInteractiveStart,
                                        startTimestamp = screenInteractiveStart,
                                        endTimestamp = t,
                                        durationMs = sessionDur,
                                        lastAppPackage = lastPkg,
                                        lastAppName = if (lastPkg.isNotBlank()) appNameResolver(lastPkg) else "None",
                                        precedingInactivityMs = inactBefore,
                                        appsUsed = distinctApps
                                    )
                                )
                            }
                        }

                        if (currentSessionApps.isNotEmpty()) {
                            lastTwoAppsBeforeLockPackages.clear()
                            lastTwoAppsBeforeLockPackages.addAll(currentSessionApps.distinct().takeLast(2))
                        }

                        previousScreenOffTime = t
                        screenInteractiveStart = -1L
                        currentSessionApps = mutableListOf()
                    }
                }
            }
        }

        // If app or screen is still ongoing at query time
        if (currentResumedPkg != null) {
            val endPoint = if (isToday) currentTimeMs else dayEndMs
            closeAppInterval(currentResumedPkg!!, currentResumeTime, endPoint)
            if (isToday) {
                currentSessionApps.add(currentResumedPkg!!)
                lastTwoAppsBeforeLockPackages.clear()
                lastTwoAppsBeforeLockPackages.addAll(currentSessionApps.distinct().takeLast(2))
            }
        }

        if (screenInteractiveStart != -1L) {
            val endPoint = if (isToday) currentTimeMs else dayEndMs
            val clippedOnStart = maxOf(screenInteractiveStart, dayStartMs)
            val clippedOnEnd = minOf(endPoint, dayEndMs)
            if (clippedOnEnd > clippedOnStart) {
                totalScreenOnMs += (clippedOnEnd - clippedOnStart)
            }
        }

        // Build list of CalculatedAppUsage
        val resultApps = mutableListOf<CalculatedAppUsage>()
        for ((pkg, fgTime) in appForegroundTimes) {
            if (fgTime > 0L) {
                val appName = appNameResolver(pkg)
                val hourly = appHourlyTimes[pkg]?.toList() ?: List(24) { 0L }
                val launches = appLaunchCounts[pkg] ?: 1
                val lastUsed = appLastUsed[pkg] ?: dayEndMs
                val firstUsed = appFirstUsed[pkg] ?: dayStartMs
                val sessions = appSessions[pkg]?.sortedBy { it.startTimestamp } ?: emptyList()
                val category = categoryResolver(pkg)

                resultApps.add(
                    CalculatedAppUsage(
                        packageName = pkg,
                        appName = appName,
                        totalTimeInForegroundMs = fgTime,
                        hourlyScreenTimeMs = hourly,
                        launchCount = launches,
                        lastTimeUsed = lastUsed,
                        firstTimeUsed = firstUsed,
                        sessions = sessions,
                        category = category
                    )
                )
            }
        }

        resultApps.sortByDescending { it.totalTimeInForegroundMs }

        // If total screen on time from events is 0, fall back to sum of app times
        if (totalScreenOnMs == 0L && resultApps.isNotEmpty()) {
            totalScreenOnMs = resultApps.sumOf { it.totalTimeInForegroundMs }
        }

        val totalDayDurationMs = (dayEndMs - dayStartMs).coerceAtLeast(1L)
        val screenOffMs = (totalDayDurationMs - totalScreenOnMs).coerceAtLeast(0L)
        val estimatedAwayMs = if (detectedInactivityGaps.isNotEmpty()) {
            detectedInactivityGaps.sumOf { it.durationMs }
        } else {
            screenOffMs
        }

        val recentAppsBeforeLock = lastTwoAppsBeforeLockPackages.distinct().takeLast(2).reversed().map { pkg ->
            RecentAppInfo(
                appName = appNameResolver(pkg),
                packageName = pkg,
                lastUsedTimestamp = lastLockTimestamp,
                relativeTime = getRelativeTimeString(lastLockTimestamp, currentTimeMs)
            )
        }

        // Tag "UsageCalc" log for verification on real devices
        logD(TAG, "Date: $date | Range: [${Instant.ofEpochMilli(dayStartMs)} - ${Instant.ofEpochMilli(dayEndMs)}] | Raw Events: ${rawEvents.size} | Total Screen On: ${totalScreenOnMs / 1000}s | Distinct Apps: ${resultApps.size}")
        resultApps.forEach { app ->
            logD(TAG, "  -> ${app.packageName} (${app.appName}): ${app.totalTimeInForegroundMs / 1000}s, launches: ${app.launchCount}")
        }

        return CalculatedDayUsage(
            date = date,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            totalScreenOnMs = totalScreenOnMs,
            totalScreenOffMs = screenOffMs,
            estimatedPhoneAwayMs = estimatedAwayMs,
            appUsages = resultApps,
            timelineEvents = timelineEvents.reversed(),
            screenSessions = reconstructedSessions,
            lastTwoAppsBeforeLock = recentAppsBeforeLock,
            lastLockTimestamp = lastLockTimestamp,
            inactivityGaps = detectedInactivityGaps.sortedByDescending { it.startTimestamp },
            hasPermission = hasPermission
        )
    }

    /**
     * Suspend calculation for a single application's 24-hour detailed screen time and notification counts.
     */
    suspend fun calculateAppUsage(
        context: Context,
        packageName: String,
        calendar: Calendar,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AppHourlyUsageData = withContext(Dispatchers.IO) {
        val date = LocalDate.of(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val dateString = dateFormat.format(calendar.time)
        val pm = context.packageManager
        val appName = SessionsRepository.getCleanAppName(pm, packageName)
        val appIcon = try { pm.getApplicationIcon(packageName) } catch (e: Exception) { null }

        val hasUsagePermission = UsageStatsHelper.hasUsageStatsPermission(context)
        val hasNotificationAccess = com.example.notification.AppNotificationListenerService.isNotificationAccessGranted(context)

        // If future date, return 0 usage immediately
        if (isFutureDate(date, zoneId)) {
            logD(TAG, "calculateAppUsage: Future date $dateString requested for $packageName. Returning 0.")
            return@withContext AppHourlyUsageData(
                packageName = packageName,
                appName = appName,
                dateString = dateString,
                totalScreenTimeMs = 0L,
                hourlyScreenTimeMs = List(24) { 0L },
                hourlyNotifications = List(24) { 0 },
                totalNotifications = 0,
                launchCount = 0,
                lastTimeUsed = 0L,
                peakHour = 0,
                hasRealUsageData = false,
                hasNotificationAccess = hasNotificationAccess,
                detectedSessions = emptyList(),
                appIcon = appIcon
            )
        }

        if (!hasUsagePermission) {
            logD(TAG, "calculateAppUsage: No usage permission for $packageName.")
            return@withContext AppHourlyUsageData(
                packageName = packageName,
                appName = appName,
                dateString = dateString,
                totalScreenTimeMs = 0L,
                hourlyScreenTimeMs = List(24) { 0L },
                hourlyNotifications = List(24) { 0 },
                totalNotifications = 0,
                launchCount = 0,
                lastTimeUsed = 0L,
                peakHour = 0,
                hasRealUsageData = false,
                hasNotificationAccess = hasNotificationAccess,
                detectedSessions = emptyList(),
                appIcon = appIcon
            )
        }

        // Calculate single source of truth for the day
        val dayUsage = calculateDayUsage(context, date, zoneId)
        val appStats = dayUsage.appUsages.find { it.packageName == packageName }

        val hourlyNotifications = IntArray(24) { 0 }
        // Fetch notifications from Room
        if (hasNotificationAccess) {
            try {
                val app = context.applicationContext as? com.example.FocusTrackApp
                if (app != null) {
                    val counts = app.database.notificationRecordDao().getHourlyCountsForApp(packageName, dateString)
                    counts.forEach { hCount: HourlyNotificationCount ->
                        if (hCount.hourOfDay in 0..23) {
                            hourlyNotifications[hCount.hourOfDay] = hCount.count
                        }
                    }
                }
            } catch (e: Exception) {
                logW(TAG, "Failed to query notification counts for $packageName: ${e.message}")
            }
        }

        val totalScreenTime = appStats?.totalTimeInForegroundMs ?: 0L
        val hourlyScreenTime = appStats?.hourlyScreenTimeMs ?: List(24) { 0L }
        val sessions = appStats?.sessions ?: emptyList()
        val launchCount = appStats?.launchCount ?: 0
        val lastUsed = appStats?.lastTimeUsed ?: 0L

        var peakH = 0
        var maxHUsage = 0L
        for (h in 0..23) {
            val usageAtH = hourlyScreenTime.getOrElse(h) { 0L }
            if (usageAtH > maxHUsage) {
                maxHUsage = usageAtH
                peakH = h
            }
        }

        return@withContext AppHourlyUsageData(
            packageName = packageName,
            appName = appName,
            dateString = dateString,
            totalScreenTimeMs = totalScreenTime,
            hourlyScreenTimeMs = hourlyScreenTime,
            hourlyNotifications = hourlyNotifications.toList(),
            totalNotifications = hourlyNotifications.sum(),
            launchCount = launchCount,
            lastTimeUsed = lastUsed,
            peakHour = peakH,
            hasRealUsageData = totalScreenTime > 0L,
            hasNotificationAccess = hasNotificationAccess,
            detectedSessions = sessions.reversed(),
            appIcon = appIcon
        )
    }

    private fun inferAppCategory(packageName: String, pm: PackageManager): String {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                ApplicationInfo.CATEGORY_GAME -> "Gaming"
                ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> "Entertainment"
                ApplicationInfo.CATEGORY_NEWS -> "Information"
                ApplicationInfo.CATEGORY_MAPS -> "Navigation"
                else -> {
                    val lower = packageName.lowercase()
                    when {
                        lower.contains("whatsapp") || lower.contains("instagram") || lower.contains("facebook") || lower.contains("twitter") || lower.contains("reddit") || lower.contains("tiktok") -> "Social"
                        lower.contains("youtube") || lower.contains("netflix") || lower.contains("spotify") || lower.contains("prime") -> "Entertainment"
                        lower.contains("chrome") || lower.contains("browser") || lower.contains("gmail") || lower.contains("slack") || lower.contains("docs") -> "Productivity"
                        lower.contains("duolingo") || lower.contains("coursera") || lower.contains("udemy") -> "Education"
                        lower.contains("pesam") || lower.contains("efootball") || lower.contains("game") || lower.contains("pubg") -> "Gaming"
                        else -> "General"
                    }
                }
            }
        } catch (e: Exception) {
            "General"
        }
    }

    private fun getRelativeTimeString(timestamp: Long, now: Long): String {
        if (timestamp <= 0L) return "Recently"
        val diffMs = now - timestamp
        val mins = diffMs / (60 * 1000)
        return when {
            mins <= 1 -> "Just now"
            mins < 60 -> "${mins}m ago"
            else -> "${mins / 60}h ${mins % 60}m ago"
        }
    }
}
