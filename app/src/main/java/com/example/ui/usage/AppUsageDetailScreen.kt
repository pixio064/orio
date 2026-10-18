package com.example.ui.usage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.notification.AppNotificationListenerService
import com.example.usage.AppHourlyUsageData
import com.example.usage.AppSessionInterval
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUsageDetailScreen(
    viewModel: AppUsageDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler {
        onBack()
    }

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

    val displayAppName = uiState.appName.ifBlank {
        uiState.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    Scaffold(
        topBar = {
            AppDetailHeader(
                appName = displayAppName,
                packageName = uiState.packageName,
                appIcon = uiState.hourlyData?.appIcon,
                onBack = onBack
            )
        },
        containerColor = Color(0xFFF7F9FC),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Date Navigation Section (Samsung One UI style)
            item {
                DateNavigationCard(
                    selectedCalendar = uiState.selectedCalendar,
                    weekDates = uiState.weekDates,
                    isWeeklyView = uiState.isWeeklyView,
                    canNavigateNextWeek = uiState.canNavigateNextWeek,
                    onSelectDate = { viewModel.selectDate(context, it) },
                    onPrevWeek = { viewModel.navigateWeek(context, -1) },
                    onNextWeek = { viewModel.navigateWeek(context, 1) },
                    onToggleWeekly = { viewModel.toggleWeeklyView(context) }
                )
            }

            // 2. App Screen Time Card with 24h Hourly Bar Chart
            item {
                AppScreenTimeCard(
                    appName = displayAppName,
                    hourlyData = uiState.hourlyData,
                    selectedCalendar = uiState.selectedCalendar,
                    selectedHour = uiState.selectedHourForScreenTime,
                    onSelectHour = { viewModel.selectHourScreenTime(it) }
                )
            }

            // 3. App Timer Card
            item {
                AppTimerCard(
                    appName = displayAppName,
                    hourlyData = uiState.hourlyData,
                    appLimit = uiState.appLimit,
                    onClick = { viewModel.showLimitDialog(true) }
                )
            }

            // 4. Notification Statistics Card with 24h Hourly Bar Chart
            item {
                NotificationStatisticsCard(
                    appName = displayAppName,
                    hourlyData = uiState.hourlyData,
                    selectedHour = uiState.selectedHourForNotification,
                    onSelectHour = { viewModel.selectHourNotification(it) },
                    onOpenNotificationSettings = {
                        AppNotificationListenerService.openNotificationAccessSettings(context)
                    }
                )
            }

            // 5. Detected Usage Time Intervals ("app gula kon kon time e use korsi tar record")
            item {
                AppUsageSessionsCard(
                    appName = displayAppName,
                    sessions = uiState.hourlyData?.detectedSessions ?: emptyList(),
                    isRealData = uiState.hourlyData?.hasRealUsageData ?: false
                )
            }
        }
    }

    // App Limit Dialog
    if (uiState.showLimitDialog) {
        val existingLimit = uiState.appLimit
        AppLimitConfigDialog(
            appName = displayAppName,
            existingMinutes = existingLimit?.limitMinutes ?: 30,
            existingWarning = existingLimit?.warningThresholdPercent ?: 80,
            existingNotify = existingLimit?.notifyOnLimit ?: true,
            hasExistingLimit = existingLimit != null,
            onDismiss = { viewModel.showLimitDialog(false) },
            onSave = { mins, warning, notify ->
                viewModel.saveLimit(context, mins, warning, notify)
            },
            onRemove = {
                viewModel.removeLimit(context)
            }
        )
    }
}

/**
 * 1. Screen Header (Samsung One UI Inspired)
 */
@Composable
fun AppDetailHeader(
    appName: String,
    packageName: String,
    appIcon: android.graphics.drawable.Drawable?,
    onBack: () -> Unit
) {
    Surface(
        color = Color(0xFFF7F9FC),
        tonalElevation = 0.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .testTag("app_detail_back_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF0F172A)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // App Icon
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE2E8F0)),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    val bitmap = remember(appIcon) {
                        try {
                            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            appIcon.setBounds(0, 0, canvas.width, canvas.height)
                            appIcon.draw(canvas)
                            bmp.asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = appName,
                            modifier = Modifier.size(36.dp)
                        )
                    } else {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFF10B981))
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 2. Date Navigation Component
 * "Today" on left, "Weekly view" on right, horizontal date selector of 7 dates.
 */
