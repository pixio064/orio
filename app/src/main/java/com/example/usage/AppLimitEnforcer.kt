package com.example.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.entity.AppLimitEntity
import com.example.notification.NotificationHelper
import com.example.util.DateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

object AppLimitEnforcer {

    suspend fun checkLimits(context: Context, precomputedUsage: Map<String, Long>? = null) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(context)
                val limits = db.appLimitDao().getAllLimitsList().filter { it.isEnabled }
                if (limits.isEmpty()) return@withContext

                val today = DateKeys.today()
                val usageMap = precomputedUsage ?: queryTodayUsage(context)

                for (limit in limits) {
                    val usedMillis = usageMap[limit.packageName] ?: 0L
                    val usedMinutes = (usedMillis / (1000 * 60)).toInt()
                    val limitMinutes = limit.limitMinutes
                    if (limitMinutes <= 0) continue

                    val thresholdPercent = limit.warningThresholdPercent.coerceIn(10, 100)
                    val warningMinutes = (limitMinutes * thresholdPercent) / 100

                    val stateLimit = "$today:LIMIT"
                    val stateWarn = "$today:WARN"

                    if (usedMinutes >= limitMinutes) {
                        if (limit.notifyOnLimit && limit.lastNotifiedDate != stateLimit) {
                            NotificationHelper.showAppLimitAlert(
                                context = context,
                                appName = limit.appName,
                                usedMinutes = usedMinutes,
                                limitMinutes = limitMinutes,
                                isWarning = false,
                                thresholdPercent = 100
                            )
                            db.appLimitDao().insertOrUpdate(limit.copy(lastNotifiedDate = stateLimit))
                        }
                    } else if (usedMinutes >= warningMinutes) {
                        if (limit.notifyOnWarning &&
                            limit.lastNotifiedDate != stateWarn &&
                            limit.lastNotifiedDate != stateLimit
                        ) {
                            NotificationHelper.showAppLimitAlert(
                                context = context,
                                appName = limit.appName,
                                usedMinutes = usedMinutes,
                                limitMinutes = limitMinutes,
                                isWarning = true,
                                thresholdPercent = thresholdPercent
                            )
                            db.appLimitDao().insertOrUpdate(limit.copy(lastNotifiedDate = stateWarn))
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore unexpected reflection or permission issues gracefully
            }
        }
    }

    private fun queryTodayUsage(context: Context): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
        if (stats.isNullOrEmpty()) return emptyMap()

        val map = mutableMapOf<String, Long>()
        for (st in stats) {
            val prev = map[st.packageName] ?: 0L
            map[st.packageName] = prev + st.totalTimeInForeground
        }
        return map
    }
}
