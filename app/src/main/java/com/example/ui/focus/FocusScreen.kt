package com.example.ui.focus

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.ai.HabitCoachEngine
import com.example.ai.HabitCoachInsight
import com.example.data.local.entity.FocusSessionEntity
import com.example.notification.NotificationHelper
import com.example.ui.components.CircularProgressRing
import com.example.usage.DayUsageSummary
import com.example.usage.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class FocusUiState(
    val selectedMinutes: Int = 25,
    val remainingSeconds: Int = 25 * 60,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val totalFocusTodayMinutes: Int = 0,
    val pastSessions: List<FocusSessionEntity> = emptyList(),
    val coachInsights: List<HabitCoachInsight> = emptyList(),
    val selectedTab: Int = 0 // 0: Focus Timer, 1: AI Wellbeing Coach
)

class FocusViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FocusUiState())
    val uiState: StateFlow<FocusUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var sessionStartTime: Long = 0L

    fun setSelectedTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun setTimerPreset(minutes: Int) {
        FocusTimerManager.setPreset(minutes)
    }

    fun startTimer(context: Context) {
        FocusTimerManager.startTimer(context)
    }

    fun pauseTimer(context: Context) {
        FocusTimerManager.pauseTimer(context)
    }

    fun resumeTimer(context: Context) {
        FocusTimerManager.resumeTimer(context)
    }

    fun stopTimer(context: Context) {
        FocusTimerManager.stopTimer(context)
    }

    private fun completeSession(context: Context) {
        val durationMins = _uiState.value.selectedMinutes
        _uiState.value = _uiState.value.copy(
            isRunning = false,
            isPaused = false,
            remainingSeconds = durationMins * 60
        )

        NotificationHelper.showFocusSessionCompleted(context, durationMins)

        viewModelScope.launch(Dispatchers.IO) {
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
            loadData(context)
        }
    }

    fun loadData(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val today = dateFormat.format(Date())

            val sessions = app.database.focusSessionDao().getFocusSessionsForDate(today).first()
            val totalMins = sessions.sumOf { it.completedMinutes }

            // AI Coach Insights
            val summary = UsageStatsHelper.queryRealDayUsage(context, Calendar.getInstance())
            val settings = app.preferencesRepository.userSettingsFlow.first()
            val tasks = app.database.taskDao().getTasksForDateSync(today)
            val habits = app.database.habitDao().getAllActiveHabits().first()

            val insights = HabitCoachEngine.generateInsights(
                usageSummary = summary,
                screenTimeGoalMinutes = settings.dailyScreenTimeGoalMinutes,
                tasks = tasks,
                habits = habits
            )

            _uiState.value = _uiState.value.copy(
                totalFocusTodayMinutes = totalMins,
                pastSessions = sessions,
                coachInsights = insights
            )
        }
    }

    fun exportReport(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val today = dateFormat.format(Date())
            val summary = UsageStatsHelper.queryRealDayUsage(context, Calendar.getInstance())
            val tasks = app.database.taskDao().getTasksForDateSync(today)
            val habits = app.database.habitDao().getAllActiveHabits().first()

            val report = HabitCoachEngine.exportReportAsText(summary, tasks, habits)

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, report)
                type = "text/plain"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(sendIntent, "Export FocusTrack Report").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    viewModel: FocusViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val selectedMinutes by FocusTimerManager.selectedMinutes.collectAsState()
    val remainingSeconds by FocusTimerManager.remainingSeconds.collectAsState()
    val totalSeconds by FocusTimerManager.totalSeconds.collectAsState()
    val isRunning by FocusTimerManager.isRunning.collectAsState()
    val isPaused by FocusTimerManager.isPaused.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadData(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Focus Mode & Coach",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.exportReport(context) },
                        modifier = Modifier.testTag("export_report_btn")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export Report")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = uiState.selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    text = { Text("Focus Timer") },
                    icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                    modifier = Modifier.testTag("tab_focus_timer")
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    text = { Text("AI Habit Coach") },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                    modifier = Modifier.testTag("tab_ai_coach")
                )
            }

            if (uiState.selectedTab == 0) {
                // Focus Timer View
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        // Presets Row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(25, 45, 50, 60).forEach { mins ->
                                FilterChip(
                                    selected = selectedMinutes == mins && !isRunning,
                                    onClick = { viewModel.setTimerPreset(mins) },
                                    label = { Text("${mins}m Focus") },
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isRunning,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(28.dp).fillMaxWidth()
                            ) {
                                val totalSec = totalSeconds.coerceAtLeast(1)
                                val progress = (remainingSeconds.toFloat() / totalSec).coerceIn(0f, 1f)

                                CircularProgressRing(
                                    progress = progress,
                                    modifier = Modifier.size(220.dp),
                                    strokeWidth = 14.dp,
                                    progressColor = MaterialTheme.colorScheme.primary
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        val m = remainingSeconds / 60
                                        val s = remainingSeconds % 60
                                        Text(
                                            text = String.format("%02d:%02d", m, s),
                                            style = MaterialTheme.typography.displaySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (isRunning) (if (isPaused) "PAUSED ⏸" else "FOCUSING ⏱️") else "READY",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                // Controls
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!isRunning) {
                                        Button(
                                            onClick = { viewModel.startTimer(context) },
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.height(52.dp).width(160.dp).testTag("start_focus_btn")
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Start Focus", style = MaterialTheme.typography.titleMedium)
                                        }
                                    } else {
                                        if (isPaused) {
                                            Button(
                                                onClick = { viewModel.resumeTimer(context) },
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.height(52.dp).width(130.dp)
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Resume")
                                            }
                                        } else {
                                            FilledTonalButton(
                                                onClick = { viewModel.pauseTimer(context) },
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.height(52.dp).width(130.dp)
                                            ) {
                                                Icon(Icons.Default.Pause, contentDescription = null)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Pause")
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.stopTimer(context) },
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.height(52.dp).width(110.dp)
                                        ) {
                                            Text("Reset")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Minimize to Pop-up (Picture-in-Picture & Floating Window)
                                Button(
                                    onClick = {
                                        if (!isRunning) {
                                            viewModel.startTimer(context)
                                        }
                                        val activity = context as? Activity
                                        val enteredPip = activity?.let { FocusPipHelper.enterPipMode(it) } ?: false
                                        if (!enteredPip) {
                                            FocusTimerManager.showFloatingWidget()
                                        }
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.testTag("pop_up_timer_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Minimize to Pop-up 🗗", fontWeight = FontWeight.SemiBold)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Background Service Indicator Card
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.NotificationsActive,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Runs in background with ongoing notification & pop-up window",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(16.dp).fillMaxWidth()
                            ) {
                                Column {
                                    Text("Today's Total Focus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${uiState.pastSessions.size} sessions completed today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    text = "${uiState.totalFocusTodayMinutes} min",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            } else {
                // AI Habit Coach Tab
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "On-Device Digital Wellbeing Coach",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "All insights are computed 100% locally from your device usage patterns, tasks, and streaks. No personal data is sent externally.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    items(uiState.coachInsights, key = { it.id }) { insight ->
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = insight.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = insight.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(10.dp).fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lightbulb,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = insight.recommendation,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
