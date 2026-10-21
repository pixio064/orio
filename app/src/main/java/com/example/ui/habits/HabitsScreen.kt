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
import com.example.util.DateKeys
import com.example.util.HabitStreakCalculator
import com.example.util.ScheduleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HabitsUiState(
    val reminders: List<ReminderEntity> = emptyList(),
    val habits: List<HabitEntity> = emptyList(),
    val todayHabitLogs: Set<Long> = emptySet(),
    val selectedTab: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

data class ReminderTemplate(
    val title: String,
    val description: String,
    val category: String,
    val intervalMinutes: Int,
    val startHour: Int = 9,
    val endHour: Int = 21
)

val PREMADE_TEMPLATES = listOf(
    ReminderTemplate("Hydration Routine", "Drink a glass of water to stay energized", "Hydration", 30),
    ReminderTemplate("20-20-20 Eye Rest", "Look at something 20 feet away for 20 seconds", "Eye-Rest", 20),
    ReminderTemplate("Anti-Scrolling Pause", "Check in: Are you using your phone intentionally?", "Anti-Scroll", 25),
    ReminderTemplate("Desk Stretch & Posture", "Straighten back, roll shoulders, and stretch", "Stretch", 60),
    ReminderTemplate("Study Focus Sprint", "Stay immersed in current study block", "Focus", 50)
)

class HabitsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HabitsUiState())
    val uiState: StateFlow<HabitsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun loadData(context: Context) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val app = context.applicationContext as FocusTrackApp
                val remindersList = app.database.reminderDao().getAllReminders().first()
                val habitsList = app.database.habitDao().getAllHabits().first()

                val today = DateKeys.today()
                val logs = app.database.habitDao().getHabitLogsForDate(today).first()
                val completedIds = logs.map { it.habitId }.toSet()

                // Synchronize habit streaks from logs
                for (habit in habitsList) {
                    val habitLogs = app.database.habitDao().getLogsForHabitSync(habit.id)
                    val dates = habitLogs.map { it.dateString }.toSet()
                    val streak = HabitStreakCalculator.computeStreak(dates, habit.targetDaysPerWeek, today)
                    if (streak != habit.currentStreak) {
                        app.database.habitDao().updateHabit(
                            habit.copy(currentStreak = streak, bestStreak = maxOf(habit.bestStreak, streak))
                        )
                    }
                }

                val refreshedHabits = app.database.habitDao().getAllHabits().first()

                _uiState.update {
                    it.copy(
                        reminders = remindersList,
                        habits = refreshedHabits,
                        todayHabitLogs = completedIds,
                        isLoading = false
                    )
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun setSelectedTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun toggleReminder(context: Context, reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                val newEnabled = !reminder.isEnabled
                val nextTrigger = if (newEnabled) AlarmScheduler.calculateNextReminderTrigger(reminder) else 0L
                val updated = reminder.copy(isEnabled = newEnabled, nextTriggerTimestamp = nextTrigger)
                app.database.reminderDao().updateReminder(updated)

                if (newEnabled) {
                    AlarmScheduler.scheduleReminder(context, updated)
                } else {
                    AlarmScheduler.cancelReminder(context, updated.id)
                }
                loadData(context)
            } catch (_: Throwable) {}
        }
    }

    fun saveReminder(context: Context, reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                val nextTrigger = if (reminder.isEnabled) AlarmScheduler.calculateNextReminderTrigger(reminder) else 0L
                val entityToSave = reminder.copy(nextTriggerTimestamp = nextTrigger)

                val id = app.database.reminderDao().insertReminder(entityToSave)
                val finalReminder = if (entityToSave.id == 0L) entityToSave.copy(id = id) else entityToSave

                if (finalReminder.isEnabled) {
                    AlarmScheduler.scheduleReminder(context, finalReminder)
                } else {
                    AlarmScheduler.cancelReminder(context, finalReminder.id)
                }
                loadData(context)
            } catch (_: Throwable) {}
        }
    }

    fun deleteReminder(context: Context, reminder: ReminderEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                app.database.reminderDao().deleteReminder(reminder)
                AlarmScheduler.cancelReminder(context, reminder.id)
                loadData(context)
            } catch (_: Throwable) {}
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
            try {
                val app = context.applicationContext as FocusTrackApp
                val today = DateKeys.today()

                // Debounce using actual DB state
                val existingLog = app.database.habitDao().getHabitLog(habit.id, today)
                if (existingLog != null) {
                    app.database.habitDao().deleteHabitLog(habit.id, today)
                } else {
                    app.database.habitDao().insertHabitLog(HabitLogEntity(habitId = habit.id, dateString = today))
                }

                val allLogs = app.database.habitDao().getLogsForHabitSync(habit.id)
                val dates = allLogs.map { it.dateString }.toSet()
                val streak = HabitStreakCalculator.computeStreak(dates, habit.targetDaysPerWeek, today)
                val best = maxOf(habit.bestStreak, streak)

                app.database.habitDao().updateHabit(
                    habit.copy(
                        currentStreak = streak,
                        bestStreak = best,
                        lastCompletedDate = if (existingLog == null) today else habit.lastCompletedDate
                    )
                )
                loadData(context)
            } catch (_: Throwable) {}
        }
    }

    fun toggleHabitPause(context: Context, habit: HabitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                val updated = habit.copy(isPaused = !habit.isPaused)
                app.database.habitDao().updateHabit(updated)
                if (updated.isPaused) {
                    AlarmScheduler.cancelHabit(context, updated.id)
                } else {
                    AlarmScheduler.scheduleHabit(context, updated)
                }
                loadData(context)
            } catch (_: Throwable) {}
        }
    }

    fun saveHabit(context: Context, habit: HabitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                val id = app.database.habitDao().insertHabit(habit)
                val saved = if (habit.id == 0L) habit.copy(id = id) else habit
                if (!saved.isPaused) {
                    AlarmScheduler.scheduleHabit(context, saved)
                } else {
                    AlarmScheduler.cancelHabit(context, saved.id)
                }
                loadData(context)
            } catch (_: Throwable) {}
        }
    }

    fun deleteHabit(context: Context, habit: HabitEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                app.database.habitDao().deleteHabit(habit)
                AlarmScheduler.cancelHabit(context, habit.id)
                loadData(context)
            } catch (_: Throwable) {}
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

    var showReminderDialog by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<ReminderEntity?>(null) }

    var showHabitDialog by remember { mutableStateOf(false) }
    var editingHabit by remember { mutableStateOf<HabitEntity?>(null) }

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
                        Icon(Icons.Default.NotificationsActive, contentDescription = "Test Notification", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Alert", style = MaterialTheme.typography.labelSmall)
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
                    if (uiState.selectedTab == 0) {
                        editingReminder = null
                        showReminderDialog = true
                    } else {
                        editingHabit = null
                        showHabitDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("fab_add_habit_or_reminder")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Item")
            }
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
                    text = { Text("Pop-up Reminders") },
                    icon = { Icon(Icons.Default.Alarm, contentDescription = "Reminders Tab") },
                    modifier = Modifier.testTag("tab_reminders")
                )
                Tab(
                    selected = uiState.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    text = { Text("Daily Habits & Streaks") },
                    icon = { Icon(Icons.Default.Whatshot, contentDescription = "Habits Tab") },
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
                            placeholder = { Text("e.g. Drink water every 2 hours", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("quick_reminder_input")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (naturalLanguageInput.isNotBlank()) {
                                    val parsed = ScheduleParser.parse(naturalLanguageInput)
                                    viewModel.saveReminder(
                                        context,
                                        ReminderEntity(
                                            title = parsed.title,
                                            intervalMinutes = parsed.intervalMinutes,
                                            frequency = parsed.frequency,
                                            isEnabled = true
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
                    text = "Quick Routines & Science Templates",
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
                                onEdit = {
                                    editingReminder = reminder
                                    showReminderDialog = true
                                },
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
                                onActionClick = {
                                    editingHabit = null
                                    showHabitDialog = true
                                }
                            )
                        }
                    } else {
                        items(uiState.habits, key = { it.id }) { habit ->
                            val isCompletedToday = uiState.todayHabitLogs.contains(habit.id)
                            HabitItemCard(
                                habit = habit,
                                isCompletedToday = isCompletedToday,
                                onToggle = { viewModel.toggleHabitCompletion(context, habit) },
                                onPauseToggle = { viewModel.toggleHabitPause(context, habit) },
                                onEdit = {
                                    editingHabit = habit
                                    showHabitDialog = true
                                },
                                onDelete = { viewModel.deleteHabit(context, habit) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showReminderDialog) {
        ReminderEditorDialog(
            initialReminder = editingReminder,
            onDismiss = {
                showReminderDialog = false
                editingReminder = null
            },
            onSave = { reminder ->
                viewModel.saveReminder(context, reminder)
                showReminderDialog = false
                editingReminder = null
            }
        )
    }

    if (showHabitDialog) {
        HabitEditorDialog(
            initialHabit = editingHabit,
            onDismiss = {
                showHabitDialog = false
                editingHabit = null
            },
            onSave = { habit ->
                viewModel.saveHabit(context, habit)
                showHabitDialog = false
                editingHabit = null
            }
        )
    }
}

@Composable
fun ReminderItemCard(
    reminder: ReminderEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
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
                    .background(
                        if (reminder.isEnabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = "Reminder Icon",
                    tint = if (reminder.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reminder.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                val timingText = when (reminder.frequency) {
                    ReminderFrequency.INTERVAL_MINUTES ->
                        "Every ${reminder.intervalMinutes}m • ${String.format("%02d:%02d", reminder.startHour, reminder.startMinute)} - ${String.format("%02d:%02d", reminder.endHour, reminder.endMinute)}"
                    ReminderFrequency.DAILY ->
                        "Daily at ${String.format("%02d:%02d", reminder.specificHour, reminder.specificMinute)}"
                    ReminderFrequency.WEEKDAYS ->
                        "Weekdays at ${String.format("%02d:%02d", reminder.specificHour, reminder.specificMinute)}"
                    ReminderFrequency.WEEKLY ->
                        "Weekly at ${String.format("%02d:%02d", reminder.specificHour, reminder.specificMinute)}"
                    ReminderFrequency.ONE_TIME ->
                        "One-time at ${String.format("%02d:%02d", reminder.specificHour, reminder.specificMinute)}"
                }

                Text(
                    text = timingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Category: ${reminder.category} • Snooze: ${reminder.snoozeMinutes}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit Reminder", tint = MaterialTheme.colorScheme.outline)
            }
            Switch(
                checked = reminder.isEnabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("toggle_reminder_${reminder.id}")
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete Reminder", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun HabitItemCard(
    habit: HabitEntity,
    isCompletedToday: Boolean,
    onToggle: () -> Unit,
    onPauseToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (habit.isPaused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().testTag("habit_card_${habit.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            IconButton(
                onClick = onToggle,
                enabled = !habit.isPaused,
                modifier = Modifier.size(48.dp).testTag("habit_check_${habit.id}")
            ) {
                Icon(
                    imageVector = if (isCompletedToday) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (isCompletedToday) "Mark Unfinished" else "Mark Finished",
                    tint = if (habit.isPaused) MaterialTheme.colorScheme.outline
                    else if (isCompletedToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = habit.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (habit.isPaused) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(Paused)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${habit.category} • Target ${habit.targetDaysPerWeek}d/wk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (habit.reminderHour in 0..23) {
                        Text(
                            text = "⏰ ${String.format("%02d:%02d", habit.reminderHour, habit.reminderMinute)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Whatshot,
                        contentDescription = "Streak Flame",
                        tint = if (habit.currentStreak > 0) Color(0xFFFF6F00) else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${habit.currentStreak} day streak (Best: ${habit.bestStreak})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (habit.currentStreak > 0) Color(0xFFFF6F00) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onPauseToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (habit.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (habit.isPaused) "Resume Habit" else "Pause Habit",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit Habit", tint = MaterialTheme.colorScheme.outline)
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete Habit", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditorDialog(
    initialReminder: ReminderEntity?,
    onDismiss: () -> Unit,
    onSave: (ReminderEntity) -> Unit
) {
    var title by remember { mutableStateOf(initialReminder?.title ?: "") }
    var category by remember { mutableStateOf(initialReminder?.category ?: "Health") }
    var frequency by remember { mutableStateOf(initialReminder?.frequency ?: ReminderFrequency.INTERVAL_MINUTES) }
    var intervalMinutes by remember { mutableStateOf(initialReminder?.intervalMinutes ?: 30) }
    var startHour by remember { mutableStateOf(initialReminder?.startHour ?: 9) }
    var startMinute by remember { mutableStateOf(initialReminder?.startMinute ?: 0) }
    var endHour by remember { mutableStateOf(initialReminder?.endHour ?: 21) }
    var endMinute by remember { mutableStateOf(initialReminder?.endMinute ?: 0) }
    var specificHour by remember { mutableStateOf(initialReminder?.specificHour ?: 9) }
    var specificMinute by remember { mutableStateOf(initialReminder?.specificMinute ?: 0) }
    var snoozeMinutes by remember { mutableStateOf(initialReminder?.snoozeMinutes ?: 10) }

    val categories = listOf("Health", "Eye-Rest", "Posture", "Hydration", "Focus", "Anti-Scroll")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialReminder == null) "Create Pop-Up Alert" else "Edit Pop-Up Alert", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Reminder Title *") },
                    placeholder = { Text("e.g. Drink Water") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("reminder_title_input")
                )

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

                Text("Frequency:", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ReminderFrequency.entries) { freq ->
                        FilterChip(
                            selected = frequency == freq,
                            onClick = { frequency = freq },
                            label = { Text(freq.name.replace("_", " ")) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                if (frequency == ReminderFrequency.INTERVAL_MINUTES) {
                    Text("Interval: $intervalMinutes minutes (Min 5m)", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = intervalMinutes.toFloat(),
                        onValueChange = { intervalMinutes = it.toInt().coerceAtLeast(5) },
                        valueRange = 5f..120f,
                        steps = 22
                    )

                    Text("Active Window (Overnight supported):", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = String.format("%02d:%02d", startHour, startMinute),
                            onValueChange = {
                                val parts = it.split(":")
                                if (parts.size == 2) {
                                    startHour = (parts[0].toIntOrNull() ?: startHour).coerceIn(0, 23)
                                    startMinute = (parts[1].toIntOrNull() ?: startMinute).coerceIn(0, 59)
                                }
                            },
                            label = { Text("Start") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = String.format("%02d:%02d", endHour, endMinute),
                            onValueChange = {
                                val parts = it.split(":")
                                if (parts.size == 2) {
                                    endHour = (parts[0].toIntOrNull() ?: endHour).coerceIn(0, 23)
                                    endMinute = (parts[1].toIntOrNull() ?: endMinute).coerceIn(0, 59)
                                }
                            },
                            label = { Text("End") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = String.format("%02d:%02d", specificHour, specificMinute),
                        onValueChange = {
                            val parts = it.split(":")
                            if (parts.size == 2) {
                                specificHour = (parts[0].toIntOrNull() ?: specificHour).coerceIn(0, 23)
                                specificMinute = (parts[1].toIntOrNull() ?: specificMinute).coerceIn(0, 59)
                            }
                        },
                        label = { Text("Trigger Time (HH:MM)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Snooze Duration: $snoozeMinutes min", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = snoozeMinutes.toFloat(),
                    onValueChange = { snoozeMinutes = it.toInt() },
                    valueRange = 5f..30f,
                    steps = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val base = initialReminder ?: ReminderEntity(title = title.trim())
                        onSave(
                            base.copy(
                                title = title.trim(),
                                category = category,
                                frequency = frequency,
                                intervalMinutes = intervalMinutes.coerceAtLeast(5),
                                startHour = startHour,
                                startMinute = startMinute,
                                endHour = endHour,
                                endMinute = endMinute,
                                specificHour = specificHour,
                                specificMinute = specificMinute,
                                snoozeMinutes = snoozeMinutes,
                                isEnabled = true
                            )
                        )
                    }
                },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (initialReminder == null) "Create Reminder" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun HabitEditorDialog(
    initialHabit: HabitEntity?,
    onDismiss: () -> Unit,
    onSave: (HabitEntity) -> Unit
) {
    var name by remember { mutableStateOf(initialHabit?.name ?: "") }
    var description by remember { mutableStateOf(initialHabit?.description ?: "") }
    var category by remember { mutableStateOf(initialHabit?.category ?: "Health") }
    var targetDays by remember { mutableStateOf(initialHabit?.targetDaysPerWeek ?: 7) }
    var reminderHour by remember { mutableStateOf(initialHabit?.reminderHour ?: 9) }
    var reminderMinute by remember { mutableStateOf(initialHabit?.reminderMinute ?: 0) }

    val categories = listOf("Health", "Study", "Mind", "Fitness", "Digital")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialHabit == null) "Create Daily Habit" else "Edit Habit", fontWeight = FontWeight.Bold) },
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

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / Note") },
                    placeholder = { Text("e.g. Chapter 1-3") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
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

                OutlinedTextField(
                    value = String.format("%02d:%02d", reminderHour, reminderMinute),
                    onValueChange = {
                        val parts = it.split(":")
                        if (parts.size == 2) {
                            reminderHour = (parts[0].toIntOrNull() ?: reminderHour).coerceIn(0, 23)
                            reminderMinute = (parts[1].toIntOrNull() ?: reminderMinute).coerceIn(0, 59)
                        }
                    },
                    label = { Text("Daily Reminder Time (HH:MM)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val base = initialHabit ?: HabitEntity(name = name.trim())
                        onSave(
                            base.copy(
                                name = name.trim(),
                                description = description.trim(),
                                category = category,
                                targetDaysPerWeek = targetDays,
                                reminderHour = reminderHour,
                                reminderMinute = reminderMinute
                            )
                        )
                    }
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (initialHabit == null) "Create Habit" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
