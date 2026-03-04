package com.taskwarrior.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskwarrior.android.data.Task
import com.taskwarrior.android.data.TaskDatabase
import com.taskwarrior.android.data.TaskImporter
import com.taskwarrior.android.data.TaskPriority
import com.taskwarrior.android.data.TaskRepository
import com.taskwarrior.android.data.TaskStatus
import com.taskwarrior.android.sync.AutoSyncScheduler
import com.taskwarrior.android.sync.SyncManager
import com.taskwarrior.android.sync.SyncResult
import com.taskwarrior.android.sync.SyncServerConfig
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Syncing : UiState()
    data class SyncSuccess(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

enum class DependencyFilter {
    ALL,
    BLOCKED,
    UNBLOCKED
}

enum class DueFilter {
    ALL,
    OVERDUE,
    DUE_TODAY
}

data class TaskStats(
    val pending: Int = 0,
    val completed: Int = 0,
    val waiting: Int = 0,
    val blocked: Int = 0,
    val unblocked: Int = 0,
    val overdue: Int = 0,
    val dueToday: Int = 0
)

private sealed class UndoAction {
    data class Added(val uuid: String) : UndoAction()
    data class Restore(val task: Task) : UndoAction()
}

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TaskDatabase.getInstance(application)
    private val repository = TaskRepository(db.taskDao())
    private val syncManager = SyncManager(application, repository)

    val allTasks: StateFlow<List<Task>> = repository.observeAllTasks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingTasks: StateFlow<List<Task>> = repository.observePendingTasks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val completedTasks: StateFlow<List<Task>> = repository.observeCompletedTasks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projects: StateFlow<List<String>> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Extract unique tags from all pending tasks' comma-separated tag strings
    val allTags: StateFlow<List<String>> = repository.observeTagStrings()
        .map { tagStrings ->
            tagStrings.flatMap { it.split(",").map { t -> t.trim() } }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val taskIndex: StateFlow<Map<String, Task>> = allTasks
        .map { tasks -> tasks.associateBy { it.uuid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Filter / search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterProject = MutableStateFlow<String?>(null)
    val filterProject: StateFlow<String?> = _filterProject.asStateFlow()

    private val _filterTag = MutableStateFlow<String?>(null)
    val filterTag: StateFlow<String?> = _filterTag.asStateFlow()
    private val _filterUntagged = MutableStateFlow(false)
    val filterUntagged: StateFlow<Boolean> = _filterUntagged.asStateFlow()

    private val _filterPriority = MutableStateFlow<TaskPriority?>(null)
    val filterPriority: StateFlow<TaskPriority?> = _filterPriority.asStateFlow()

    private val _dependencyFilter = MutableStateFlow(DependencyFilter.ALL)
    val dependencyFilter: StateFlow<DependencyFilter> = _dependencyFilter.asStateFlow()

    private val _dueFilter = MutableStateFlow(DueFilter.ALL)
    val dueFilter: StateFlow<DueFilter> = _dueFilter.asStateFlow()

    private val _lastUndoAction = MutableStateFlow<UndoAction?>(null)
    val canUndo: StateFlow<Boolean> = _lastUndoAction
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val stats: StateFlow<TaskStats> = combine(allTasks, taskIndex) { tasks, index ->
        val now = System.currentTimeMillis()
        val startOfToday = now - (now % (24L * 60L * 60L * 1000L))
        val startOfTomorrow = startOfToday + (24L * 60L * 60L * 1000L)

        val pending = tasks.count { it.status == TaskStatus.PENDING }
        val completed = tasks.count { it.status == TaskStatus.COMPLETED }
        val waiting = tasks.count { it.status == TaskStatus.WAITING }
        val overdue = tasks.count {
            (it.status == TaskStatus.PENDING || it.status == TaskStatus.WAITING) &&
                (it.due ?: Long.MAX_VALUE) < now
        }
        val dueToday = tasks.count {
            (it.status == TaskStatus.PENDING || it.status == TaskStatus.WAITING) &&
                (it.due ?: Long.MAX_VALUE) in startOfToday until startOfTomorrow
        }

        val actionable = tasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.WAITING }
        val blocked = actionable.count { task -> isTaskBlocked(task, index) }

        TaskStats(
            pending = pending,
            completed = completed,
            waiting = waiting,
            blocked = blocked,
            unblocked = actionable.size - blocked,
            overdue = overdue,
            dueToday = dueToday
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TaskStats())

    val recentHistory: StateFlow<List<Task>> = completedTasks
        .map { it.sortedByDescending { task -> task.modified }.take(30) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private data class PendingFilterState(
        val query: String,
        val project: String?,
        val tag: String?,
        val priority: TaskPriority?,
        val dependencyFilter: DependencyFilter,
        val dueFilter: DueFilter
    )

    private data class CompletedFilterPayload(
        val tasks: List<Task>,
        val query: String,
        val project: String?,
        val tag: String?,
        val priority: TaskPriority?
    )

    private val pendingFilterState: StateFlow<PendingFilterState> = combine(
        _searchQuery,
        _filterProject,
        _filterTag,
        _filterPriority,
        _filterUntagged
    ) { query, project, tag, priority, filterUntagged ->
        PendingFilterState(
            query = query,
            project = project,
            tag = if (filterUntagged) null else tag,
            priority = priority,
            dependencyFilter = _dependencyFilter.value,
            dueFilter = _dueFilter.value
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PendingFilterState("", null, null, null, DependencyFilter.ALL, DueFilter.ALL)
    )

    // Filtered pending tasks
    val filteredPendingTasks: StateFlow<List<Task>> = combine(
        combine(pendingTasks, taskIndex) { tasks, index -> tasks to index },
        combine(pendingFilterState, _dependencyFilter, _dueFilter) { state, dep, due ->
            state.copy(dependencyFilter = dep, dueFilter = due)
        }
    ) { (tasks, index), filterState ->
        tasks.filter { task ->
            val blocked = isTaskBlocked(task, index)
            (filterState.query.isBlank() || task.description.contains(filterState.query, ignoreCase = true) ||
                task.project?.contains(filterState.query, ignoreCase = true) == true ||
                task.getTagList().any { it.contains(filterState.query, ignoreCase = true) }) &&
            (filterState.project == null || task.project == filterState.project) &&
            ((_filterUntagged.value && task.getTagList().isEmpty()) ||
                (!_filterUntagged.value && (filterState.tag == null || task.getTagList().contains(filterState.tag)))) &&
            (filterState.priority == null || task.priority == filterState.priority) &&
            (filterState.dependencyFilter == DependencyFilter.ALL ||
                (filterState.dependencyFilter == DependencyFilter.BLOCKED && blocked) ||
                (filterState.dependencyFilter == DependencyFilter.UNBLOCKED && !blocked)) &&
            matchesDueFilter(task, filterState.dueFilter)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Filtered completed tasks
    val filteredCompletedTasks: StateFlow<List<Task>> = combine(
        combine(
            completedTasks,
            _searchQuery,
            _filterProject,
            _filterTag,
            _filterPriority
        ) { tasks, query, project, tag, priority ->
            CompletedFilterPayload(tasks, query, project, tag, priority)
        },
        _filterUntagged
    ) { payload, filterUntagged ->
        payload.tasks.filter { task ->
            (payload.query.isBlank() || task.description.contains(payload.query, ignoreCase = true) ||
                task.project?.contains(payload.query, ignoreCase = true) == true ||
                task.getTagList().any { it.contains(payload.query, ignoreCase = true) }) &&
            (payload.project == null || task.project == payload.project) &&
            ((filterUntagged && task.getTagList().isEmpty()) ||
                (!filterUntagged && (payload.tag == null || task.getTagList().contains(payload.tag)))) &&
            (payload.priority == null || task.priority == payload.priority)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _syncConfig = MutableStateFlow<SyncServerConfig?>(null)
    val syncConfig: StateFlow<SyncServerConfig?> = _syncConfig.asStateFlow()

    private val _autoSyncEnabled = MutableStateFlow(false)
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            _syncConfig.value = syncManager.getServerConfig()
            _autoSyncEnabled.value = syncManager.isAutoSyncEnabled()
            AutoSyncScheduler.schedulePeriodicSync(application, _autoSyncEnabled.value)
        }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setFilterProject(project: String?) { _filterProject.value = project }
    fun setFilterTag(tag: String?) {
        _filterTag.value = tag
        if (tag != null) _filterUntagged.value = false
    }
    fun setFilterUntagged(enabled: Boolean) {
        _filterUntagged.value = enabled
        if (enabled) _filterTag.value = null
    }
    fun setFilterPriority(priority: TaskPriority?) { _filterPriority.value = priority }
    fun setDependencyFilter(filter: DependencyFilter) { _dependencyFilter.value = filter }
    fun setDueFilter(filter: DueFilter) { _dueFilter.value = filter }

    fun clearAllFilters() {
        _searchQuery.value = ""
        _filterProject.value = null
        _filterTag.value = null
        _filterUntagged.value = false
        _filterPriority.value = null
        _dependencyFilter.value = DependencyFilter.ALL
        _dueFilter.value = DueFilter.ALL
    }

    val hasActiveFilters: Boolean
        get() = _searchQuery.value.isNotBlank() ||
            _filterProject.value != null ||
            _filterTag.value != null ||
            _filterUntagged.value ||
            _filterPriority.value != null ||
            _dependencyFilter.value != DependencyFilter.ALL ||
            _dueFilter.value != DueFilter.ALL

    fun applyReport(reportId: String) {
        clearAllFilters()
        when (reportId) {
            "blocked" -> _dependencyFilter.value = DependencyFilter.BLOCKED
            "unblocked" -> _dependencyFilter.value = DependencyFilter.UNBLOCKED
            "overdue" -> _dueFilter.value = DueFilter.OVERDUE
            "due_today" -> _dueFilter.value = DueFilter.DUE_TODAY
            "high_priority" -> _filterPriority.value = TaskPriority.HIGH
        }
    }

    fun addTask(
        description: String,
        project: String? = null,
        priority: TaskPriority = TaskPriority.NONE,
        tags: List<String> = emptyList(),
        due: Long? = null,
        recur: String? = null,
        scheduled: Long? = null,
        wait: Long? = null,
        depends: List<String> = emptyList(),
        skipSync: Boolean = false
    ) {
        viewModelScope.launch {
            val task = Task(
                description = description,
                project = project?.takeIf { it.isNotBlank() },
                priority = priority,
                tags = tags.joinToString(",").ifEmpty { null },
                due = due,
                recur = recur?.takeIf { it.isNotBlank() },
                scheduled = scheduled,
                wait = wait,
                depends = depends.joinToString(",").ifBlank { null }
            )
            repository.addTask(task)
            _lastUndoAction.value = UndoAction.Added(task.uuid)
            triggerAutoSync(skipSync)
        }
    }

    fun updateTask(task: Task, skipSync: Boolean = false) {
        viewModelScope.launch {
            val existing = repository.getTaskByUuid(task.uuid)
            existing?.let { _lastUndoAction.value = UndoAction.Restore(it) }
            repository.updateTask(task)
            triggerAutoSync(skipSync)
        }
    }

    fun completeTask(uuid: String, skipSync: Boolean = false) {
        viewModelScope.launch {
            val existing = repository.getTaskByUuid(uuid)
            existing?.let { _lastUndoAction.value = UndoAction.Restore(it) }
            repository.completeTask(uuid)
            triggerAutoSync(skipSync)
        }
    }

    fun deleteTask(uuid: String, skipSync: Boolean = false) {
        viewModelScope.launch {
            val existing = repository.getTaskByUuid(uuid)
            existing?.let { _lastUndoAction.value = UndoAction.Restore(it) }
            repository.deleteTask(uuid)
            triggerAutoSync(skipSync)
        }
    }

    fun undoLastAction() {
        viewModelScope.launch {
            val action = _lastUndoAction.value ?: return@launch
            when (action) {
                is UndoAction.Added -> repository.permanentlyDeleteTask(action.uuid)
                is UndoAction.Restore -> repository.upsertTaskRaw(
                    action.task.copy(modified = System.currentTimeMillis(), isModifiedLocally = true)
                )
            }
            _lastUndoAction.value = null
            _uiState.value = UiState.SyncSuccess("Last change undone")
            triggerAutoSync(skipSync = false)
        }
    }

    fun sync() {
        viewModelScope.launch {
            _uiState.value = UiState.Syncing
            val result = syncManager.sync()
            _uiState.value = when (result) {
                is SyncResult.Success ->
                    UiState.SyncSuccess("Sync complete: ${result.operationsSent} sent, ${result.operationsApplied} received")
                is SyncResult.Error -> UiState.Error(result.message)
                SyncResult.NoServerConfigured -> UiState.Error("No sync server configured")
                SyncResult.NotModified -> UiState.SyncSuccess("Already up to date")
            }
        }
    }

    fun saveSyncConfig(serverUrl: String, clientId: String, secret: String) {
        viewModelScope.launch {
            val config = SyncServerConfig(serverUrl, clientId, secret.takeIf { it.isNotBlank() })
            syncManager.saveServerConfig(config)
            _syncConfig.value = config
            _autoSyncEnabled.value = syncManager.isAutoSyncEnabled()
            AutoSyncScheduler.schedulePeriodicSync(getApplication(), _autoSyncEnabled.value)
            if (_autoSyncEnabled.value) {
                AutoSyncScheduler.enqueueForegroundSync(getApplication())
            }
            _uiState.value = UiState.SyncSuccess("Settings saved")
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            syncManager.setAutoSyncEnabled(enabled)
            _autoSyncEnabled.value = syncManager.isAutoSyncEnabled()
            AutoSyncScheduler.schedulePeriodicSync(getApplication(), _autoSyncEnabled.value)
            _uiState.value = UiState.SyncSuccess(
                if (_autoSyncEnabled.value) "Autosync enabled" else "Autosync disabled"
            )
        }
    }

    fun onAppForegrounded() {
        viewModelScope.launch {
            val enabled = syncManager.isAutoSyncEnabled()
            _autoSyncEnabled.value = enabled
            AutoSyncScheduler.schedulePeriodicSync(getApplication(), enabled)
            if (enabled) {
                AutoSyncScheduler.enqueueForegroundSync(getApplication())
            }
        }
    }

    fun importTasksFromJson(inputStream: InputStream) {
        viewModelScope.launch {
            _uiState.value = UiState.Syncing
            try {
                val json = inputStream.bufferedReader().use { it.readText() }
                TaskImporter.parse(json).fold(
                    onSuccess = { tasks ->
                        repository.importTasks(tasks)
                        _uiState.value = UiState.SyncSuccess("Imported ${tasks.size} tasks")
                    },
                    onFailure = { e ->
                        _uiState.value = UiState.Error("Import failed: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Import failed: ${e.message}")
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = UiState.Idle
    }

    fun getTaskForEdit(uuid: String): Task? = allTasks.value.firstOrNull { it.uuid == uuid }

    private suspend fun triggerAutoSync(skipSync: Boolean) {
        if (skipSync) return
        val enabled = syncManager.isAutoSyncEnabled()
        _autoSyncEnabled.value = enabled
        if (enabled) {
            AutoSyncScheduler.enqueueMutationSync(getApplication())
        }
    }

    private fun isTaskBlocked(task: Task, index: Map<String, Task>): Boolean {
        val dependencies = task.getDependencyList()
        if (dependencies.isEmpty()) return false

        return dependencies.any { depUuid ->
            val depTask = index[depUuid]
            depTask == null || (depTask.status != TaskStatus.COMPLETED && depTask.status != TaskStatus.DELETED)
        }
    }

    private fun matchesDueFilter(task: Task, dueFilter: DueFilter): Boolean {
        if (dueFilter == DueFilter.ALL) return true
        val due = task.due ?: return false
        val now = System.currentTimeMillis()
        return when (dueFilter) {
            DueFilter.ALL -> true
            DueFilter.OVERDUE -> due < now
            DueFilter.DUE_TODAY -> {
                val startOfToday = now - (now % (24L * 60L * 60L * 1000L))
                val startOfTomorrow = startOfToday + (24L * 60L * 60L * 1000L)
                due in startOfToday until startOfTomorrow
            }
        }
    }
}