@Composable
fun DateNavigationCard(
    selectedCalendar: Calendar,
    weekDates: List<DateItem>,
    isWeeklyView: Boolean,
    canNavigateNextWeek: Boolean = false,
    onSelectDate: (Calendar) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToggleWeekly: () -> Unit
) {
    val todayCal = Calendar.getInstance()
    val isToday = isSameDay(selectedCalendar, todayCal)
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
    val displayDateText = if (isToday) "Today, ${dateFormat.format(selectedCalendar.time)}" else dateFormat.format(selectedCalendar.time)

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("date_navigation_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: "Today" on left, "Weekly view" on right
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayDateText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    if (isToday) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE8F5E9))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Today",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }

                // Weekly View Switch / Toggle
                Surface(
                    onClick = onToggleWeekly,
                    shape = RoundedCornerShape(12.dp),
                    color = if (isWeeklyView) Color(0xFFE8F5E9) else Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, if (isWeeklyView) Color(0xFF2E7D32) else Color(0xFFCBD5E1))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (isWeeklyView) Icons.Filled.DateRange else Icons.Outlined.DateRange,
                            contentDescription = null,
                            tint = if (isWeeklyView) Color(0xFF2E7D32) else Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isWeeklyView) "Weekly View" else "Daily View",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isWeeklyView) Color(0xFF2E7D32) else Color(0xFF475569)
                        )
                    }
                }
            }

            // 7-Date Horizontal Selector with Prev/Next Week buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onPrevWeek,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Previous Week",
                        tint = Color(0xFF64748B)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    weekDates.forEach { item ->
                        val isSelected = item.isSelected
                        val isClickable = !item.isFuture
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) Color(0xFFE8F5E9)
                                    else if (item.isToday) Color(0xFFF1F5F9)
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else if (item.isToday) 1.dp else 0.dp,
                                    color = if (isSelected) Color(0xFF2E7D32) else if (item.isToday) Color(0xFFCBD5E1) else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable(enabled = isClickable) { onSelectDate(item.calendar) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = item.dayOfWeek,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (item.isFuture) Color(0xFFCBD5E1) else if (isSelected) Color(0xFF2E7D32) else Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = if (item.isFuture) Color(0xFFCBD5E1) else if (isSelected) Color(0xFF2E7D32) else Color(0xFF0F172A)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onNextWeek,
                    enabled = canNavigateNextWeek,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Next Week",
                        tint = if (canNavigateNextWeek) Color(0xFF64748B) else Color(0xFFCBD5E1)
                    )
                }
            }
        }
    }
}

/**
 * 3. App Screen Time Card
 * Large white rounded card, total screen time, subtitle, green hourly usage bar chart covering 24 hours.
 */
