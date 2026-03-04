package com.taskwarrior.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskwarrior.android.data.Task
import com.taskwarrior.android.data.TaskPriority
import com.taskwarrior.android.data.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    viewModel: TaskViewModel,
    onAddTask: () -> Unit,
    onTaskClick: (String) -> Unit,
    onSettings: () -> Unit
) {
    val pendingTasks by viewModel.filteredPendingTasks.collectAsState()
    val completedTasks by viewModel.filteredCompletedTasks.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterProject by viewModel.filterProject.collectAsState()
    val filterTag by viewModel.filterTag.collectAsState()
    val filterPriority by viewModel.filterPriority.collectAsState()
    val allProjects by viewModel.projects.collectAsState()
    val allTags by viewModel.allTags.collectAsState()

    var showCompleted by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val hasActiveFilters = searchQuery.isNotBlank() || filterProject != null ||
            filterTag != null || filterPriority != null

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is UiState.SyncSuccess -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.dismissMessage()
            }
            is UiState.Error -> {
                snackbarHostState.showSnackbar("Error: ${state.message}")
                viewModel.dismissMessage()
            }
            else -> {}
        }
    }

    // Filter bottom sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            projects = allProjects,
            tags = allTags,
            selectedProject = filterProject,
            selectedTag = filterTag,
            selectedPriority = filterPriority,
            onProjectSelected = { viewModel.setFilterProject(it) },
            onTagSelected = { viewModel.setFilterTag(it) },
            onPrioritySelected = { viewModel.setFilterPriority(it) },
            onClearAll = { viewModel.clearAllFilters() },
            onDismiss = { showFilterSheet = false }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searchExpanded) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text("Search tasks...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyLarge,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                        }
                                    }
                                }
                            )
                        } else {
                            Text("Taskwarrior")
                        }
                    },
                    navigationIcon = {
                        if (searchExpanded) {
                            IconButton(onClick = {
                                searchExpanded = false
                                viewModel.setSearchQuery("")
                            }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Close search")
                            }
                        }
                    },
                    actions = {
                        if (!searchExpanded) {
                            IconButton(onClick = { searchExpanded = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        }
                        // Filter button with badge
                        IconButton(onClick = { showFilterSheet = true }) {
                            BadgedBox(
                                badge = {
                                    if (hasActiveFilters) {
                                        Badge { }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.FilterList, contentDescription = "Filter")
                            }
                        }
                        if (uiState is UiState.Syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { viewModel.sync() }) {
                                Icon(Icons.Filled.Sync, contentDescription = "Sync")
                            }
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                // Active filter chips row
                AnimatedVisibility(visible = hasActiveFilters) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (searchQuery.isNotBlank()) {
                            ActiveFilterChip(
                                label = "\"$searchQuery\"",
                                onRemove = { viewModel.setSearchQuery("") }
                            )
                        }
                        filterProject?.let {
                            ActiveFilterChip(
                                label = "Project: $it",
                                onRemove = { viewModel.setFilterProject(null) }
                            )
                        }
                        filterTag?.let {
                            ActiveFilterChip(
                                label = "Tag: +$it",
                                onRemove = { viewModel.setFilterTag(null) }
                            )
                        }
                        filterPriority?.let {
                            ActiveFilterChip(
                                label = "Priority: ${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                                onRemove = { viewModel.setFilterPriority(null) }
                            )
                        }
                        TextButton(
                            onClick = { viewModel.clearAllFilters() },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Clear all", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTask) {
                Icon(Icons.Filled.Add, contentDescription = "Add Task")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Tab row for Pending / Completed
            TabRow(selectedTabIndex = if (showCompleted) 1 else 0) {
                Tab(
                    selected = !showCompleted,
                    onClick = { showCompleted = false },
                    text = { Text("Pending (${pendingTasks.size})") }
                )
                Tab(
                    selected = showCompleted,
                    onClick = { showCompleted = true },
                    text = { Text("Completed (${completedTasks.size})") }
                )
            }

            val tasks = if (showCompleted) completedTasks else pendingTasks

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (hasActiveFilters) "No tasks match filters"
                        else if (showCompleted) "No completed tasks"
                        else "No pending tasks\nTap + to add one",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = tasks.sortedByDescending { it.computedUrgency() },
                        key = { it.uuid }
                    ) { task ->
                        TaskItem(
                            task = task,
                            onClick = { onTaskClick(task.uuid) },
                            onComplete = { viewModel.completeTask(task.uuid) },
                            onDelete = { viewModel.deleteTask(task.uuid) },
                            onTagClick = { tag -> viewModel.setFilterTag(tag) },
                            onProjectClick = { project -> viewModel.setFilterProject(project) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveFilterChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBottomSheet(
    projects: List<String>,
    tags: List<String>,
    selectedProject: String?,
    selectedTag: String?,
    selectedPriority: TaskPriority?,
    onProjectSelected: (String?) -> Unit,
    onTagSelected: (String?) -> Unit,
    onPrioritySelected: (TaskPriority?) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = {
                    onClearAll()
                    onDismiss()
                }) { Text("Clear All") }
            }

            // Priority filter
            Text("Priority", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val priorities = listOf(null to "All") +
                        TaskPriority.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
                for ((p, label) in priorities) {
                    FilterChip(
                        selected = selectedPriority == p,
                        onClick = {
                            onPrioritySelected(p)
                        },
                        label = { Text(label) }
                    )
                }
            }

            // Project filter
            if (projects.isNotEmpty()) {
                Text("Project", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedProject == null,
                        onClick = { onProjectSelected(null) },
                        label = { Text("All") }
                    )
                    for (project in projects) {
                        FilterChip(
                            selected = selectedProject == project,
                            onClick = {
                                onProjectSelected(if (selectedProject == project) null else project)
                            },
                            label = { Text(project) }
                        )
                    }
                }
            }

            // Tag filter
            if (tags.isNotEmpty()) {
                Text("Tag", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTag == null,
                        onClick = { onTagSelected(null) },
                        label = { Text("All") }
                    )
                    for (tag in tags) {
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = {
                                onTagSelected(if (selectedTag == tag) null else tag)
                            },
                            label = { Text("+$tag") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskItem(
    task: Task,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onTagClick: (String) -> Unit = {},
    onProjectClick: (String) -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Task") },
            text = { Text("Delete \"${task.description}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Completion checkbox
            if (task.status == TaskStatus.PENDING || task.status == TaskStatus.WAITING) {
                Checkbox(
                    checked = false,
                    onCheckedChange = { if (it) onComplete() }
                )
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Completed",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp).padding(2.dp)
                )
                Spacer(Modifier.width(8.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Priority indicator
                    PriorityBadge(task.priority)

                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.status == TaskStatus.COMPLETED)
                            TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Recurrence indicator
                if (task.recur != null) {
                    Text(
                        text = "Repeats: ${task.recur}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    task.project?.let { project ->
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onProjectClick(project) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = project, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    task.due?.let {
                        val dateStr = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(it))
                        val isOverdue = it < System.currentTimeMillis() && task.status == TaskStatus.PENDING
                        TaskChip(
                            text = dateStr,
                            color = if (isOverdue) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        )
                    }
                    for (tag in task.getTagList().take(3)) {
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onTagClick(tag) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "+$tag", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Delete button
            if (task.status == TaskStatus.PENDING) {
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PriorityBadge(priority: TaskPriority) {
    val (text, color) = when (priority) {
        TaskPriority.HIGH -> "H" to Color(0xFFE53935)
        TaskPriority.MEDIUM -> "M" to Color(0xFFFB8C00)
        TaskPriority.LOW -> "L" to Color(0xFF43A047)
        TaskPriority.NONE -> return
    }
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.padding(end = 4.dp)
    )
}

@Composable
fun TaskChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
