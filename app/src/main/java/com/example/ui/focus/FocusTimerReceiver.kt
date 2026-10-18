package com.example.ui.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FocusTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            FocusTimerService.ACTION_PAUSE -> {
                FocusTimerManager.pauseTimer(context)
            }
            FocusTimerService.ACTION_RESUME -> {
                FocusTimerManager.resumeTimer(context)
            }
            FocusTimerService.ACTION_STOP -> {
                FocusTimerManager.stopTimer(context)
            }
        }
    }
}
