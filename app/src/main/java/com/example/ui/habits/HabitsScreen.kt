package com.example.ui.habits

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.local.entity.HabitEntity
import com.example.data.local.entity.HabitLogEntity
import com.example.data.local.entity.ReminderEntity
import com.example.data.local.entity.ReminderFrequency
import com.example.notification.NotificationHelper
import com.example.scheduler.AlarmScheduler
import com.example.ui.components.EmptyStateCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class HabitsUiState(
    val reminders: List<ReminderEntity> = emptyList(),
    val habits: List<HabitEntity> = emptyList(),
    val todayHabitLogs: Set<Long> = emptySet(), // completed habit IDs today
    val selectedTab: Int = 0, // 0: Reminders & Alerts, 1: Habits & Streaks
    val isLoading: Boolean = true
)

data class ReminderTemplate(
    val title: String,
    val description: String,
    val category: String,
    val intervalMinutes: Int,
    val startHour: Int = 9,
    val endHour: Int = 21,
    val iconName: String = "WaterDrop"
)

val PREMADE_TEMPLATES = listOf(
    ReminderTemplate("Hydration Routine", "Drink a glass of water to stay energized", "Hydration", 30),
    ReminderTemplate("20-20-20 Eye Rest", "Look at something 20 feet away for 20 seconds", "Eye-Rest", 20),
    ReminderTemplate("Anti-Scrolling Pause", "Check in: Are you using your phone intentionally?", "Anti-Scroll", 25),
    ReminderTemplate("Desk Stretch & Posture", "Straighten back, roll shoulders, and stretch", "Stretch", 60),
    ReminderTemplate("Study Focus Pomodoro", "Focus sprint reminder", "Focus", 50)
)

class HabitsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HabitsUiState())
    val uiState: StateFlow<HabitsUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun loadData(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val remindersList = app.database.reminderDao().getAllReminders().first()
            val habitsList = app.database.habitDao().getAllHabits().first()

            val today = dateFormat.format(Date())
            val logs = app.database.habitDao().getHabitLogsForDate(today).first()
            val completedIds = logs.map { it.habitId }.toSet()

            _uiState.value = _uiState.value.copy(
                reminders = remindersList,
                habits = habitsList,
                todayHabitLogs = completedIds,
                isLoading = false
            )
        }
    }

    fun setSelectedTab(tab: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun toggleReminder(context: Context, reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val updated = reminder.copy(isEnabled = !reminder.isEnabled)
            app.database.reminderDao().updateReminder(updated)
            if (updated.isEnabled) {
                AlarmScheduler.scheduleReminder(context, updated)
            } else {
                AlarmScheduler.cancelReminder(context, updated.id)
            }
            loadData(context)
        }
    }

    fun saveReminder(context: Context, reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val id = app.database.reminderDao().insertReminder(reminder)
            val saved = if (reminder.id == 0L) reminder.copy(id = id) else reminder
            AlarmScheduler.scheduleReminder(context, saved)
            loadData(context)
        }
    }

    fun deleteReminder(context: Context, reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.reminderDao().deleteReminder(reminder)
            AlarmScheduler.cancelReminder(context, reminder.id)
            loadData(context)
        }
    }

    fun applyTemplate(context: Context, template: ReminderTemplate) {
        val reminder = ReminderEntity(
            title = template.title,
            description = template.description,
            category = template.category,
            frequency = ReminderFrequency.INTERVAL_MINUTES,
            intervalMinutes = template.intervalMinutes,
            startHour = template.startHour,
            endHour = template.endHour,
            isEnabled = true
        )
        saveReminder(context, reminder)
    }

    fun toggleHabitCompletion(context: Context, habit: HabitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val today = dateFormat.format(Date())
            val isCompleted = _uiState.value.todayHabitLogs.contains(habit.id)

            if (isCompleted) {
                app.database.habitDao().deleteHabitLog(habit.id, today)
                val newStreak = (habit.currentStreak - 1).coerceAtLeast(0)
                app.database.habitDao().updateHabit(habit.copy(currentStreak = newStreak))
            } else {
                app.database.habitDao().insertHabitLog(HabitLogEntity(habitId = habit.id, dateString = today))
                val newStreak = habit.currentStreak + 1
                val newBest = maxOf(newStreak, habit.bestStreak)
                app.database.habitDao().updateHabit(habit.copy(currentStreak = newStreak, bestStreak = newBest, lastCompletedDate = today))
            }
            loadData(context)
        }
    }

    fun saveHabit(context: Context, habit: HabitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.habitDao().insertHabit(habit)
            loadData(context)
        }
    }

    fun deleteHabit(context: Context, habit: HabitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.habitDao().deleteHabit(habit)
            loadData(context)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitsScreen(
    viewModel: HabitsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var showCreateReminderDialog by remember { mutableStateOf(false) }
    var showCreateHabitDialog by remember { mutableStateOf(false) }
    var naturalLanguageInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadData(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Habits & Smart Reminders",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    FilledTonalButton(
                        onClick = { NotificationHelper.sendTestNotification(context) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp).testTag("test_notification_btn")
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Pop-Up", style = MaterialTheme.typography.labelSmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (uiState.selectedTab == 0) showCreateReminderDialog = true else showCreateHabitDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("fab_add_habit_or_reminder")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
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
                    text = { Text("Pop-up Reminders") },
                    icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    modifier = Modifier.testTag("tab_reminders")
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    text = { Text("Daily Habits & Streaks") },
                    icon = { Icon(Icons.Default.Whatshot, contentDescription = null) },
                    modifier = Modifier.testTag("tab_habits")
                )
            }

            if (uiState.selectedTab == 0) {
                // Natural Language Quick Add Card
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        OutlinedTextField(
                            value = naturalLanguageInput,
                            onValueChange = { naturalLanguageInput = it },
                            placeholder = { Text("e.g. Drink water every 30 mins", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("quick_reminder_input")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (naturalLanguageInput.isNotBlank()) {
                                    val text = naturalLanguageInput.trim()
                                    // Parse interval minutes if found
                                    val interval = Regex("(\\d+)\\s*(m|min|minute)", RegexOption.IGNORE_CASE)
                                        .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 30
                                    viewModel.saveReminder(
                                        context,
                                        ReminderEntity(
                                            title = text.substringBefore("every").trim().ifEmpty { text },
                                            intervalMinutes = interval,
                                            frequency = ReminderFrequency.INTERVAL_MINUTES
                                        )
                                    )
                                    naturalLanguageInput = ""
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            enabled = naturalLanguageInput.isNotBlank(),
                            modifier = Modifier.testTag("quick_reminder_btn")
                        ) {
                            Text("Add")
                        }
                    }
                }

                // Ready-made Templates Carousel
                Text(
                    text = "Ready-Made Templates",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(PREMADE_TEMPLATES) { template ->
                        Card(
                            onClick = { viewModel.applyTemplate(context, template) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                            modifier = Modifier.width(200.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = template.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Every ${template.intervalMinutes}m (${template.category})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "+ Activate",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Active Reminders List
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.reminders.isEmpty()) {
                        item {
                            EmptyStateCard(
                                title = "No Active Reminders",
                                description = "Tap a template above or '+' to create hydration, anti-scroll, or stretch pop-ups.",
                                icon = Icons.Outlined.NotificationsNone
                            )
                        }
                    } else {
                        items(uiState.reminders, key = { it.id }) { reminder ->
                            ReminderItemCard(
                                reminder = reminder,
                                onToggle = { viewModel.toggleReminder(context, reminder) },
                                onDelete = { viewModel.deleteReminder(context, reminder) }
                            )
                        }
                    }
                }
            } else {
                // Habits and Streaks Tab
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.habits.isEmpty()) {
                        item {
                            EmptyStateCard(
                                title = "No Habits Configured",
                                description = "Build healthy daily routines like drinking water, daily study, reading, or screen breaks.",
                                icon = Icons.Outlined.Whatshot,
                                actionText = "Create Habit",
                                onActionClick = { showCreateHabitDialog = true }
                            )
                        }
                    } else {
                        items(uiState.habits, key = { it.id }) { habit ->
                            val isCompletedToday = uiState.todayHabitLogs.contains(habit.id)
                            HabitItemCard(
                                habit = habit,
                                isCompletedToday = isCompletedToday,
                                onToggle = { viewModel.toggleHabitCompletion(context, habit) },
                                onDelete = { viewModel.deleteHabit(context, habit) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Reminder Dialog
    if (showCreateReminderDialog) {
        CreateReminderDialog(
            onDismiss = { showCreateReminderDialog = false },
            onSave = { reminder ->
                viewModel.saveReminder(context, reminder)
                showCreateReminderDialog = false
            }
        )
    }

    // Create Habit Dialog
    if (showCreateHabitDialog) {
        CreateHabitDialog(
            onDismiss = { showCreateHabitDialog = false },
            onSave = { habit ->
                viewModel.saveHabit(context, habit)
                showCreateHabitDialog = false
            }
        )
    }
}

@Composable
fun ReminderItemCard(
    reminder: ReminderEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("reminder_card_${reminder.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reminder.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Every ${reminder.intervalMinutes}m • ${reminder.startHour}:00 - ${reminder.endHour}:00",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Category: ${reminder.category} • Snooze: ${reminder.snoozeMinutes}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Switch(
                checked = reminder.isEnabled,
                onCheckedChange = { onToggle() }
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun HabitItemCard(
    habit: HabitEntity,
    isCompletedToday: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("habit_card_${habit.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(44.dp).testTag("habit_check_${habit.id}")
            ) {
                Icon(
                    imageVector = if (isCompletedToday) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isCompletedToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${habit.category} • Target ${habit.targetDaysPerWeek} days/week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Streak Badge
            Surface(
                color = if (habit.currentStreak > 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Whatshot,
                        contentDescription = null,
                        tint = if (habit.currentStreak > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${habit.currentStreak}d",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun CreateReminderDialog(
    onDismiss: () -> Unit,
    onSave: (ReminderEntity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var intervalMinutes by remember { mutableStateOf(30) }
    var category by remember { mutableStateOf("Hydration") }
    var snoozeMinutes by remember { mutableStateOf(10) }

    val categories = listOf("Hydration", "Focus", "Stretch", "Anti-Scroll", "Eye-Rest", "Medicine", "Custom")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Smart Pop-Up Reminder", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Reminder Title *") },
                    placeholder = { Text("e.g. Drink Water") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("reminder_title_input")
                )

                Text("Repeat Interval (Minutes):", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60, 90).forEach { mins ->
                        FilterChip(
                            selected = intervalMinutes == mins,
                            onClick = { intervalMinutes = mins },
                            label = { Text("${mins}m") },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Text("Category:", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(
                            ReminderEntity(
                                title = title.trim(),
                                category = category,
                                intervalMinutes = intervalMinutes,
                                snoozeMinutes = snoozeMinutes,
                                isEnabled = true
                            )
                        )
                    }
                },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Create Reminder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CreateHabitDialog(
    onDismiss: () -> Unit,
    onSave: (HabitEntity) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Health") }
    var targetDays by remember { mutableStateOf(7) }

    val categories = listOf("Health", "Study", "Mind", "Fitness", "Digital")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Daily Habit", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Habit Name *") },
                    placeholder = { Text("e.g. Read for 20 minutes") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("habit_name_input")
                )

                Text("Category:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Text("Target Days Per Week: $targetDays", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = targetDays.toFloat(),
                    onValueChange = { targetDays = it.toInt() },
                    valueRange = 1f..7f,
                    steps = 5
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            HabitEntity(
                                name = name.trim(),
                                category = category,
                                targetDaysPerWeek = targetDays
                            )
                        )
                    }
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Create Habit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
