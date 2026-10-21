package com.example.ui.usage

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.local.entity.AppLimitEntity
import com.example.ui.components.EmptyStateCard
import com.example.ui.util.AppIcon
import com.example.usage.AppUsageInfo
import com.example.usage.DayUsageSummary
import com.example.usage.UsageStatsHelper
import com.example.usage.UsageTimelineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.*

data class UsageUiState(
    val hasPermission: Boolean = false,
    val summary: DayUsageSummary? = null,
    val limitsMap: Map<String, AppLimitEntity> = emptyMap(),
    val searchQuery: String = "",
    val selectedCategory: String = "All",
    val selectedTab: Int = 0, // 0: Apps & Limits, 1: Timeline
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class UsageViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

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
                val limitsList = app.database.appLimitDao().getAllLimits().first()
                val map = limitsList.associateBy { it.packageName }

                _uiState.update {
                    it.copy(
                        hasPermission = hasPermission,
                        summary = summary,
                        limitsMap = map,
                        isLoading = false
                    )
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedCategory(cat: String) {
        _uiState.update { it.copy(selectedCategory = cat) }
    }

    fun setSelectedTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun saveAppLimit(context: Context, appLimit: AppLimitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                app.database.appLimitDao().insertOrUpdate(appLimit)
                loadData(context)
            } catch (_: Throwable) {}
        }
    }

    fun removeAppLimit(context: Context, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                app.database.appLimitDao().deleteByPackage(packageName)
                loadData(context)
            } catch (_: Throwable) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    viewModel: UsageViewModel,
    onBack: (() -> Unit)? = null,
    onNavigateToAppDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedAppForLimit by remember { mutableStateOf<AppUsageInfo?>(null) }

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

    val allApps = uiState.summary?.appUsages ?: emptyList()
    val categories = remember(allApps) {
        listOf("All") + allApps.map { it.category }.distinct().filter { it.isNotBlank() }
    }

    val filteredApps = remember(allApps, uiState.searchQuery, uiState.selectedCategory) {
        allApps.filter { app ->
            val matchesQuery = app.appName.contains(uiState.searchQuery, ignoreCase = true) ||
                    app.packageName.contains(uiState.searchQuery, ignoreCase = true)
            val matchesCategory = uiState.selectedCategory == "All" || app.category == uiState.selectedCategory
            matchesQuery && matchesCategory
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "App Usage & Screen Time",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
            // Tab Selector
            TabRow(
                selectedTabIndex = uiState.selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.selectedTab == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    text = { Text("Apps & Limits") },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "Apps Tab") },
                    modifier = Modifier.testTag("tab_apps_limits")
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    text = { Text("Activity Timeline") },
                    icon = { Icon(Icons.Default.Timeline, contentDescription = "Timeline Tab") },
                    modifier = Modifier.testTag("tab_timeline")
                )
            }

            if (uiState.selectedTab == 0) {
                // Search Field
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search installed apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("search_apps_input")
                )

                // Category Chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = uiState.selectedCategory == cat,
                            onClick = { viewModel.setSelectedCategory(cat) },
                            label = { Text(cat) },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                // App Usage Cards List
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (filteredApps.isEmpty()) {
                        item {
                            EmptyStateCard(
                                title = "No Apps Found",
                                description = if (uiState.hasPermission) "No app activity matches your search filter."
                                else "Grant Usage Stats permission to view per-app active time.",
                                icon = Icons.Outlined.SearchOff
                            )
                        }
                    } else {
                        items(filteredApps, key = { it.packageName }) { appInfo ->
                            val limit = uiState.limitsMap[appInfo.packageName]
                            AppUsageCard(
                                app = appInfo,
                                limit = limit,
                                onClick = { onNavigateToAppDetail(appInfo.packageName) },
                                onConfigureLimit = { selectedAppForLimit = appInfo }
                            )
                        }
                    }
                }
            } else {
                // Timeline Tab (with Screen On, Screen Off, Unlocks, and App events)
                val timeline = uiState.summary?.timelineEvents ?: emptyList()
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (timeline.isEmpty()) {
                        item {
                            EmptyStateCard(
                                title = "No Timeline Events Yet",
                                description = "Unlocks, screen sessions, and app switches will appear here chronologically.",
                                icon = Icons.Outlined.HourglassEmpty
                            )
                        }
                    } else {
                        itemsIndexed(timeline) { index, event ->
                            TimelineCard(event = event, isLast = index == timeline.lastIndex)
                        }
                    }
                }
            }
        }
    }

    // App Limit Dialog with separate switches and editable warning threshold
    if (selectedAppForLimit != null) {
        val app = selectedAppForLimit!!
        val existingLimit = uiState.limitsMap[app.packageName]
        var limitMinutes by remember { mutableStateOf(existingLimit?.limitMinutes ?: 30) }
        var warningThreshold by remember { mutableStateOf(existingLimit?.warningThresholdPercent ?: 80) }
        var notifyOnWarning by remember { mutableStateOf(existingLimit?.notifyOnWarning ?: true) }
        var notifyOnLimit by remember { mutableStateOf(existingLimit?.notifyOnLimit ?: true) }

        AlertDialog(
            onDismissRequest = { selectedAppForLimit = null },
            title = {
                Text(
                    text = "Daily Limit: ${app.appName}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Set a daily usage threshold. You'll receive pop-up alerts when approaching and reaching this limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Presets
                    Text("Preset Limits:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(15, 30, 45, 60, 90).forEach { mins ->
                            FilterChip(
                                selected = limitMinutes == mins,
                                onClick = { limitMinutes = mins },
                                label = { Text("${mins}m") },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    // Custom Slider
                    Text("Selected Limit: $limitMinutes minutes", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = limitMinutes.toFloat(),
                        onValueChange = { limitMinutes = it.toInt() },
                        valueRange = 5f..180f,
                        steps = 34
                    )

                    // Editable Warning Threshold Slider
                    val warnMinutes = (limitMinutes * warningThreshold / 100)
                    Text("Warning Threshold: $warningThreshold% (${warnMinutes}m)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = warningThreshold.toFloat(),
                        onValueChange = { warningThreshold = it.toInt() },
                        valueRange = 50f..95f,
                        steps = 8
                    )

                    // Switch 1: Warning Alert
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notify on Approaching ($warningThreshold%)", style = MaterialTheme.typography.bodyMedium)
                            Text("Alerts once at $warnMinutes min into limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = notifyOnWarning,
                            onCheckedChange = { notifyOnWarning = it }
                        )
                    }

                    // Switch 2: Limit Reached Alert
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Notify at 100% Daily Limit", style = MaterialTheme.typography.bodyMedium)
                            Text("Alerts once at full $limitMinutes min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = notifyOnLimit,
                            onCheckedChange = { notifyOnLimit = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveAppLimit(
                            context = context,
                            appLimit = AppLimitEntity(
                                packageName = app.packageName,
                                appName = app.appName,
                                limitMinutes = limitMinutes,
                                warningThresholdPercent = warningThreshold,
                                isEnabled = true,
                                category = app.category,
                                notifyOnLimit = notifyOnLimit,
                                notifyOnWarning = notifyOnWarning
                            )
                        )
                        selectedAppForLimit = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("save_limit_btn")
                ) {
                    Text("Save Limit")
                }
            },
            dismissButton = {
                if (existingLimit != null) {
                    TextButton(
                        onClick = {
                            viewModel.removeAppLimit(context, app.packageName)
                            selectedAppForLimit = null
                        }
                    ) {
                        Text("Remove Limit", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = { selectedAppForLimit = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
fun AppUsageCard(
    app: AppUsageInfo,
    limit: AppLimitEntity?,
    onClick: () -> Unit,
    onConfigureLimit: () -> Unit
) {
    val usedMins = (app.totalTimeInForegroundMs / (60 * 1000)).toInt()
    val limitMins = limit?.limitMinutes ?: 0
    val progress = if (limitMins > 0) (usedMins.toFloat() / limitMins).let { if (it.isNaN() || it.isInfinite()) 0f else it.coerceIn(0f, 1f) } else 0f
    val isNearLimit = limitMins > 0 && usedMins >= (limitMins * (limit?.warningThresholdPercent ?: 80) / 100)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("app_usage_${app.packageName.replace(".", "_")}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AppIcon(
                    packageName = app.packageName,
                    size = 46.dp,
                    contentDescription = app.appName
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${app.category} • ${app.launchCount} launches • avg ${app.averageSessionDurationMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clickable { onConfigureLimit() }
                ) {
                    Text(
                        text = "${usedMins}m",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    if (limitMins > 0) {
                        Text(
                            text = "Limit: ${limitMins}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "+ Set Limit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (limitMins > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
fun TimelineCard(
    event: UsageTimelineEvent,
    isLast: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val cal = Calendar.getInstance().apply { timeInMillis = event.timestamp }
        val timeString = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        Text(
            text = timeString,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(44.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    when (event.eventType) {
                        "SCREEN_ON" -> MaterialTheme.colorScheme.primaryContainer
                        "SCREEN_UNLOCK" -> MaterialTheme.colorScheme.secondaryContainer
                        "SCREEN_OFF" -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (event.eventType) {
                "SCREEN_ON" -> Icon(Icons.Default.PhoneAndroid, contentDescription = "Screen On", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                "SCREEN_UNLOCK" -> Icon(Icons.Default.LockOpen, contentDescription = "Unlock", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                "SCREEN_OFF" -> Icon(Icons.Default.Lock, contentDescription = "Screen Off", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                else -> {
                    if (event.packageName.isNotBlank()) {
                        AppIcon(packageName = event.packageName, size = 24.dp)
                    } else {
                        Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.description,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (event.appName.isNotBlank() && event.eventType == "APP_FOREGROUND") {
                Text(
                    text = event.appName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
