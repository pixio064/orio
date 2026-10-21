package com.example.ui.planner

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.local.entity.TaskEntity
import com.example.data.local.entity.TaskOutcome
import com.example.data.local.entity.TaskPriority
import com.example.data.local.entity.TaskType
import com.example.scheduler.AlarmScheduler
import com.example.ui.components.EmptyStateCard
import com.example.util.DateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class PlannerUiState(
    val selectedDate: String = "",
    val isTomorrow: Boolean = false,
    val tasks: List<TaskEntity> = emptyList(),
    val conflictTaskIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class PlannerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun checkAndRefreshDate(context: Context) {
        val today = DateKeys.today()
        val current = _uiState.value.selectedDate
        if (current.isBlank() || (current != today && !_uiState.value.isTomorrow)) {
            _uiState.update { it.copy(selectedDate = today, isTomorrow = false) }
            loadTasks(context, today)
        }
    }

    fun setDate(date: String, isTomorrow: Boolean, context: Context) {
        _uiState.update { it.copy(selectedDate = date, isTomorrow = isTomorrow) }
        loadTasks(context, date)
    }

    fun loadTasks(context: Context, date: String = _uiState.value.selectedDate) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val targetDate = if (date.isNotBlank()) date else DateKeys.today()
                val app = context.applicationContext as FocusTrackApp
                val taskList = app.database.taskDao().getTasksForDateSync(targetDate)

                // Detect overlaps between scheduled pending tasks
                val conflicts = mutableSetOf<Long>()
                val scheduled = taskList.filter { it.taskType == TaskType.SCHEDULED && it.outcome == TaskOutcome.PENDING }
                for (i in scheduled.indices) {
                    for (j in i + 1 until scheduled.size) {
                        val a = scheduled[i]
                        val b = scheduled[j]
                        val aStart = a.startTimeHour * 60 + a.startTimeMinute
                        var aEnd = a.endTimeHour * 60 + a.endTimeMinute
                        if (aEnd <= aStart) aEnd += 24 * 60 // Overnight handling

                        val bStart = b.startTimeHour * 60 + b.startTimeMinute
                        var bEnd = b.endTimeHour * 60 + b.endTimeMinute
                        if (bEnd <= bStart) bEnd += 24 * 60 // Overnight handling

                        if (aStart < bEnd && bStart < aEnd) {
                            conflicts.add(a.id)
                            conflicts.add(b.id)
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        selectedDate = targetDate,
                        tasks = taskList,
                        conflictTaskIds = conflicts,
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

                // Calculate if trigger time is in future or if reminder is enabled
                val cal = Calendar.getInstance().apply {
                    val parts = savedTask.dateString.split("-")
                    if (parts.size == 3) {
                        set(Calendar.YEAR, parts[0].toInt())
                        set(Calendar.MONTH, parts[1].toInt() - 1)
                        set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                    }
                    set(Calendar.HOUR_OF_DAY, savedTask.startTimeHour)
                    set(Calendar.MINUTE, savedTask.startTimeMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, -savedTask.reminderMinutesBefore)
                }

                if (savedTask.reminderMinutesBefore >= 0 &&
                    savedTask.outcome == TaskOutcome.PENDING &&
                    cal.timeInMillis > System.currentTimeMillis()
                ) {
                    AlarmScheduler.scheduleTask(context, savedTask)
                } else {
                    AlarmScheduler.cancelTask(context, savedTask.id)
                }
                loadTasks(context)
            } catch (_: Throwable) {}
        }
    }

    fun updateTaskOutcome(context: Context, taskId: Long, outcome: TaskOutcome) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                app.database.taskDao().updateTaskOutcome(taskId, outcome, System.currentTimeMillis())

                // Cancel the alarm if completed, skipped, or missed
                if (outcome != TaskOutcome.PENDING) {
                    AlarmScheduler.cancelTask(context, taskId)
                }
                loadTasks(context)
            } catch (_: Throwable) {}
        }
    }

    fun deleteTask(context: Context, task: TaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                app.database.taskDao().deleteTask(task)
                AlarmScheduler.cancelTask(context, task.id)
                loadTasks(context)
            } catch (_: Throwable) {}
        }
    }

    fun carryOverUnfinishedToTomorrow(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = context.applicationContext as FocusTrackApp
                val today = DateKeys.today()
                val tomorrow = DateKeys.tomorrow()

                val todayTasks = app.database.taskDao().getTasksForDateSync(today)
                val unfinished = todayTasks.filter { it.outcome == TaskOutcome.PENDING || it.outcome == TaskOutcome.MISSED }

                for (task in unfinished) {
                    // Mark original as CARRIED so duplicates cannot be created if pressed twice
                    app.database.taskDao().updateTaskOutcome(task.id, TaskOutcome.CARRIED, System.currentTimeMillis())
                    AlarmScheduler.cancelTask(context, task.id)

                    val carried = task.copy(
                        id = 0,
                        dateString = tomorrow,
                        outcome = TaskOutcome.PENDING,
                        outcomeTimestamp = 0L
                    )
                    val id = app.database.taskDao().insertTask(carried)
                    val savedNew = carried.copy(id = id)
                    if (savedNew.reminderMinutesBefore >= 0) {
                        AlarmScheduler.scheduleTask(context, savedNew)
                    }
                }

                setDate(tomorrow, isTomorrow = true, context = context)
            } catch (_: Throwable) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    viewModel: PlannerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var showEditDialog by remember { mutableStateOf(false) }
    var selectedTaskForEdit by remember { mutableStateOf<TaskEntity?>(null) }

    val todayStr = DateKeys.today()
    val tomorrowStr = DateKeys.tomorrow()

    // Automatically refresh date after midnight
    LaunchedEffect(Unit) {
        viewModel.checkAndRefreshDate(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Daily Planner & Intentions",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uiState.isTomorrow) "Planning for Tomorrow ($tomorrowStr)" else "Today's Agenda ($todayStr)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (!uiState.isTomorrow) {
                        FilledTonalButton(
                            onClick = { viewModel.carryOverUnfinishedToTomorrow(context) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 8.dp).testTag("carry_over_btn")
                        ) {
                            Icon(Icons.Default.FastForward, contentDescription = "Carry Over Icon", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Carry Over", style = MaterialTheme.typography.labelSmall)
                        }
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
                    selectedTaskForEdit = null
                    showEditDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("fab_add_task")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Task")
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Day selector chips (Today / Tomorrow)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = !uiState.isTomorrow,
                    onClick = { viewModel.setDate(todayStr, isTomorrow = false, context = context) },
                    label = { Text("Today ($todayStr)") },
                    leadingIcon = {
                        if (!uiState.isTomorrow) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(16.dp))
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("chip_today")
                )
                FilterChip(
                    selected = uiState.isTomorrow,
                    onClick = { viewModel.setDate(tomorrowStr, isTomorrow = true, context = context) },
                    label = { Text("Tomorrow ($tomorrowStr)") },
                    leadingIcon = {
                        if (uiState.isTomorrow) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(16.dp))
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("chip_tomorrow")
                )
            }

            // Conflict Warning Banner if overlaps exist
            if (uiState.conflictTaskIds.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Conflict Warning",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Schedule Conflict: ${uiState.conflictTaskIds.size} tasks have overlapping time slots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Task List
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (uiState.tasks.isEmpty()) {
                    item {
                        EmptyStateCard(
                            title = if (uiState.isTomorrow) "No Tasks Planned for Tomorrow" else "No Tasks Planned for Today",
                            description = "Tap '+' below to plan intentional time blocks and build your focus.",
                            icon = Icons.Outlined.Checklist,
                            actionText = "Add First Task",
                            onActionClick = {
                                selectedTaskForEdit = null
                                showEditDialog = true
                            }
                        )
                    }
                } else {
                    items(uiState.tasks, key = { it.id }) { task ->
                        val hasConflict = uiState.conflictTaskIds.contains(task.id)
                        TaskCardItem(
                            task = task,
                            hasConflict = hasConflict,
                            onOutcomeChange = { outcome ->
                                viewModel.updateTaskOutcome(context, task.id, outcome)
                            },
                            onEdit = {
                                selectedTaskForEdit = task
                                showEditDialog = true
                            },
                            onDelete = {
                                viewModel.deleteTask(context, task)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        TaskEditDialog(
            existingTask = selectedTaskForEdit,
            defaultDate = if (uiState.isTomorrow) tomorrowStr else todayStr,
            existingTasksOnDate = uiState.tasks.filter { it.id != (selectedTaskForEdit?.id ?: 0L) },
            onDismiss = {
                showEditDialog = false
                selectedTaskForEdit = null
            },
            onSave = { task ->
                viewModel.saveTask(context, task)
                showEditDialog = false
                selectedTaskForEdit = null
            }
        )
    }
}

@Composable
fun TaskCardItem(
    task: TaskEntity,
    hasConflict: Boolean,
    onOutcomeChange: (TaskOutcome) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isDone = task.outcome == TaskOutcome.COMPLETED
    val isSkipped = task.outcome == TaskOutcome.SKIPPED
    val isMissed = task.outcome == TaskOutcome.MISSED
    val isCarried = task.outcome == TaskOutcome.CARRIED

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                hasConflict -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                isDone -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                isCarried -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val next = if (isDone) TaskOutcome.PENDING else TaskOutcome.COMPLETED
                        onOutcomeChange(next)
                    },
                    modifier = Modifier.size(48.dp).testTag("task_check_${task.id}")
                ) {
                    Icon(
                        imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = if (isDone) "Mark Pending" else "Mark Complete",
                        tint = if (isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (isDone || isSkipped || isCarried) TextDecoration.LineThrough else TextDecoration.None
                    )

                    if (task.description.isNotBlank()) {
                        Text(
                            text = task.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (task.taskType == TaskType.SCHEDULED) {
                            val timeText = "${String.format("%02d:%02d", task.startTimeHour, task.startTimeMinute)} - ${String.format("%02d:%02d", task.endTimeHour, task.endTimeMinute)}"
                            Text(
                                text = "⏰ $timeText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "Flexible Item",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Text(
                            text = "• ${task.priority.name} Priority",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (task.priority) {
                                TaskPriority.URGENT -> MaterialTheme.colorScheme.error
                                TaskPriority.HIGH -> Color(0xFFFF6F00)
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )

                        if (isCarried) {
                            Text(
                                text = "• Carried Over",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit Task", tint = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete Task", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditDialog(
    existingTask: TaskEntity?,
    defaultDate: String,
    existingTasksOnDate: List<TaskEntity>,
    onDismiss: () -> Unit,
    onSave: (TaskEntity) -> Unit
) {
    var title by remember { mutableStateOf(existingTask?.title ?: "") }
    var description by remember { mutableStateOf(existingTask?.description ?: "") }
    var taskType by remember { mutableStateOf(existingTask?.taskType ?: TaskType.SCHEDULED) }
    var startHour by remember { mutableStateOf(existingTask?.startTimeHour ?: 9) }
    var startMin by remember { mutableStateOf(existingTask?.startTimeMinute ?: 0) }
    var endHour by remember { mutableStateOf(existingTask?.endTimeHour ?: 10) }
    var endMin by remember { mutableStateOf(existingTask?.endTimeMinute ?: 0) }
    var category by remember { mutableStateOf(existingTask?.category ?: "Work") }
    var priority by remember { mutableStateOf(existingTask?.priority ?: TaskPriority.MEDIUM) }
    var reminderBefore by remember { mutableStateOf(existingTask?.reminderMinutesBefore ?: 10) }

    val categories = listOf("Work", "Study", "Health", "Personal", "Focus")
    val reminderOptions = listOf(
        -1 to "None",
        0 to "At start",
        5 to "5m before",
        10 to "10m before",
        30 to "30m before"
    )

    val startTotalMins = startHour * 60 + startMin
    val endTotalMins = endHour * 60 + endMin
    val isOvernight = taskType == TaskType.SCHEDULED && (endTotalMins <= startTotalMins)

    // Check overlaps with other tasks on this date
    val overlaps = remember(taskType, startHour, startMin, endHour, endMin) {
        if (taskType != TaskType.SCHEDULED) emptyList()
        else {
            val aStart = startHour * 60 + startMin
            var aEnd = endHour * 60 + endMin
            if (aEnd <= aStart) aEnd += 24 * 60

            existingTasksOnDate.filter { other ->
                if (other.taskType != TaskType.SCHEDULED || other.outcome != TaskOutcome.PENDING) false
                else {
                    val bStart = other.startTimeHour * 60 + other.startTimeMinute
                    var bEnd = other.endTimeHour * 60 + other.endTimeMinute
                    if (bEnd <= bStart) bEnd += 24 * 60
                    aStart < bEnd && bStart < aEnd
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existingTask != null) "Edit Task" else "Create New Task",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title *") },
                    placeholder = { Text("e.g. Study React & Compose") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("task_title_input")
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / Notes") },
                    placeholder = { Text("e.g. Chapter 4 exercises") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Task Type Selector
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = taskType == TaskType.SCHEDULED,
                        onClick = { taskType = TaskType.SCHEDULED },
                        label = { Text("Scheduled Time") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = taskType == TaskType.FLEXIBLE,
                        onClick = { taskType = TaskType.FLEXIBLE },
                        label = { Text("Flexible Checklist") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (taskType == TaskType.SCHEDULED) {
                    Text("Time Pickers (Minute Precision):", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = String.format("%02d:%02d", startHour, startMin),
                            onValueChange = {
                                val parts = it.split(":")
                                if (parts.size == 2) {
                                    startHour = (parts[0].toIntOrNull() ?: startHour).coerceIn(0, 23)
                                    startMin = (parts[1].toIntOrNull() ?: startMin).coerceIn(0, 59)
                                }
                            },
                            label = { Text("Start (HH:MM)") },
                            modifier = Modifier.weight(1f).testTag("start_time_input")
                        )
                        OutlinedTextField(
                            value = String.format("%02d:%02d", endHour, endMin),
                            onValueChange = {
                                val parts = it.split(":")
                                if (parts.size == 2) {
                                    endHour = (parts[0].toIntOrNull() ?: endHour).coerceIn(0, 23)
                                    endMin = (parts[1].toIntOrNull() ?: endMin).coerceIn(0, 59)
                                }
                            },
                            label = { Text("End (HH:MM)") },
                            modifier = Modifier.weight(1f).testTag("end_time_input")
                        )
                    }

                    if (isOvernight) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🌙 Overnight schedule (ends next morning)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (overlaps.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ Overlaps with '${overlaps.first().title}' (${String.format("%02d:%02d", overlaps.first().startTimeHour, overlaps.first().startTimeMinute)} - ${String.format("%02d:%02d", overlaps.first().endTimeHour, overlaps.first().endTimeMinute)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                // Priority Selector
                Text("Priority:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskPriority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.name, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                // Reminder Before Selector
                Text("Notification Reminder Before Start:", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(reminderOptions) { opt ->
                        val mins = opt.first
                        val label = opt.second
                        FilterChip(
                            selected = reminderBefore == mins,
                            onClick = { reminderBefore = mins },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                // Category selection
                Text("Category:", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
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
                        val task = (existingTask ?: TaskEntity(
                            title = title,
                            dateString = defaultDate
                        )).copy(
                            title = title.trim(),
                            description = description.trim(),
                            taskType = taskType,
                            startTimeHour = startHour,
                            startTimeMinute = startMin,
                            endTimeHour = endHour,
                            endTimeMinute = endMin,
                            category = category,
                            priority = priority,
                            reminderMinutesBefore = reminderBefore
                        )
                        onSave(task)
                    }
                },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("save_task_submit")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
