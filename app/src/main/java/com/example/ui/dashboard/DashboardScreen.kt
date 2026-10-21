package com.example.ui.dashboard

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.local.entity.AppLimitEntity
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskOutcome
import com.example.scheduler.AlarmScheduler
import com.example.ui.components.AppProgressSegment
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.MultiSegmentCircularProgressRing
import com.example.ui.components.SegmentPalette
import com.example.ui.components.StatCard
import com.example.ui.planner.TaskEditDialog
import com.example.ui.util.AppIcon
import com.example.usage.DayUsageSummary
import com.example.usage.UsageStatsHelper
import com.example.util.DateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class DashboardUiState(
    val hasUsagePermission: Boolean = false,
    val usageSummary: DayUsageSummary? = null,
    val dailyGoalMinutes: Int = 180,
    val activeLimits: List<AppLimitEntity> = emptyList(),
    val appUsageMap: Map<String, Long> = emptyMap(),
    val nextReminder: ReminderEntity? = null,
    val pendingTasksCount: Int = 0,
    val completedTasksCount: Int = 0,
    val totalTasksCount: Int = 0,
    val allTasksToday: List<TaskEntity> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class DashboardViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun loadData(context: Context) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val hasPermission = UsageStatsHelper.hasUsageStatsPermission(context)
                val cal = Calendar.getInstance()
                val summary = UsageStatsHelper.queryRealDayUsage(context, cal)

                val app = context.applicationContext as FocusTrackApp
                val userSettings = app.preferencesRepository.userSettingsFlow.first()
                val limits = app.database.appLimitDao().getAllLimitsList().filter { it.isEnabled }
                val reminders = app.database.reminderDao().getActiveRemindersSync()
                val now = System.currentTimeMillis()
                val nextRem = reminders.minByOrNull {
                    val t = it.nextTriggerTimestamp
                    if (t > now) t else Long.MAX_VALUE
                }

                val todayStr = DateKeys.today()
                val tasks = app.database.taskDao().getTasksForDateSync(todayStr)
                val pending = tasks.count { it.outcome == TaskOutcome.PENDING }
                val completed = tasks.count { it.outcome == TaskOutcome.COMPLETED }
                val total = tasks.size

                val usageMap = summary.appUsages.associate { it.packageName to it.totalTimeInForegroundMs }

                _uiState.update {
                    it.copy(
                        hasUsagePermission = hasPermission,
                        usageSummary = summary,
                        dailyGoalMinutes = userSettings.dailyScreenTimeGoalMinutes,
                        activeLimits = limits,
                        appUsageMap = usageMap,
                        nextReminder = nextRem,
                        pendingTasksCount = pending,
                        completedTasksCount = completed,
                        totalTasksCount = total,
                        allTasksToday = tasks,
                        isLoading = false
                    )
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun saveTask(context: Context, task: TaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                val id = app.database.taskDao().insertTask(task)
                val savedTask = if (task.id == 0L) task.copy(id = id) else task
                if (savedTask.reminderMinutesBefore >= 0 && savedTask.outcome == TaskOutcome.PENDING) {
                    AlarmScheduler.scheduleTask(context, savedTask)
                }
                loadData(context)
            } catch (_: Throwable) {}
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

    var showAddTaskDialog by remember { mutableStateOf(false) }

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
                        Text(
                            text = "PIXIO 1.1",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Daily Screen Balance & Productivity",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("dashboard_settings_btn")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Open Settings")
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
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 80.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Permission Alert Banner
            if (!uiState.hasUsagePermission) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().testTag("permission_banner")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Usage Access Required",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "PIXIO needs permission to accurately measure real app screen time and enforce limits.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                            Button(
                                onClick = onOpenSettings,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Grant")
                            }
                        }
                    }
                }
            }

            // Error state banner if error occurred
            if (uiState.errorMessage != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = "Info", tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Notice: ${uiState.errorMessage}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Circular Screen Time Progress Card
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
                            HorizontalDivider(
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
                            HorizontalDivider(
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

            // Stat Cards Grid: Most Used App & Tasks Progress (denominator includes all tasks)
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
                        value = "${uiState.completedTasksCount} / ${uiState.totalTasksCount}",
                        subtitle = "${uiState.pendingTasksCount} tasks pending",
                        icon = Icons.Default.CheckCircleOutline,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToPlanner
                    )
                }
            }

            // App Limits & Enforcement Progress Card (Item 1)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().testTag("app_limits_dashboard_card")
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Active App Limits",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = onNavigateToUsage) {
                                Text("Manage Limits")
                            }
                        }

                        if (uiState.activeLimits.isEmpty()) {
                            Text(
                                text = "No app limits set. You can set daily usage thresholds for apps in Usage & Apps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                uiState.activeLimits.forEach { limit ->
                                    val usedMs = uiState.appUsageMap[limit.packageName] ?: 0L
                                    val usedMins = (usedMs / (60 * 1000)).toInt()
                                    val limitMins = limit.limitMinutes.coerceAtLeast(1)
                                    val limitRatio = (usedMins.toFloat() / limitMins).coerceIn(0f, 1f)
                                    val isOverLimit = usedMins >= limitMins
                                    val isWarning = usedMins >= (limitMins * limit.warningThresholdPercent / 100)

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable { onNavigateToAppDetail(limit.packageName) }
                                    ) {
                                        AppIcon(
                                            packageName = limit.packageName,
                                            size = 36.dp,
                                            contentDescription = limit.appName
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = limit.appName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "$usedMins / $limitMins min",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when {
                                                        isOverLimit -> MaterialTheme.colorScheme.error
                                                        isWarning -> Color(0xFFFF6F00)
                                                        else -> MaterialTheme.colorScheme.primary
                                                    }
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = { limitRatio },
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                color = when {
                                                    isOverLimit -> MaterialTheme.colorScheme.error
                                                    isWarning -> Color(0xFFFF6F00)
                                                    else -> MaterialTheme.colorScheme.primary
                                                },
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
                                    contentDescription = "Active Reminder",
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

            // Quick Actions Row: "Start Focus" & "Add Task" (opens dialog directly)
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
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start Focus")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Focus")
                    }

                    FilledTonalButton(
                        onClick = { showAddTaskDialog = true },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).testTag("plan_tomorrow_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Task")
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

                        if (summary?.appUsages.isNullOrEmpty()) {
                            Text(
                                text = "No app usage recorded yet today.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val topApps = summary?.appUsages?.take(4) ?: emptyList()
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                topApps.forEach { appItem ->
                                    val minutes = appItem.totalTimeInForegroundMs / (60 * 1000)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable { onNavigateToAppDetail(appItem.packageName) }
                                    ) {
                                        AppIcon(
                                            packageName = appItem.packageName,
                                            size = 38.dp,
                                            contentDescription = appItem.appName
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = appItem.appName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${appItem.launchCount} launches • ${appItem.category}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
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

    if (showAddTaskDialog) {
        TaskEditDialog(
            existingTask = null,
            defaultDate = DateKeys.today(),
            existingTasksOnDate = uiState.allTasksToday,
            onDismiss = { showAddTaskDialog = false },
            onSave = { task ->
                viewModel.saveTask(context, task)
                showAddTaskDialog = false
            }
        )
    }
}
