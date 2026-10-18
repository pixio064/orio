package com.example.ui.focus

import android.content.Context
import android.os.SystemClock
import com.example.FocusTrackApp
import com.example.data.local.entity.FocusSessionEntity
import com.example.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FocusTimerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _selectedMinutes = MutableStateFlow(25)
    val selectedMinutes: StateFlow<Int> = _selectedMinutes.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(25 * 60)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _totalSeconds = MutableStateFlow(25 * 60)
    val totalSeconds: StateFlow<Int> = _totalSeconds.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Floating Mini-Timer Widget visibility for inside the app
    private val _isFloatingWidgetVisible = MutableStateFlow(false)
    val isFloatingWidgetVisible: StateFlow<Boolean> = _isFloatingWidgetVisible.asStateFlow()

    var sessionStartTime: Long = 0L
        private set

    fun setPreset(minutes: Int) {
        if (!_isRunning.value) {
            _selectedMinutes.value = minutes
            _totalSeconds.value = minutes * 60
            _remainingSeconds.value = minutes * 60
        }
    }

    fun startTimer(context: Context, minutes: Int? = null) {
        val targetMins = minutes ?: _selectedMinutes.value
        _selectedMinutes.value = targetMins
        _totalSeconds.value = targetMins * 60
        _remainingSeconds.value = targetMins * 60
        _isRunning.value = true
        _isPaused.value = false
        sessionStartTime = System.currentTimeMillis()

        FocusTimerService.startService(context, targetMins * 60, targetMins)
    }

    fun resumeTimer(context: Context) {
        if (_isRunning.value && _isPaused.value) {
            _isPaused.value = false
            FocusTimerService.resumeService(context)
        }
    }

    fun pauseTimer(context: Context) {
        if (_isRunning.value && !_isPaused.value) {
            _isPaused.value = true
            FocusTimerService.pauseService(context)
        }
    }

    fun stopTimer(context: Context) {
        _isRunning.value = false
        _isPaused.value = false
        _remainingSeconds.value = _selectedMinutes.value * 60
        _isFloatingWidgetVisible.value = false
        FocusTimerService.stopService(context)
    }

    fun updateTick(secondsLeft: Int) {
        _remainingSeconds.value = secondsLeft.coerceAtLeast(0)
    }

    fun completeSession(context: Context) {
        val durationMins = _selectedMinutes.value
        _isRunning.value = false
        _isPaused.value = false
        _remainingSeconds.value = durationMins * 60
        _isFloatingWidgetVisible.value = false

        NotificationHelper.showFocusSessionCompleted(context, durationMins)

        scope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val today = dateFormat.format(Date())

                app.database.focusSessionDao().insertFocusSession(
                    FocusSessionEntity(
                        startTimestamp = sessionStartTime,
                        endTimestamp = System.currentTimeMillis(),
                        targetMinutes = durationMins,
                        completedMinutes = durationMins,
                        isCompleted = true,
                        dateString = today
                    )
                )
            } catch (e: Exception) {
                // Ignore DB insertion error
            }
        }
    }

    fun showFloatingWidget() {
        _isFloatingWidgetVisible.value = true
    }

    fun dismissFloatingWidget() {
        _isFloatingWidgetVisible.value = false
    }
}
