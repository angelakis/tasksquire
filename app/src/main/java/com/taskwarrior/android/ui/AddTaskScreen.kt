package com.taskwarrior.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskwarrior.android.data.TaskPriority
import com.taskwarrior.android.data.TaskStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    viewModel: TaskViewModel,
    existingTaskUuid: String? = null,
    onBack: () -> Unit
) {
    var description by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(TaskPriority.NONE) }
    var tagsText by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<Long?>(null) }
    var recurrence by remember { mutableStateOf("") }
    var dependsText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var descriptionError by remember { mutableStateOf(false) }

    val projects by viewModel.projects.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val allTasks by viewModel.allTasks.collectAsState()

    val datePickerState = rememberDatePickerState()

    // Recurrence options
    val recurrenceOptions = listOf(
        "" to "None",
        "daily" to "Daily",
        "weekly" to "Weekly",
        "biweekly" to "Every 2 Weeks",
        "monthly" to "Monthly",
        "quarterly" to "Quarterly",
        "yearly" to "Yearly"
    )

    val existingTask = remember(existingTaskUuid, allTasks) {
        existingTaskUuid?.let { uuid -> allTasks.firstOrNull { it.uuid == uuid } }
    }

    LaunchedEffect(existingTask?.uuid) {
        existingTask?.let { task ->
            description = task.description
            project = task.project.orEmpty()
            priority = task.priority
            tagsText = task.getTagList().joinToString(", ")
            dueDate = task.due
            recurrence = task.recur.orEmpty()
            dependsText = task.getDependencyList().joinToString(", ")
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dueDate = datePickerState.selectedDateMillis
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingTaskUuid == null) "New Task" else "Edit Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Description
            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it
                    descriptionError = false
                },
                label = { Text("Description *") },
                modifier = Modifier.fillMaxWidth(),
                isError = descriptionError,
                supportingText = if (descriptionError) {
                    { Text("Description is required") }
                } else null,
                singleLine = false,
                maxLines = 3
            )

            // Project with suggestions
            OutlinedTextField(
                value = project,
                onValueChange = { project = it },
                label = { Text("Project") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (projects.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (p in projects) {
                        SuggestionChip(
                            onClick = { project = p },
                            label = { Text(p) }
                        )
                    }
                }
            }

            // Priority
            Text(
                text = "Priority",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PriorityButton("None", priority == TaskPriority.NONE) {
                    priority = TaskPriority.NONE
                }
                PriorityButton("Low", priority == TaskPriority.LOW) {
                    priority = TaskPriority.LOW
                }
                PriorityButton("Medium", priority == TaskPriority.MEDIUM) {
                    priority = TaskPriority.MEDIUM
                }
                PriorityButton("High", priority == TaskPriority.HIGH) {
                    priority = TaskPriority.HIGH
                }
            }

            // Tags with suggestions
            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("work, important, home") }
            )
            if (allTags.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (tag in allTags) {
                        val currentTags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val isSelected = tag in currentTags
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                                if (isSelected) {
                                    tags.remove(tag)
                                } else {
                                    tags.add(tag)
                                }
                                tagsText = tags.joinToString(", ")
                            },
                            label = { Text("+$tag") }
                        )
                    }
                }
            }

            // Due date
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = dueDate?.let {
                        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(it))
                    } ?: "",
                    onValueChange = {},
                    label = { Text("Due Date") },
                    modifier = Modifier.weight(1f),
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Filled.DateRange, contentDescription = "Pick date")
                        }
                    }
                )
                if (dueDate != null) {
                    TextButton(onClick = { dueDate = null }) {
                        Text("Clear")
                    }
                }
            }

            // Recurrence
            Text(
                text = "Repeat",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((value, label) in recurrenceOptions) {
                    FilterChip(
                        selected = recurrence == value,
                        onClick = { recurrence = value },
                        label = { Text(label) }
                    )
                }
            }
            if (recurrence.isNotEmpty()) {
                Text(
                    text = "A due date is recommended for recurring tasks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Custom recurrence input
            var showCustomRecur by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showCustomRecur = !showCustomRecur }) {
                    Text(if (showCustomRecur) "Hide custom" else "Custom interval...")
                }
            }
            if (showCustomRecur) {
                OutlinedTextField(
                    value = recurrence,
                    onValueChange = { recurrence = it },
                    label = { Text("Custom recurrence") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. 3days, 2weeks, 6months") },
                    supportingText = { Text("Taskwarrior format: daily, weekly, monthly, yearly, Nd, Nw, Nm, Ny") }
                )
            }

            // Dependencies
            OutlinedTextField(
                value = dependsText,
                onValueChange = { dependsText = it },
                label = { Text("Depends (UUIDs, comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                supportingText = {
                    Text("Task is blocked until these tasks are completed")
                }
            )
            val dependencyCandidates = allTasks
                .filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.WAITING }
                .filter { it.uuid != existingTaskUuid }
                .take(8)
            if (dependencyCandidates.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val currentDeps = dependsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    for (candidate in dependencyCandidates) {
                        val shortUuid = candidate.uuid.take(8)
                        val selected = currentDeps.contains(candidate.uuid)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val deps = currentDeps.toMutableList()
                                if (selected) deps.remove(candidate.uuid) else deps.add(candidate.uuid)
                                dependsText = deps.joinToString(", ")
                            },
                            label = { Text("${candidate.description.take(18)} ($shortUuid)") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Save button
            Button(
                onClick = {
                    if (description.isBlank()) {
                        descriptionError = true
                        return@Button
                    }
                    val tags = tagsText.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    val depends = dependsText.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    if (existingTask != null) {
                        viewModel.updateTask(
                            existingTask.copy(
                                description = description.trim(),
                                project = project.trim().ifBlank { null },
                                priority = priority,
                                tags = tags.joinToString(",").ifBlank { null },
                                due = dueDate,
                                recur = recurrence.ifBlank { null },
                                depends = depends.joinToString(",").ifBlank { null }
                            )
                        )
                    } else {
                        viewModel.addTask(
                            description = description.trim(),
                            project = project.trim().ifBlank { null },
                            priority = priority,
                            tags = tags,
                            due = dueDate,
                            recur = recurrence.ifBlank { null },
                            depends = depends
                        )
                    }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (existingTask == null) "Save Task" else "Update Task")
            }
        }
    }
}

@Composable
private fun PriorityButton(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}
