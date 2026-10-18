package com.example.ui.planner

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class PlannerUiState(
    val selectedDate: String = "", // "YYYY-MM-DD"
    val isTomorrow: Boolean = false,
    val tasks: List<TaskEntity> = emptyList(),
    val conflictTaskIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true
)

class PlannerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun initDate() {
        val today = dateFormat.format(Date())
        _uiState.value = _uiState.value.copy(selectedDate = today, isTomorrow = false)
    }

    fun setDate(date: String, isTomorrow: Boolean, context: Context) {
        _uiState.value = _uiState.value.copy(selectedDate = date, isTomorrow = isTomorrow)
        loadTasks(context, date)
    }

    fun loadTasks(context: Context, date: String = _uiState.value.selectedDate) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val taskList = app.database.taskDao().getTasksForDateSync(date)

            // Detect conflicts for scheduled tasks
            val conflicts = mutableSetOf<Long>()
            val scheduled = taskList.filter { it.taskType == TaskType.SCHEDULED && it.outcome == TaskOutcome.PENDING }
            for (i in scheduled.indices) {
                for (j in i + 1 until scheduled.size) {
                    val a = scheduled[i]
                    val b = scheduled[j]
                    val aStart = a.startTimeHour * 60 + a.startTimeMinute
                    val aEnd = a.endTimeHour * 60 + a.endTimeMinute
                    val bStart = b.startTimeHour * 60 + b.startTimeMinute
                    val bEnd = b.endTimeHour * 60 + b.endTimeMinute

                    if (aStart < bEnd && bStart < aEnd) {
                        conflicts.add(a.id)
                        conflicts.add(b.id)
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                tasks = taskList,
                conflictTaskIds = conflicts,
                isLoading = false
            )
        }
    }

    fun saveTask(context: Context, task: TaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val id = app.database.taskDao().insertTask(task)
            val savedTask = if (task.id == 0L) task.copy(id = id) else task
            AlarmScheduler.scheduleTask(context, savedTask)
            loadTasks(context)
        }
    }

    fun updateTaskOutcome(context: Context, taskId: Long, outcome: TaskOutcome) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.taskDao().updateTaskOutcome(taskId, outcome, System.currentTimeMillis())
            loadTasks(context)
        }
    }

    fun deleteTask(context: Context, task: TaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            app.database.taskDao().deleteTask(task)
            AlarmScheduler.cancelTask(context, task.id)
            loadTasks(context)
        }
    }

    fun carryOverUnfinishedToTomorrow(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as FocusTrackApp
            val today = dateFormat.format(Date())
            val tomorrowCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            val tomorrowStr = dateFormat.format(tomorrowCal.time)

            val todayTasks = app.database.taskDao().getTasksForDateSync(today)
            val unfinished = todayTasks.filter { it.outcome == TaskOutcome.PENDING || it.outcome == TaskOutcome.MISSED }

            for (task in unfinished) {
                val carried = task.copy(
                    id = 0,
                    dateString = tomorrowStr,
                    outcome = TaskOutcome.PENDING,
                    outcomeTimestamp = 0L
                )
                val id = app.database.taskDao().insertTask(carried)
                AlarmScheduler.scheduleTask(context, carried.copy(id = id))
            }

            setDate(tomorrowStr, isTomorrow = true, context = context)
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

    var showCreateDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<TaskEntity?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val todayStr = remember { dateFormat.format(Date()) }
    val tomorrowCal = remember { Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) } }
    val tomorrowStr = remember { dateFormat.format(tomorrowCal.time) }

    LaunchedEffect(Unit) {
        if (uiState.selectedDate.isEmpty()) {
            viewModel.initDate()
        }
        viewModel.loadTasks(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.isTomorrow) "Tomorrow's Planner 🌙" else "Today's Schedule 📋",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    FilledTonalButton(
                        onClick = { viewModel.carryOverUnfinishedToTomorrow(context) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp).testTag("carry_over_btn")
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Carry to Tomorrow", style = MaterialTheme.typography.labelSmall)
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
                    taskToEdit = null
                    showCreateDialog = true
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
            // Day Switcher (Today vs Tomorrow)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = !uiState.isTomorrow,
                    onClick = { viewModel.setDate(todayStr, isTomorrow = false, context = context) },
                    label = { Text("Today (${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())})") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).testTag("chip_today")
                )
                FilterChip(
                    selected = uiState.isTomorrow,
                    onClick = { viewModel.setDate(tomorrowStr, isTomorrow = true, context = context) },
                    label = { Text("Tomorrow (${SimpleDateFormat("MMM d", Locale.getDefault()).format(tomorrowCal.time)})") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).testTag("chip_tomorrow")
                )
            }

            // Conflict Warning Banner
            if (uiState.conflictTaskIds.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Schedule Conflict Detected: Two or more scheduled tasks overlap in time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Task List
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (uiState.tasks.isEmpty()) {
                    item {
                        EmptyStateCard(
                            title = if (uiState.isTomorrow) "No Tasks Planned for Tomorrow" else "No Tasks for Today",
                            description = "Tap '+' below to add a scheduled time-block or a flexible checklist task.",
                            icon = Icons.Outlined.Checklist,
                            actionText = "Add First Task",
                            onActionClick = {
                                taskToEdit = null
                                showCreateDialog = true
                            }
                        )
                    }
                } else {
                    items(uiState.tasks, key = { it.id }) { task ->
                        val hasConflict = uiState.conflictTaskIds.contains(task.id)
                        TaskItemCard(
                            task = task,
                            hasConflict = hasConflict,
                            onOutcomeChanged = { outcome ->
                                viewModel.updateTaskOutcome(context, task.id, outcome)
                            },
                            onEdit = {
                                taskToEdit = task
                                showCreateDialog = true
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

    // Task Creation / Editing Dialog
    if (showCreateDialog) {
        TaskEditDialog(
            existingTask = taskToEdit,
            defaultDate = uiState.selectedDate,
            onDismiss = { showCreateDialog = false },
            onSave = { task ->
                viewModel.saveTask(context, task)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun TaskItemCard(
    task: TaskEntity,
    hasConflict: Boolean,
    onOutcomeChanged: (TaskOutcome) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isCompleted = task.outcome == TaskOutcome.COMPLETED
    val isMissed = task.outcome == TaskOutcome.MISSED
    val isSkipped = task.outcome == TaskOutcome.SKIPPED

    val cardColor = when {
        isCompleted -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        hasConflict -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCompleted) 0.dp else 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Outcome Toggle Checkbox
                IconButton(
                    onClick = {
                        val nextOutcome = if (isCompleted) TaskOutcome.PENDING else TaskOutcome.COMPLETED
                        onOutcomeChanged(nextOutcome)
                    },
                    modifier = Modifier.size(36.dp).testTag("task_check_${task.id}")
                ) {
                    Icon(
                        imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = "Toggle Complete",
                        tint = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f).clickable { onEdit() }) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (task.taskType == TaskType.SCHEDULED) {
                            val startStr = String.format("%02d:%02d", task.startTimeHour, task.startTimeMinute)
                            val endStr = String.format("%02d:%02d", task.endTimeHour, task.endTimeMinute)
                            Text(
                                text = "⏰ $startStr - $endStr",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(text = " • ", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = "${task.category} • ${task.priority.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Delete Button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Outcome Controls Row (Pending, Missed, Skipped)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Status: ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AssistChip(
                    onClick = { onOutcomeChanged(TaskOutcome.COMPLETED) },
                    label = { Text("Done ✓") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (isCompleted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                AssistChip(
                    onClick = { onOutcomeChanged(TaskOutcome.MISSED) },
                    label = { Text("Missed ✗") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (isMissed) MaterialTheme.colorScheme.errorContainer else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                AssistChip(
                    onClick = { onOutcomeChanged(TaskOutcome.SKIPPED) },
                    label = { Text("Skip") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (isSkipped) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }
}

@Composable
fun TaskEditDialog(
    existingTask: TaskEntity?,
    defaultDate: String,
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    Text("Time: ${String.format("%02d:%02d", startHour, startMin)} to ${String.format("%02d:%02d", endHour, endMin)}", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                startHour = (startHour + 1) % 24
                                endHour = (startHour + 1) % 24
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Shift Hour (+1h)")
                        }
                    }
                }

                // Category selection
                Text("Category:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
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
