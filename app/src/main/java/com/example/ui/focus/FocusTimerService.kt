package com.example.ui.focus

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.notification.NotificationHelper
import kotlinx.coroutines.*

class FocusTimerService : Service() {

    companion object {
        const val NOTIFICATION_ID = 7001

        const val ACTION_START = "com.example.focus.ACTION_START"
        const val ACTION_PAUSE = "com.example.focus.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.focus.ACTION_RESUME"
        const val ACTION_STOP = "com.example.focus.ACTION_STOP"

        const val EXTRA_DURATION_SECONDS = "extra_duration_seconds"
        const val EXTRA_TARGET_MINUTES = "extra_target_minutes"

        fun startService(context: Context, durationSeconds: Int, targetMinutes: Int) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_SECONDS, durationSeconds)
                putExtra(EXTRA_TARGET_MINUTES, targetMinutes)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseService(context: Context) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeService(context: Context) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FocusTimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var targetEndTimeMs: Long = 0L
    private var pausedRemainingSeconds: Int = 0
    private var isPaused: Boolean = false
    private var targetMinutes: Int = 25
    private var tickerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val durationSec = intent.getIntExtra(EXTRA_DURATION_SECONDS, 25 * 60)
                targetMinutes = intent.getIntExtra(EXTRA_TARGET_MINUTES, durationSec / 60)
                isPaused = false
                targetEndTimeMs = System.currentTimeMillis() + (durationSec * 1000L)

                startInForeground(durationSec)
                startTicker()
            }
            ACTION_PAUSE -> {
                if (!isPaused) {
                    isPaused = true
                    val currentRemaining = ((targetEndTimeMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                    pausedRemainingSeconds = currentRemaining
                    tickerJob?.cancel()
                    updateNotification(pausedRemainingSeconds, isPaused = true)
                }
            }
            ACTION_RESUME -> {
                if (isPaused) {
                    isPaused = false
                    targetEndTimeMs = System.currentTimeMillis() + (pausedRemainingSeconds * 1000L)
                    startTicker()
                    updateNotification(pausedRemainingSeconds, isPaused = false)
                }
            }
            ACTION_STOP -> {
                stopForegroundAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val remainingSec = ((targetEndTimeMs - now) / 1000L).toInt()

                if (remainingSec <= 0) {
                    withContext(Dispatchers.Main) {
                        FocusTimerManager.completeSession(applicationContext)
                    }
                    stopForegroundAndSelf()
                    break
                }

                withContext(Dispatchers.Main) {
                    FocusTimerManager.updateTick(remainingSec)
                }

                // Update notification display every second
                updateNotification(remainingSec, isPaused = false)

                delay(1000L)
            }
        }
    }

    private fun startInForeground(secondsLeft: Int) {
        val notification = buildNotification(secondsLeft, isPaused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(secondsLeft: Int, isPaused: Boolean) {
        val notification = buildNotification(secondsLeft, isPaused)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(secondsLeft: Int, isPaused: Boolean): Notification {
        val mins = secondsLeft / 60
        val secs = secondsLeft % 60
        val timeFormatted = String.format("%02d:%02d", mins, secs)

        // Intent to launch MainActivity and navigate to Focus screen
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NAVIGATE_TO", "focus")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Pause / Resume
        val toggleActionIntent = Intent(this, FocusTimerReceiver::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            this,
            102,
            toggleActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleActionTitle = if (isPaused) "Resume ▶" else "Pause ⏸"

        // Action Stop
        val stopActionIntent = Intent(this, FocusTimerReceiver::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            103,
            stopActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPaused) "Focus Timer (Paused) ⏸" else "Focus Session in Progress ⏱️"
        val subtitle = "$timeFormatted remaining • Target: ${targetMinutes}m"

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TIMER_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, toggleActionTitle, togglePendingIntent)
            .addAction(0, "Reset ⏹", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .build()
    }

    private fun stopForegroundAndSelf() {
        tickerJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerJob?.cancel()
        serviceJob.cancel()
    }
}
