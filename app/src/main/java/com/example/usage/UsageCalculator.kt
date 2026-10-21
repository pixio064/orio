package com.example.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.data.local.entity.HourlyNotificationCount
import com.example.util.DateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

data class RawEvent(
    val timestamp: Long,
    val eventType: Int,
    val packageName: String,
    val className: String = ""
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
            ?: return CalculatedDayUsage(
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
                    packageName = rawEvent.packageName ?: "",
                    className = rawEvent.className ?: ""
                )
            )
        }

        rawEventsList.sortBy { it.timestamp }

        val pm = context.packageManager
        val noiseCache = mutableMapOf<String, Boolean>()
        val nameCache = mutableMapOf<String, String>()
        val categoryCache = mutableMapOf<String, String>()

        val result = computeFromEvents(
            rawEvents = rawEventsList,
            date = date,
            dayStartMs = dayStartMs,
            dayEndMs = effectiveEndMs,
            isToday = isToday,
            currentTimeMs = currentTimeMs,
            hasPermission = true,
            isSystemNoiseCheck = { pkg ->
                noiseCache.getOrPut(pkg) { UsageStatsHelper.isSystemNoise(context, pkg) }
            },
            appNameResolver = { pkg ->
                nameCache.getOrPut(pkg) { SessionsRepository.getCleanAppName(pm, pkg) }
            },
            categoryResolver = { pkg ->
                categoryCache.getOrPut(pkg) { inferAppCategory(pkg, pm) }
            }
        )

        return result
    }

    /**
     * Pure function that calculates per-app usage, foreground time, transitions, and screen sessions.
     * Transitions within the same package (e.g. PAUSED(A1) -> RESUMED(A2) -> STOPPED(A1))
     * do not count as separate launches and do not truncate foreground duration.
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

        // Track active foreground app and active activities per package
        var currentResumedPkg: String? = null
        var currentResumeTime: Long = -1L
        val activeActivitiesByPkg = mutableMapOf<String, MutableSet<String>>()

        var screenInteractiveStart: Long = -1L
        var totalScreenOnMs: Long = 0L
        var lastLockTimestamp: Long = 0L
        var previousScreenOffTime: Long = dayStartMs
        var currentSessionApps = mutableListOf<String>()

        val detectedInactivityGaps = mutableListOf<InactivityRestGap>()
        val lastTwoAppsBeforeLockPackages = mutableListOf<String>()

        fun closeAppInterval(pkg: String, start: Long, end: Long) {
            if (end <= start || isSystemNoiseCheck(pkg)) return

            val clippedStart = maxOf(start, dayStartMs)
            val clippedEnd = minOf(end, dayEndMs)
            val duration = clippedEnd - clippedStart

            if (duration > 0) {
                appForegroundTimes[pkg] = (appForegroundTimes[pkg] ?: 0L) + duration

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

                if (duration >= 5000L) {
                    val sessionsList = appSessions.getOrPut(pkg) { mutableListOf() }
                    sessionsList.add(AppSessionInterval(clippedStart, clippedEnd, duration))
                }
            }
        }

        val eventCount = rawEvents.size
        for (i in 0 until eventCount) {
            val event = rawEvents[i]
            val t = event.timestamp
            val pkg = event.packageName
            val cls = event.className

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg.isNotBlank() && !isSystemNoiseCheck(pkg)) {
                        val activeSet = activeActivitiesByPkg.getOrPut(pkg) { mutableSetOf() }
                        val isInternalTransition = (currentResumedPkg == pkg)

                        if (cls.isNotBlank()) {
                            activeSet.add(cls)
                        }

                        if (!isInternalTransition) {
                            // Switched from a different app
                            if (currentResumedPkg != null) {
                                closeAppInterval(currentResumedPkg!!, currentResumeTime, t)
                                activeActivitiesByPkg[currentResumedPkg!!]?.clear()
                            }
                            currentResumedPkg = pkg
                            currentResumeTime = t

                            if (t in dayStartMs..dayEndMs) {
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
                        } else {
                            // Same app internal transition: update last used timestamp without incrementing launchCount
                            if (t in dayStartMs..dayEndMs) {
                                val currentLast = appLastUsed[pkg] ?: 0L
                                if (t > currentLast) appLastUsed[pkg] = t
                            }
                        }
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (cls.isNotBlank()) {
                        activeActivitiesByPkg[pkg]?.remove(cls)
                    }
                    if (currentResumedPkg == pkg) {
                        // Check if the next event is an activity resume inside the same package (lookahead)
                        val nextEvent = if (i + 1 < eventCount) rawEvents[i + 1] else null
                        val willResumeSameApp = nextEvent != null &&
                                nextEvent.packageName == pkg &&
                                nextEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                                (nextEvent.timestamp - t) < 3000L

                        if (!willResumeSameApp) {
                            val activeSet = activeActivitiesByPkg[pkg]
                            if (activeSet.isNullOrEmpty()) {
                                closeAppInterval(pkg, currentResumeTime, t)
                                currentResumedPkg = null
                                currentResumeTime = -1L
                            }
                        }
                    }
                }

                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (cls.isNotBlank()) {
                        activeActivitiesByPkg[pkg]?.remove(cls)
                    }
                    if (currentResumedPkg == pkg) {
                        val activeSet = activeActivitiesByPkg[pkg]
                        if (activeSet.isNullOrEmpty()) {
                            closeAppInterval(pkg, currentResumeTime, t)
                            currentResumedPkg = null
                            currentResumeTime = -1L
                        }
                    }
                }

                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    timelineEvents.add(
                        UsageTimelineEvent(
                            timestamp = t,
                            eventType = "SCREEN_ON",
                            description = "Screen turned on",
                            packageName = "",
                            appName = "Screen"
                        )
                    )
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

                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    timelineEvents.add(
                        UsageTimelineEvent(
                            timestamp = t,
                            eventType = "SCREEN_UNLOCK",
                            description = "Device unlocked",
                            packageName = "",
                            appName = "System"
                        )
                    )
                    if (screenInteractiveStart == -1L) {
                        screenInteractiveStart = t
                        currentSessionApps = mutableListOf()
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    lastLockTimestamp = t
                    timelineEvents.add(
                        UsageTimelineEvent(
                            timestamp = t,
                            eventType = "SCREEN_OFF",
                            description = "Screen locked / turned off",
                            packageName = "",
                            appName = "Screen"
                        )
                    )

                    if (currentResumedPkg != null) {
                        closeAppInterval(currentResumedPkg!!, currentResumeTime, t)
                        activeActivitiesByPkg[currentResumedPkg!!]?.clear()
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
                                        appsUsed = distinctApps,
                                        isCurrentActive = false
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

        // Close ongoing app session at current time / day end
        if (currentResumedPkg != null) {
            val endPoint = if (isToday) currentTimeMs else dayEndMs
            closeAppInterval(currentResumedPkg!!, currentResumeTime, endPoint)
            if (isToday) {
                currentSessionApps.add(currentResumedPkg!!)
                lastTwoAppsBeforeLockPackages.clear()
                lastTwoAppsBeforeLockPackages.addAll(currentSessionApps.distinct().takeLast(2))
            }
        }

        // Handle active ongoing screen session (Item 7)
        if (screenInteractiveStart != -1L) {
            val endPoint = if (isToday) currentTimeMs else dayEndMs
            val clippedOnStart = maxOf(screenInteractiveStart, dayStartMs)
            val clippedOnEnd = minOf(endPoint, dayEndMs)
            val liveDur = (clippedOnEnd - clippedOnStart).coerceAtLeast(0L)
            if (liveDur > 0) {
                totalScreenOnMs += liveDur
            }

            if (isToday) {
                val inactBefore = (screenInteractiveStart - previousScreenOffTime).coerceAtLeast(0L)
                val distinctApps = currentSessionApps.distinct().map { appNameResolver(it) }
                val lastPkg = currentSessionApps.lastOrNull() ?: currentResumedPkg ?: ""
                reconstructedSessions.add(
                    DetailedSession(
                        id = screenInteractiveStart,
                        startTimestamp = screenInteractiveStart,
                        endTimestamp = currentTimeMs,
                        durationMs = (currentTimeMs - screenInteractiveStart).coerceAtLeast(0L),
                        lastAppPackage = lastPkg,
                        lastAppName = if (lastPkg.isNotBlank()) appNameResolver(lastPkg) else "Active",
                        precedingInactivityMs = inactBefore,
                        appsUsed = distinctApps,
                        isCurrentActive = true
                    )
                )
            }
        } else if (previousScreenOffTime > 0L) {
            // Screen is currently off: calculate trailing screen-off break gap (Item 9)
            val trailingEnd = if (isToday) currentTimeMs else dayEndMs
            val gapStart = maxOf(dayStartMs, previousScreenOffTime)
            val gapEnd = minOf(trailingEnd, dayEndMs)
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
        val dateString = DateKeys.format(calendar.time)
        val pm = context.packageManager
        val appName = SessionsRepository.getCleanAppName(pm, packageName)

        val hasUsagePermission = UsageStatsHelper.hasUsageStatsPermission(context)
        val hasNotificationAccess = com.example.notification.AppNotificationListenerService.isNotificationAccessGranted(context)

        if (isFutureDate(date, zoneId)) {
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
                appIcon = null
            )
        }

        if (!hasUsagePermission) {
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
                appIcon = null
            )
        }

        val dayUsage = calculateDayUsage(context, date, zoneId)
        val appStats = dayUsage.appUsages.find { it.packageName == packageName }

        val hourlyNotifications = IntArray(24) { 0 }
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
            } catch (_: Exception) {}
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
            appIcon = null
        )
    }

    private fun inferAppCategory(packageName: String, pm: PackageManager): String {
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val categoryInt = try {
                    appInfo.category
                } catch (_: Throwable) {
                    ApplicationInfo.CATEGORY_UNDEFINED
                }
                when (categoryInt) {
                    ApplicationInfo.CATEGORY_SOCIAL -> return "Social"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "Productivity"
                    ApplicationInfo.CATEGORY_GAME -> return "Gaming"
                    ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> return "Entertainment"
                    ApplicationInfo.CATEGORY_NEWS -> return "Information"
                    ApplicationInfo.CATEGORY_MAPS -> return "Navigation"
                }
            }
        } catch (_: Throwable) {}

        val lower = packageName.lowercase()
        return when {
            lower.contains("whatsapp") || lower.contains("instagram") || lower.contains("facebook") ||
                    lower.contains("twitter") || lower.contains("reddit") || lower.contains("tiktok") ||
                    lower.contains("telegram") || lower.contains("messaging") -> "Social"
            lower.contains("youtube") || lower.contains("netflix") || lower.contains("spotify") ||
                    lower.contains("prime") || lower.contains("music") -> "Entertainment"
            lower.contains("chrome") || lower.contains("browser") || lower.contains("gmail") ||
                    lower.contains("slack") || lower.contains("docs") || lower.contains("notes") ||
                    lower.contains("office") -> "Productivity"
            lower.contains("duolingo") || lower.contains("coursera") || lower.contains("udemy") -> "Education"
            lower.contains("game") || lower.contains("play") || lower.contains("pubg") || lower.contains("pes") -> "Gaming"
            else -> "General"
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
