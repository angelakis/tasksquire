package com.tasksquire.android.ui

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
import com.tasksquire.android.data.Task
import com.tasksquire.android.data.TaskPriority
import com.tasksquire.android.data.TaskStatus
import com.tasksquire.android.ui.DependencyFilter
import com.tasksquire.android.ui.DueFilter
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
    val filterUntagged by viewModel.filterUntagged.collectAsState()
    val filterPriority by viewModel.filterPriority.collectAsState()
    val dependencyFilter by viewModel.dependencyFilter.collectAsState()
    val dueFilter by viewModel.dueFilter.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val recentHistory by viewModel.recentHistory.collectAsState()
    val allProjects by viewModel.projects.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val taskIndex by viewModel.taskIndex.collectAsState()

    var showCompleted by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showReportsSheet by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val hasActiveFilters = searchQuery.isNotBlank() || filterProject != null ||
            filterTag != null || filterUntagged || filterPriority != null ||
            dependencyFilter != DependencyFilter.ALL || dueFilter != DueFilter.ALL

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
            selectedUntagged = filterUntagged,
            selectedPriority = filterPriority,
            selectedDependencyFilter = dependencyFilter,
            selectedDueFilter = dueFilter,
            onProjectSelected = { viewModel.setFilterProject(it) },
            onTagSelected = { viewModel.setFilterTag(it) },
            onUntaggedSelected = { viewModel.setFilterUntagged(it) },
            onPrioritySelected = { viewModel.setFilterPriority(it) },
            onDependencyFilterSelected = { viewModel.setDependencyFilter(it) },
            onDueFilterSelected = { viewModel.setDueFilter(it) },
            onClearAll = { viewModel.clearAllFilters() },
            onDismiss = { showFilterSheet = false }
        )
    }

    if (showReportsSheet) {
        ReportsBottomSheet(
            stats = stats,
            recentHistory = recentHistory,
            onApplyReport = {
                viewModel.applyReport(it)
                showReportsSheet = false
            },
            onDismiss = { showReportsSheet = false }
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
                            Text("TaskSquire")
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
                        IconButton(onClick = { showReportsSheet = true }) {
                            Icon(Icons.Filled.BarChart, contentDescription = "Reports")
                        }
                        if (canUndo) {
                            IconButton(onClick = { viewModel.undoLastAction() }) {
                                Icon(Icons.Filled.Undo, contentDescription = "Undo")
                            }
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
                        if (filterUntagged) {
                            ActiveFilterChip(
                                label = "Not tagged",
                                onRemove = { viewModel.setFilterUntagged(false) }
                            )
                        }
                        filterPriority?.let {
                            ActiveFilterChip(
                                label = "Priority: ${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                                onRemove = { viewModel.setFilterPriority(null) }
                            )
                        }
                        if (dependencyFilter != DependencyFilter.ALL) {
                            ActiveFilterChip(
                                label = if (dependencyFilter == DependencyFilter.BLOCKED) "Blocked"
                                else "Unblocked",
                                onRemove = { viewModel.setDependencyFilter(DependencyFilter.ALL) }
                            )
                        }
                        if (dueFilter != DueFilter.ALL) {
                            ActiveFilterChip(
                                label = if (dueFilter == DueFilter.OVERDUE) "Overdue" else "Due Today",
                                onRemove = { viewModel.setDueFilter(DueFilter.ALL) }
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
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(
                        items = tasks.sortedByDescending { it.computedUrgency() },
                        key = { it.uuid }
                    ) { task ->
                        TaskItem(
                            task = task,
                            isBlocked = task.isBlockedBy(taskIndex),
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
    selectedUntagged: Boolean,
    selectedPriority: TaskPriority?,
    selectedDependencyFilter: DependencyFilter,
    selectedDueFilter: DueFilter,
    onProjectSelected: (String?) -> Unit,
    onTagSelected: (String?) -> Unit,
    onUntaggedSelected: (Boolean) -> Unit,
    onPrioritySelected: (TaskPriority?) -> Unit,
    onDependencyFilterSelected: (DependencyFilter) -> Unit,
    onDueFilterSelected: (DueFilter) -> Unit,
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
            Text("Tag", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTag == null && !selectedUntagged,
                    onClick = {
                        onUntaggedSelected(false)
                        onTagSelected(null)
                    },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = selectedUntagged,
                    onClick = {
                        onUntaggedSelected(!selectedUntagged)
                        if (!selectedUntagged) onTagSelected(null)
                    },
                    label = { Text("Not tagged") }
                )
                for (tag in tags) {
                    FilterChip(
                        selected = selectedTag == tag && !selectedUntagged,
                        onClick = {
                            onUntaggedSelected(false)
                            onTagSelected(if (selectedTag == tag) null else tag)
                        },
                        label = { Text("+$tag") }
                    )
                }
            }

            Text("Dependencies", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedDependencyFilter == DependencyFilter.ALL,
                    onClick = { onDependencyFilterSelected(DependencyFilter.ALL) },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = selectedDependencyFilter == DependencyFilter.BLOCKED,
                    onClick = { onDependencyFilterSelected(DependencyFilter.BLOCKED) },
                    label = { Text("Blocked") }
                )
                FilterChip(
                    selected = selectedDependencyFilter == DependencyFilter.UNBLOCKED,
                    onClick = { onDependencyFilterSelected(DependencyFilter.UNBLOCKED) },
                    label = { Text("Unblocked") }
                )
            }

            Text("Due", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedDueFilter == DueFilter.ALL,
                    onClick = { onDueFilterSelected(DueFilter.ALL) },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = selectedDueFilter == DueFilter.OVERDUE,
                    onClick = { onDueFilterSelected(DueFilter.OVERDUE) },
                    label = { Text("Overdue") }
                )
                FilterChip(
                    selected = selectedDueFilter == DueFilter.DUE_TODAY,
                    onClick = { onDueFilterSelected(DueFilter.DUE_TODAY) },
                    label = { Text("Due Today") }
                )
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
fun ReportsBottomSheet(
    stats: TaskStats,
    recentHistory: List<Task>,
    onApplyReport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Reports", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = { onApplyReport("blocked") }, label = { Text("Blocked") })
                FilterChip(selected = false, onClick = { onApplyReport("unblocked") }, label = { Text("Unblocked") })
                FilterChip(selected = false, onClick = { onApplyReport("overdue") }, label = { Text("Overdue") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = { onApplyReport("due_today") }, label = { Text("Due Today") })
                FilterChip(selected = false, onClick = { onApplyReport("high_priority") }, label = { Text("High Priority") })
            }

            HorizontalDivider()
            Text("Stats", style = MaterialTheme.typography.titleMedium)
            Text("Pending: ${stats.pending}  Completed: ${stats.completed}  Waiting: ${stats.waiting}")
            Text("Blocked: ${stats.blocked}  Unblocked: ${stats.unblocked}")
            Text("Overdue: ${stats.overdue}  Due Today: ${stats.dueToday}")

            HorizontalDivider()
            Text("History (recent completions)", style = MaterialTheme.typography.titleMedium)

            if (recentHistory.isEmpty()) {
                Text(
                    "No completed tasks yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val formatter = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())
                for (task in recentHistory.take(10)) {
                    Text("• ${formatter.format(Date(task.modified))}  ${task.description}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskItem(
    task: Task,
    isBlocked: Boolean = false,
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
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (task.status == TaskStatus.COMPLETED)
                            TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Urgency readout, so it's clear why a task sorted where it did
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f", task.computedUrgency()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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

                Spacer(Modifier.height(2.dp))

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
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(text = project, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    task.due?.let {
                        val isActive = task.status == TaskStatus.PENDING || task.status == TaskStatus.WAITING
                        TaskChip(
                            text = formatDueLabel(it),
                            color = dueChipColor(it, isActive)
                        )
                    }
                    if (task.getDependencyList().isNotEmpty()) {
                        TaskChip(
                            text = if (isBlocked) "Blocked" else "Depends ${task.getDependencyList().size}",
                            color = if (isBlocked) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.tertiaryContainer
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
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(text = "+$tag", fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/**
 * Formats a due date as a countdown relative to now (e.g. "Overdue 2d", "Tomorrow",
 * "In 3w"), falling back to an absolute date once it's far enough out that the
 * countdown stops being meaningful at a glance.
 */
fun formatDueLabel(due: Long, now: Long = System.currentTimeMillis()): String {
    val diffDays = (due - now) / (1000.0 * 60 * 60 * 24)
    return when {
        diffDays < 0 -> {
            val overdueDays = kotlin.math.ceil(-diffDays).toInt()
            if (overdueDays <= 1) "Overdue" else "Overdue ${overdueDays}d"
        }
        diffDays < 1 -> "Today"
        diffDays < 2 -> "Tomorrow"
        diffDays < 7 -> "In ${kotlin.math.ceil(diffDays).toInt()}d"
        diffDays < 30 -> "In ${(diffDays / 7).toInt()}w"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(due))
    }
}

@Composable
fun dueChipColor(due: Long, isActive: Boolean, now: Long = System.currentTimeMillis()): Color {
    if (!isActive) return MaterialTheme.colorScheme.secondaryContainer
    val diffDays = (due - now) / (1000.0 * 60 * 60 * 24)
    return when {
        diffDays < 0 -> MaterialTheme.colorScheme.errorContainer
        diffDays < 2 -> MaterialTheme.colorScheme.tertiaryContainer
        diffDays < 7 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
fun TaskChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