@Composable
fun AppScreenTimeCard(
    appName: String,
    hourlyData: AppHourlyUsageData?,
    selectedCalendar: Calendar,
    selectedHour: Int?,
    onSelectHour: (Int?) -> Unit
) {
    val totalMs = hourlyData?.totalScreenTimeMs ?: 0L
    val totalMins = (totalMs / (60 * 1000)).toInt()
    val hours = totalMins / 60
    val mins = totalMins % 60
    val timeFormatted = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

    val hourlyValues = hourlyData?.hourlyScreenTimeMs ?: List(24) { 0L }
    val maxHourlyMs = (hourlyValues.maxOrNull() ?: 0L).coerceAtLeast(60 * 1000L) // at least 1 min for scaling

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("app_screen_time_card")
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header and Total
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = timeFormatted,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$appName screen time",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF64748B)
                    )
                }

                // Launch count badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE8F5E9))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${hourlyData?.launchCount ?: 0} opens",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                }
            }

            // 24-Hour Green Bar Chart
            HourlyUsageChart(
                hourlyValues = hourlyValues,
                maxValue = maxHourlyMs,
                selectedHour = selectedHour,
                barColor = Color(0xFF10B981),
                selectedBarColor = Color(0xFF047857),
                onSelectHour = onSelectHour,
                isMinutes = true
            )

            // Interactive Hour Detailed Session Breakdown (when an hour bar is clicked)
            if (selectedHour != null && selectedHour in 0..23) {
                val hourMs = hourlyValues[selectedHour]
                val hourMins = (hourMs / (60 * 1000)).toInt()
                val nextH = (selectedHour + 1) % 24
                val percentOfDay = if (totalMs > 0) ((hourMs.toFloat() / totalMs) * 100).toInt() else 0
                val hourSessions = hourlyData?.getSessionsForHour(selectedHour, selectedCalendar) ?: emptyList()

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF0FDF4),
                    border = BorderStroke(1.5.dp, Color(0xFF86EFAC)),
                    modifier = Modifier.fillMaxWidth().testTag("hour_sessions_breakdown")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccessTime,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = String.format(Locale.getDefault(), "%02d:00 – %02d:00 Details", selectedHour, nextH),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF065F46)
                                    )
                                    Text(
                                        text = if (hourSessions.isNotEmpty()) "${hourSessions.size} app opens • ${hourMins}m total ($percentOfDay% of day)" else "No usage in this hour",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF047857)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onSelectHour(null) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color(0xFF065F46),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (hourSessions.isEmpty()) {
                            Text(
                                text = "No app opens detected between ${String.format(Locale.getDefault(), "%02d:00 and %02d:00", selectedHour, nextH)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                hourSessions.forEachIndexed { idx, session ->
                                    val startCal = Calendar.getInstance().apply { timeInMillis = session.startTimestamp }
                                    val endCal = Calendar.getInstance().apply { timeInMillis = session.endTimestamp }
                                    val startText = String.format(Locale.getDefault(), "%02d:%02d", startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE))
                                    val endText = String.format(Locale.getDefault(), "%02d:%02d", endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE))
                                    val durMins = (session.durationMs / (60 * 1000L)).toInt().coerceAtLeast(1)

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, Color(0xFFDCFCE7)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    color = Color(0xFFE8F5E9),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = "Open #${idx + 1}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF2E7D32),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = "$startText to $endText",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF0F172A)
                                                )
                                            }
                                            Text(
                                                text = "${durMins} mins",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF10B981)
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
}

/**
 * 4. App Timer Card
 * Rounded white card labelled "App timer"
 */
