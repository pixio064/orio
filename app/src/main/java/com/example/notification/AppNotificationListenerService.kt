package com.example.notification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.example.FocusTrackApp
import com.example.data.local.entity.NotificationRecordEntity
import com.example.util.DateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar

class AppNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPruneTimestamp = 0L

    // In-memory cache to detect and drop duplicate updates within 2 seconds
    private val recentKeys = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 150
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val notification = sbn.notification ?: return

        // 1. Ignore persistent ongoing notifications
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) return

        // 2. Ignore group summaries
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        // 3. Ignore duplicate updates for the same notification key within 2000ms
        val now = System.currentTimeMillis()
        val notifKey = sbn.key ?: "$pkg:${sbn.id}"
        synchronized(recentKeys) {
            val lastSeen = recentKeys[notifKey]
            if (lastSeen != null && (now - lastSeen) < 2000L) {
                return
            }
            recentKeys[notifKey] = now
        }

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dateString = DateKeys.today()

        val appName = try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }

        scope.launch {
            try {
                val app = applicationContext as? FocusTrackApp ?: return@launch
                app.database.notificationRecordDao().insert(
                    NotificationRecordEntity(
                        packageName = pkg,
                        appName = appName,
                        timestamp = now,
                        hourOfDay = hour,
                        dateString = dateString
                    )
                )

                // Schedule pruning of records older than 30 days once per 24 hours
                if (now - lastPruneTimestamp > 24 * 60 * 60 * 1000L) {
                    lastPruneTimestamp = now
                    val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000L
                    app.database.notificationRecordDao().pruneOldRecords(thirtyDaysAgo)
                }
            } catch (_: Exception) {
                // Silently ignore DB errors
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        fun isNotificationAccessGranted(context: Context): Boolean {
            val packages = NotificationManagerCompat.getEnabledListenerPackages(context)
            return packages.contains(context.packageName)
        }

        fun openNotificationAccessSettings(context: Context) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            }
        }
    }
}
