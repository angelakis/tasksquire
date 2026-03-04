package com.taskwarrior.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskwarrior.android.data.Task
import com.taskwarrior.android.data.TaskStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    taskUuid: String,
    viewModel: TaskViewModel,
    onBack: () -> Unit
) {
    val pendingTasks by viewModel.pendingTasks.collectAsState()
    val completedTasks by viewModel.completedTasks.collectAsState()
    val task = (pendingTasks + completedTasks).firstOrNull { it.uuid == taskUuid }

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Task") },
            text = { Text("Are you sure you want to delete this task?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(taskUuid)
                    showDeleteDialog = false
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (task?.status == TaskStatus.PENDING) {
                        IconButton(onClick = {
                            viewModel.completeTask(taskUuid)
                            onBack()
                        }) {
                            Icon(Icons.Filled.Check, contentDescription = "Complete")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (task == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Task not found")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Description
            Text(
                text = task.description,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            DetailRow("Status", task.status.name.lowercase().replaceFirstChar { it.uppercase() })

            if (task.priority.name != "NONE") {
                DetailRow("Priority", task.priority.name.lowercase().replaceFirstChar { it.uppercase() })
            }

            task.project?.let { DetailRow("Project", it) }

            task.due?.let {
                val dateStr = SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault()).format(Date(it))
                val isOverdue = it < System.currentTimeMillis() && task.status == TaskStatus.PENDING
                DetailRow(
                    "Due",
                    dateStr + if (isOverdue) " (OVERDUE)" else ""
                )
            }

            val tags = task.getTagList()
            if (tags.isNotEmpty()) {
                DetailRow("Tags", tags.joinToString(", ") { "+$it" })
            }

            task.scheduled?.let {
                val dateStr = SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault()).format(Date(it))
                DetailRow("Scheduled", dateStr)
            }

            task.recur?.let {
                DetailRow("Recurrence", it)
                if (task.status == TaskStatus.PENDING) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Recurring task",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            HorizontalDivider()

            val entryDate = SimpleDateFormat("EEE, MMM dd yyyy HH:mm", Locale.getDefault())
                .format(Date(task.entry))
            DetailRow("Created", entryDate)

            val modifiedDate = SimpleDateFormat("EEE, MMM dd yyyy HH:mm", Locale.getDefault())
                .format(Date(task.modified))
            DetailRow("Modified", modifiedDate)

            DetailRow("UUID", task.uuid)

            DetailRow("Urgency", String.format("%.2f", task.computedUrgency()))

            if (!task.isSynced || task.isModifiedLocally) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Pending sync",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f)
        )
    }
}