@Composable
fun AppTimerCard(
    appName: String,
    hourlyData: AppHourlyUsageData?,
    appLimit: com.example.data.local.entity.AppLimitEntity?,
    onClick: () -> Unit
) {
    val usedMins = ((hourlyData?.totalScreenTimeMs ?: 0L) / (60 * 1000)).toInt()
    val limitMins = appLimit?.limitMinutes ?: 0
    val isLimitConfigured = limitMins > 0
    val remainingMins = (limitMins - usedMins).coerceAtLeast(0)
    val progress = if (isLimitConfigured) (usedMins.toFloat() / limitMins).coerceIn(0f, 1f) else 0f
    val isLimitReached = isLimitConfigured && usedMins >= limitMins

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("app_timer_card")
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFE8F5E9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassBottom,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "App timer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = if (isLimitConfigured) "${limitMins}m daily limit configured" else "No limit set",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isLimitReached) Color(0xFFFFEBEE) else Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, if (isLimitReached) Color(0xFFEF5350) else Color(0xFFCBD5E1))
                ) {
                    Text(
                        text = if (!isLimitConfigured) "+ Set Limit" else if (isLimitReached) "Limit Reached" else "${remainingMins}m left",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isLimitReached) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            if (isLimitConfigured) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${usedMins}m used of ${limitMins}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isLimitReached) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (isLimitReached) Color(0xFFEF4444) else Color(0xFF10B981),
                        trackColor = Color(0xFFE2E8F0)
                    )
                }
            } else {
                Text(
                    text = "Tap to set a daily goal or limit for $appName. You'll receive a timely alert before exceeding it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

/**
 * 5. Notification Statistics Card
 * Large white rounded card, total notification events observed, hourly notification bar chart.
 */
@Composable
fun NotificationStatisticsCard(
    appName: String,
    hourlyData: AppHourlyUsageData?,
    selectedHour: Int?,
    onSelectHour: (Int?) -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val totalNotifications = hourlyData?.totalNotifications ?: 0
    val hourlyCounts = hourlyData?.hourlyNotifications?.map { it.toLong() } ?: List(24) { 0L }
    val maxCount = (hourlyCounts.maxOrNull() ?: 0L).coerceAtLeast(5L)
    val hasPermission = hourlyData?.hasNotificationAccess ?: false

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("app_notifications_card")
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    if (!hasPermission) {
                        Text(
                            text = "Notification access off",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD97706)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Enable permission to record alerts",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E)
                        )
                    } else {
                        Text(
                            text = "$totalNotifications",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Notifications received",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFEDE9FE))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7C3AED)
                    )
                }
            }

            // Interactive Hour Tooltip
            if (selectedHour != null && selectedHour in 0..23) {
                val count = hourlyCounts[selectedHour]
                val nextH = (selectedHour + 1) % 24

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF581C87),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format(Locale.getDefault(), "%02d:00 - %02d:00", selectedHour, nextH),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "$count notification${if (count != 1L) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD8B4FE)
                        )
                    }
                }
            }

            // 24-Hour Notification Bar Chart (using purple / indigo styling)
            HourlyUsageChart(
                hourlyValues = hourlyCounts,
                maxValue = maxCount,
                selectedHour = selectedHour,
                barColor = Color(0xFF8B5CF6),
                selectedBarColor = Color(0xFF6D28D9),
                onSelectHour = onSelectHour,
                isMinutes = false
            )

            // Explanatory note if Notification Access is missing
            if (!hasPermission) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Live notification monitoring",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = "Enable Notification Access in Settings so FocusTrack can record every incoming alert by hour.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                        }
                        TextButton(onClick = onOpenNotificationSettings) {
                            Text("Enable", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 24-Hour Interactive Bar Chart Component
 * Horizontal axis labelled 0, 6, 12, 18, 24(h).
 * Vertical axis scaled dynamically.
 * Light gray alternating time bands and rounded bars.
 */
@Composable
fun HourlyUsageChart(
    hourlyValues: List<Long>,
    maxValue: Long,
    selectedHour: Int?,
    barColor: Color,
    selectedBarColor: Color,
    onSelectHour: (Int?) -> Unit,
    isMinutes: Boolean
) {
    val axisColor = Color(0xFF94A3B8)
    val gridColor = Color(0xFFF1F5F9)
    val altBandColor = Color(0xFFFAFAFA)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Vertical scale labels row
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            val topLabel = if (isMinutes) "${(maxValue / (60 * 1000)).coerceAtLeast(1)}m" else "$maxValue"
            val midLabel = if (isMinutes) "${(maxValue / (2 * 60 * 1000)).coerceAtLeast(0)}m" else "${maxValue / 2}"

            Text(topLabel, style = MaterialTheme.typography.labelSmall, color = axisColor)
            Text(midLabel, style = MaterialTheme.typography.labelSmall, color = axisColor)
            Text("0", style = MaterialTheme.typography.labelSmall, color = axisColor)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Canvas for 24-hour bars with tap detection
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val slotWidth = width / 24f

            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val sw = if (size.width > 0) size.width / 24f else 1f
                            val clickedH = (offset.x / sw).toInt().coerceIn(0, 23)
                            if (selectedHour == clickedH) {
                                onSelectHour(null) // toggle off
                            } else {
                                onSelectHour(clickedH)
                            }
                        }
                    }
            ) {
                val chartW = size.width
                val chartH = size.height
                val colWidth = chartW / 24f

                // 1. Draw alternating light gray time bands (e.g. 6-hour blocks)
                for (b in 0..3) {
                    if (b % 2 == 1) {
                        val bandStartX = b * 6 * colWidth
                        drawRect(
                            color = altBandColor,
                            topLeft = Offset(bandStartX, 0f),
                            size = Size(6 * colWidth, chartH)
                        )
                    }
                }

                // 2. Horizontal subtle grid lines
                drawLine(
                    color = gridColor,
                    start = Offset(0f, 0f),
                    end = Offset(chartW, 0f),
                    strokeWidth = 1f
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, chartH * 0.5f),
                    end = Offset(chartW, chartH * 0.5f),
                    strokeWidth = 1f
                )
                drawLine(
                    color = Color(0xFFE2E8F0),
                    start = Offset(0f, chartH),
                    end = Offset(chartW, chartH),
                    strokeWidth = 1.5f
                )

                // 3. Draw the 24 hourly bars
                val barW = (colWidth * 0.62f).coerceAtLeast(3f)

                for (h in 0..23) {
                    val value = hourlyValues.getOrElse(h) { 0L }
                    val fraction = if (maxValue > 0) (value.toFloat() / maxValue).let { if (it.isNaN() || it.isInfinite()) 0f else it.coerceIn(0f, 1f) } else 0f
                    val barH = (chartH * fraction).coerceAtLeast(if (value > 0) 4f else 0f)

                    val centerX = (h * colWidth) + (colWidth / 2f)
                    val left = centerX - (barW / 2f)
                    val top = chartH - barH

                    val isSelected = (selectedHour == h)

                    // Draw selection highlight background column
                    if (isSelected) {
                        drawRoundRect(
                            color = barColor.copy(alpha = 0.15f),
                            topLeft = Offset(h * colWidth, 0f),
                            size = Size(colWidth, chartH),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }

                    if (barH > 0f) {
                        drawRoundRect(
                            color = if (isSelected) selectedBarColor else barColor,
                            topLeft = Offset(left, top),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Horizontal Axis Labels: 0, 6, 12, 18, 24(h)
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            listOf("0", "6", "12", "18", "24(h)").forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = axisColor
                )
            }
        }
    }
}

