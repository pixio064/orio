package com.example.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.preferences.UserSettings
import com.example.notification.NotificationHelper
import com.example.scheduler.AlarmScheduler
import com.example.usage.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val hasUsageStatsPermission: Boolean = false,
    val canScheduleExactAlarms: Boolean = true,
    val showClearConfirmDialog: Boolean = false
)

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun load(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val s = app.preferencesRepository.userSettingsFlow.first()
            val hasUsage = UsageStatsHelper.hasUsageStatsPermission(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager?.canScheduleExactAlarms() ?: false
            } else {
                true
            }

            _uiState.value = SettingsUiState(
                settings = s,
                hasUsageStatsPermission = hasUsage,
                canScheduleExactAlarms = canExact
            )
        }
    }

    fun setScreenTimeGoal(context: Context, minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.preferencesRepository.setScreenTimeGoal(minutes)
            load(context)
        }
    }

    fun setPlanningReminder(context: Context, enabled: Boolean, hour: Int, min: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.preferencesRepository.setPlanningReminder(hour, min, enabled)
            if (enabled) {
                AlarmScheduler.scheduleDailyPlannerReminder(context, hour, min)
            }
            load(context)
        }
    }

    fun setDarkMode(context: Context, mode: Boolean?) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.preferencesRepository.setDarkMode(mode)
            load(context)
        }
    }

    fun setLanguage(context: Context, lang: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.preferencesRepository.setLanguage(lang)
            load(context)
        }
    }

    fun setQuietHours(context: Context, enabled: Boolean, start: Int, end: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.preferencesRepository.setQuietHours(enabled, start, end)
            load(context)
        }
    }

    fun clearUsageHistory(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.usageSessionDao().clearAll()
            load(context)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val s = uiState.settings

    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.load(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Privacy", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Diagnostics & Permissions
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Permission & Diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Usage Access
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("Usage Stats Access", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (uiState.hasUsageStatsPermission) "Granted (Real data active)" else "Not granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uiState.hasUsageStatsPermission) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                                )
                            }
                            if (!uiState.hasUsageStatsPermission) {
                                Button(
                                    onClick = { UsageStatsHelper.openUsageAccessSettings(context) },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Grant")
                                }
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 12.dp))

                        // Exact Alarms
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text("Exact Alarms & Pop-ups", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (uiState.canScheduleExactAlarms) "Enabled" else "Standard Inexact fallback",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }

                        Divider(modifier = Modifier.padding(vertical = 12.dp))

                        // Test Notification
                        Button(
                            onClick = { NotificationHelper.sendTestNotification(context) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("send_test_popup_btn")
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send Test Heads-Up Pop-Up Alert")
                        }
                    }
                }
            }

            // Screen Time Goal
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Daily Screen Time Goal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Target: ${s.dailyScreenTimeGoalMinutes / 60}h ${s.dailyScreenTimeGoalMinutes % 60}m per day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Slider(
                            value = s.dailyScreenTimeGoalMinutes.toFloat(),
                            onValueChange = { viewModel.setScreenTimeGoal(context, it.toInt()) },
                            valueRange = 60f..360f,
                            steps = 9
                        )
                    }
                }
            }

            // Daily Planner Reminder (11:00 PM)
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "Tomorrow Planning Reminder",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Alert at 11:00 PM to organize your next day",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = s.isPlanningReminderEnabled,
                                onCheckedChange = { viewModel.setPlanningReminder(context, it, 23, 0) }
                            )
                        }
                    }
                }
            }

            // Appearance & Language
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Appearance & Language",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Theme Preference:", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(
                                selected = s.isDarkMode == null,
                                onClick = { viewModel.setDarkMode(context, null) },
                                label = { Text("System") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = s.isDarkMode == false,
                                onClick = { viewModel.setDarkMode(context, false) },
                                label = { Text("Light") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = s.isDarkMode == true,
                                onClick = { viewModel.setDarkMode(context, true) },
                                label = { Text("Dark") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Language (ভাষা):", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(
                                selected = s.language == "en",
                                onClick = { viewModel.setLanguage(context, "en") },
                                label = { Text("English") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = s.language == "bn",
                                onClick = { viewModel.setLanguage(context, "bn") },
                                label = { Text("বাংলা (Bangla)") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Privacy & Data
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Privacy & Local Data",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "FocusTrack is 100% local-first. We do not track, collect, or upload your private usage history, tasks, or routines to any cloud server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("clear_history_btn")
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Clear Reconstructed Usage History")
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear History?") },
            text = { Text("This will clear reconstructed usage logs on-device. Your tasks, habits, and app limits will be preserved.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearUsageHistory(context)
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
