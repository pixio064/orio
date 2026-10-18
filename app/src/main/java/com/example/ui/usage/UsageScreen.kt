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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.local.entity.AppLimitEntity
import com.example.ui.components.EmptyStateCard
import com.example.usage.AppUsageInfo
import com.example.usage.DayUsageSummary
import com.example.usage.UsageStatsHelper
import com.example.usage.UsageTimelineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class UsageUiState(
    val hasPermission: Boolean = false,
    val summary: DayUsageSummary? = null,
    val limitsMap: Map<String, AppLimitEntity> = emptyMap(),
    val searchQuery: String = "",
    val selectedCategory: String = "All",
    val selectedTab: Int = 0, // 0: Apps & Limits, 1: Timeline
    val isLoading: Boolean = true
)

class UsageViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    fun loadData(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val hasPermission = UsageStatsHelper.hasUsageStatsPermission(context)
            val cal = Calendar.getInstance()
            val summary = UsageStatsHelper.queryRealDayUsage(context, cal)

            val app = context.applicationContext as FocusTrackApp
            val limitsList = app.database.appLimitDao().getAllLimits().first()
            val map = limitsList.associateBy { it.packageName }

            _uiState.value = _uiState.value.copy(
                hasPermission = hasPermission,
                summary = summary,
                limitsMap = map,
                isLoading = false
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun setSelectedCategory(cat: String) {
        _uiState.value = _uiState.value.copy(selectedCategory = cat)
    }

    fun setSelectedTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun saveAppLimit(context: Context, appLimit: AppLimitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.appLimitDao().insertOrUpdate(appLimit)
            loadData(context)
        }
    }

    fun removeAppLimit(context: Context, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.appLimitDao().deleteByPackage(packageName)
            loadData(context)
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

    val categories = listOf("All", "Most Used", "Social", "Entertainment", "Productivity", "Education")

    val filteredApps = remember(uiState.summary, uiState.searchQuery, uiState.selectedCategory) {
        val list = uiState.summary?.appUsages ?: emptyList()
        list.filter { app ->
            val matchesQuery = app.appName.contains(uiState.searchQuery, ignoreCase = true)
            val matchesCat = when (uiState.selectedCategory) {
                "All" -> true
                "Most Used" -> true
                else -> app.category.equals(uiState.selectedCategory, ignoreCase = true)
            }
            matchesQuery && matchesCat
        }.let {
            if (uiState.selectedCategory == "Most Used") it.sortedByDescending { a -> a.totalTimeInForegroundMs } else it
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "App Usage & Limits",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    if (com.example.BuildConfig.DEBUG) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (uiState.hasPermission) Color(0xFFDCFCE7) else Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, if (uiState.hasPermission) Color(0xFF16A34A) else Color(0xFFD97706)),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = if (uiState.hasPermission) "Data: Real" else "Data: Need Perm",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.hasPermission) Color(0xFF15803D) else Color(0xFFB45309),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
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
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    modifier = Modifier.testTag("tab_apps_limits")
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    text = { Text("Usage Timeline") },
                    icon = { Icon(Icons.Default.Timeline, contentDescription = null) },
                    modifier = Modifier.testTag("tab_usage_timeline")
                )
            }

            if (uiState.selectedTab == 0) {
                // Search Field
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("search_apps_input")
                )

                // Category Chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = uiState.selectedCategory == cat,
                            onClick = { viewModel.setSelectedCategory(cat) },
                            label = { Text(cat) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // Apps List
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (filteredApps.isEmpty()) {
                        item {
                            EmptyStateCard(
                                title = "No Apps Found",
                                description = "No application matches your current search or category filter.",
                                icon = Icons.Outlined.SearchOff
                            )
                        }
                    } else {
                        itemsIndexed(filteredApps, key = { index, appInfo -> "${appInfo.packageName}_$index" }) { _, appInfo ->
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
                // Usage Timeline Tab
                val timeline = uiState.summary?.timelineEvents ?: emptyList()
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (timeline.isEmpty()) {
                        item {
                            EmptyStateCard(
                                title = "No Timeline Events",
                                description = "Real phone usage events will be reconstructed here as you use your device.",
                                icon = Icons.Outlined.History
                            )
                        }
                    } else {
                        itemsIndexed(timeline, key = { index, event -> "${event.timestamp}_$index" }) { _, event ->
                            TimelineEventCard(event = event)
                        }
                    }
                }
            }
        }
    }

    // App Limit Dialog
    if (selectedAppForLimit != null) {
        val app = selectedAppForLimit!!
        val existingLimit = uiState.limitsMap[app.packageName]
        var limitMinutes by remember { mutableStateOf(existingLimit?.limitMinutes ?: 30) }
        var warningThreshold by remember { mutableStateOf(existingLimit?.warningThresholdPercent ?: 80) }
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
                        text = "Set a daily usage threshold. You'll receive a heads-up pop-up when approaching and reaching this limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Presets
                    Text("Preset Limits:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(15, 30, 45, 60).forEach { mins ->
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

                    // 80% Warning Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("Warning at 80% ($warningThreshold%)", style = MaterialTheme.typography.bodyMedium)
                            Text("Alert ${(limitMinutes * warningThreshold / 100)}m into limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                notifyOnWarning = notifyOnLimit
                            )
                        )
                        selectedAppForLimit = null
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("save_limit_btn")
                ) {
                    Text("Set Limit")
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
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
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
fun TimelineEventCard(event: UsageTimelineEvent) {
    val timeFormatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(event.timestamp))

    val (icon, color) = when (event.eventType) {
        "SCREEN_ON" -> Pair(Icons.Default.Visibility, MaterialTheme.colorScheme.primary)
        "SCREEN_OFF" -> Pair(Icons.Default.VisibilityOff, MaterialTheme.colorScheme.outline)
        "KEYGUARD_HIDDEN" -> Pair(Icons.Default.LockOpen, MaterialTheme.colorScheme.secondary)
        "APP_FOREGROUND" -> Pair(Icons.Default.Launch, MaterialTheme.colorScheme.tertiary)
        else -> Pair(Icons.Default.Spa, MaterialTheme.colorScheme.secondary)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (event.isEstimated) {
                    Text(
                        text = "Estimated interval",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Text(
                text = timeFormatted,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
