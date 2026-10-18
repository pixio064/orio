package com.example.ui.dashboard

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.local.entity.AppLimitEntity
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.TaskEntity
import com.example.ui.components.AppProgressSegment
import com.example.ui.components.CircularProgressRing
import com.example.ui.components.MultiSegmentCircularProgressRing
import com.example.ui.components.SegmentPalette
import com.example.ui.components.StatCard
import com.example.usage.DayUsageSummary
import com.example.usage.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class DashboardUiState(
    val hasUsagePermission: Boolean = false,
    val usageSummary: DayUsageSummary? = null,
    val dailyGoalMinutes: Int = 180,
    val activeLimits: List<AppLimitEntity> = emptyList(),
    val nextReminder: ReminderEntity? = null,
    val pendingTasksCount: Int = 0,
    val completedTasksCount: Int = 0,
    val isLoading: Boolean = true
)

class DashboardViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun loadData(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasPermission = UsageStatsHelper.hasUsageStatsPermission(context)
            val cal = Calendar.getInstance()
            val summary = UsageStatsHelper.queryRealDayUsage(context, cal)

            val app = context.applicationContext as FocusTrackApp
            val userSettings = app.preferencesRepository.userSettingsFlow.first()
            val limits = app.database.appLimitDao().getAllLimits()
            val reminders = app.database.reminderDao().getActiveRemindersSync()
            val nextRem = reminders.minByOrNull { it.nextTriggerTimestamp.takeIf { t -> t > System.currentTimeMillis() } ?: Long.MAX_VALUE }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = dateFormat.format(Date())
            val tasks = app.database.taskDao().getTasksForDateSync(todayStr)
            val pending = tasks.count { it.outcome == com.example.data.local.entity.TaskOutcome.PENDING }
            val completed = tasks.count { it.outcome == com.example.data.local.entity.TaskOutcome.COMPLETED }

            _uiState.value = DashboardUiState(
                hasUsagePermission = hasPermission,
                usageSummary = summary,
                dailyGoalMinutes = userSettings.dailyScreenTimeGoalMinutes,
                nextReminder = nextRem,
                pendingTasksCount = pending,
                completedTasksCount = completed,
                isLoading = false
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToUsage: () -> Unit,
    onNavigateToPlanner: () -> Unit,
    onNavigateToFocus: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToAppDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadData(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val summary = uiState.usageSummary
    val screenOnMins = (summary?.totalScreenOnTimeMs ?: 0L) / (60 * 1000)
    val goalMins = uiState.dailyGoalMinutes.coerceAtLeast(1)
    val progress = (screenOnMins.toFloat() / goalMins).coerceIn(0f, 1f)

    var selectedAppSegment by remember { mutableStateOf<AppProgressSegment?>(null) }
    val appSegments = remember(summary) {
        SegmentPalette.buildSegments(
            appUsages = summary?.appUsages ?: emptyList(),
            totalScreenOnMs = summary?.totalScreenOnTimeMs ?: 0L
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val cal = Calendar.getInstance()
                        val hour = cal.get(Calendar.HOUR_OF_DAY)
                        val greeting = when {
                            hour < 12 -> "Good Morning ☀️"
                            hour < 17 -> "Good Afternoon 🌤️"
                            hour < 21 -> "Good Evening 🌅"
                            else -> "Good Night 🌙"
                        }
                        val dateFormatted = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = dateFormatted,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (com.example.BuildConfig.DEBUG) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (uiState.hasUsagePermission) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, if (uiState.hasUsagePermission) Color(0xFF16A34A) else Color(0xFFD97706)),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = if (uiState.hasUsagePermission) "Data: Real" else "Data: Need Perm",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.hasUsagePermission) Color(0xFF15803D) else Color(0xFFB45309),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
            // Permission Banner (if not granted)
            if (!uiState.hasUsagePermission) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth().testTag("permission_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Real Usage Access",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = "FocusTrack queries device UsageStats locally on-device. No data ever leaves your phone.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { UsageStatsHelper.openUsageAccessSettings(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("grant_usage_permission_btn")
                                ) {
                                    Text("Grant Access")
                                }
                            }
                        }
                    }
                }
            }

            // Samsung Wellbeing-Style Circular Progress Card
            item {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth().testTag("screen_time_card")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp).fillMaxWidth()
                    ) {
                        Text(
                            text = "Today's Screen Time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MultiSegmentCircularProgressRing(
                            segments = appSegments,
                            totalScreenOnMinutes = screenOnMins,
                            dailyGoalMinutes = goalMins,
                            selectedSegment = selectedAppSegment,
                            onSegmentSelected = { selectedAppSegment = it }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Quick Glance Metrics Row
                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${summary?.phoneSessionsCount ?: 0}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Phone Sessions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Divider(
                                modifier = Modifier.height(36.dp).width(1.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${summary?.appLaunchesCount ?: 0}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "App Launches",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Divider(
                                modifier = Modifier.height(36.dp).width(1.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val freeMins = (summary?.longestPhoneFreeIntervalMs ?: 0L) / (60 * 1000)
                                Text(
                                    text = "${freeMins}m",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "Longest Break",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Stat Cards Grid
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatCard(
                        title = "Most Used App",
                        value = summary?.mostUsedApp ?: "None",
                        subtitle = "Leading screen time",
                        icon = Icons.Default.TrendingUp,
                        iconTint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToUsage
                    )
                    StatCard(
                        title = "Tasks Progress",
                        value = "${uiState.completedTasksCount} / ${uiState.pendingTasksCount + uiState.completedTasksCount}",
                        subtitle = "${uiState.pendingTasksCount} tasks pending",
                        icon = Icons.Default.CheckCircleOutline,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToPlanner
                    )
                }
            }

            // Next Reminder Banner
            if (uiState.nextReminder != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Upcoming Reminder",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = uiState.nextReminder?.title ?: "",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Interval: Every ${uiState.nextReminder?.intervalMinutes}m • ${uiState.nextReminder?.category}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // Quick Actions Row
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = onNavigateToFocus,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).testTag("quick_focus_btn")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Focus")
                    }

                    FilledTonalButton(
                        onClick = onNavigateToPlanner,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).testTag("plan_tomorrow_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Task")
                    }
                }
            }

            // Top Apps Preview
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "App Screen Time",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = onNavigateToUsage) {
                                Text("See All")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        val topApps = summary?.appUsages?.take(4) ?: emptyList()
                        if (topApps.isEmpty()) {
                            Text(
                                text = "No app usage recorded yet today.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            topApps.forEach { app ->
                                val mins = app.totalTimeInForegroundMs / (60 * 1000)
                                val appFraction = if (screenOnMins > 0) (mins.toFloat() / screenOnMins).let { if (it.isNaN() || it.isInfinite()) 0f else it.coerceIn(0f, 1f) } else 0f
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onNavigateToAppDetail(app.packageName) }
                                        .padding(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = app.appName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${mins}m (${app.launchCount} launches)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { appFraction.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
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