/**
 * Detailed detected sessions list: "app gula kon kon time e use korsi tar record"
 */
@Composable
fun AppUsageSessionsCard(
    appName: String,
    sessions: List<AppSessionInterval>,
    isRealData: Boolean
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().testTag("app_sessions_intervals_card")
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFE8F5E9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Usage Times & Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = if (isRealData) "Observed from Android device events" else "Estimated session intervals",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Text(
                    text = "${sessions.size} sessions",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No detected usage sessions for $appName on this date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessions.forEachIndexed { index, session ->
                        val validStart = if (session.startTimestamp > 0L) session.startTimestamp else System.currentTimeMillis()
                        val validEnd = if (session.endTimestamp > 0L) session.endTimestamp else validStart + 60_000L
                        val startStr = try { timeFormat.format(Date(validStart)) } catch (e: Exception) { "--:--" }
                        val endStr = try { timeFormat.format(Date(validEnd)) } catch (e: Exception) { "--:--" }
                        val durMins = (session.durationMs / (60 * 1000)).toInt().coerceAtLeast(1)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFF8FAFC))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "$startStr – $endStr",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1E293B)
                                )
                            }
                            Text(
                                text = "${durMins}m",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * App Limit Configuration Dialog
 */
@Composable
fun AppLimitConfigDialog(
    appName: String,
    existingMinutes: Int,
    existingWarning: Int,
    existingNotify: Boolean,
    hasExistingLimit: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var limitMinutes by remember { mutableStateOf(existingMinutes) }
    var warningPercent by remember { mutableStateOf(existingWarning) }
    var notifyOnLimit by remember { mutableStateOf(existingNotify) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Daily Limit: $appName",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Configure a daily usage allowance. When the limit is reached, FocusTrack will alert you with a heads-up reminder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Presets
                Text("Quick Presets:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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

                // Slider
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Daily Allowance:", style = MaterialTheme.typography.bodyMedium)
                    Text("$limitMinutes minutes", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                }
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Early Warning at 80%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Alerts ${(limitMinutes * warningPercent / 100)}m into your limit", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
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
                onClick = { onSave(limitMinutes, warningPercent, notifyOnLimit) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                modifier = Modifier.testTag("save_app_detail_limit_btn")
            ) {
                Text("Save Limit")
            }
        },
        dismissButton = {
            if (hasExistingLimit) {
                TextButton(onClick = onRemove) {
                    Text("Remove Limit", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}
