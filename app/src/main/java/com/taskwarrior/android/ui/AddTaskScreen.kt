package com.taskwarrior.android.ui

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
    var showDatePicker by remember { mutableStateOf(false) }
    var descriptionError by remember { mutableStateOf(false) }

    val datePickerState = rememberDatePickerState()

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

            // Project
            OutlinedTextField(
                value = project,
                onValueChange = { project = it },
                label = { Text("Project") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

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

            // Tags
            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text("Tags (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("work, important, home") }
            )

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
                    viewModel.addTask(
                        description = description.trim(),
                        project = project.trim().ifBlank { null },
                        priority = priority,
                        tags = tags,
                        due = dueDate
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Task")
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
