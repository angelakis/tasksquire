package com.taskwarrior.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskwarrior.android.data.Task
import com.taskwarrior.android.data.TaskDatabase
import com.taskwarrior.android.data.TaskImporter
import com.taskwarrior.android.data.TaskPriority
import com.taskwarrior.android.data.TaskRepository
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

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TaskDatabase.getInstance(application)
    private val repository = TaskRepository(db.taskDao())
    private val syncManager = SyncManager(application, repository)

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

    // Filter / search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterProject = MutableStateFlow<String?>(null)
    val filterProject: StateFlow<String?> = _filterProject.asStateFlow()

    private val _filterTag = MutableStateFlow<String?>(null)
    val filterTag: StateFlow<String?> = _filterTag.asStateFlow()

    private val _filterPriority = MutableStateFlow<TaskPriority?>(null)
    val filterPriority: StateFlow<TaskPriority?> = _filterPriority.asStateFlow()

    // Filtered pending tasks
    val filteredPendingTasks: StateFlow<List<Task>> = combine(
        pendingTasks, _searchQuery, _filterProject, _filterTag, _filterPriority
    ) { tasks, query, project, tag, priority ->
        tasks.filter { task ->
            (query.isBlank() || task.description.contains(query, ignoreCase = true) ||
                task.project?.contains(query, ignoreCase = true) == true ||
                task.getTagList().any { it.contains(query, ignoreCase = true) }) &&
            (project == null || task.project == project) &&
            (tag == null || task.getTagList().contains(tag)) &&
            (priority == null || task.priority == priority)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Filtered completed tasks
    val filteredCompletedTasks: StateFlow<List<Task>> = combine(
        completedTasks, _searchQuery, _filterProject, _filterTag, _filterPriority
    ) { tasks, query, project, tag, priority ->
        tasks.filter { task ->
            (query.isBlank() || task.description.contains(query, ignoreCase = true) ||
                task.project?.contains(query, ignoreCase = true) == true ||
                task.getTagList().any { it.contains(query, ignoreCase = true) }) &&
            (project == null || task.project == project) &&
            (tag == null || task.getTagList().contains(tag)) &&
            (priority == null || task.priority == priority)
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
    fun setFilterTag(tag: String?) { _filterTag.value = tag }
    fun setFilterPriority(priority: TaskPriority?) { _filterPriority.value = priority }

    fun clearAllFilters() {
        _searchQuery.value = ""
        _filterProject.value = null
        _filterTag.value = null
        _filterPriority.value = null
    }

    val hasActiveFilters: Boolean
        get() = _searchQuery.value.isNotBlank() || _filterProject.value != null ||
                _filterTag.value != null || _filterPriority.value != null

    fun addTask(
        description: String,
        project: String? = null,
        priority: TaskPriority = TaskPriority.NONE,
        tags: List<String> = emptyList(),
        due: Long? = null,
        recur: String? = null,
        scheduled: Long? = null,
        wait: Long? = null,
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
                wait = wait
            )
            repository.addTask(task)
            triggerAutoSync(skipSync)
        }
    }

    fun updateTask(task: Task, skipSync: Boolean = false) {
        viewModelScope.launch {
            repository.updateTask(task)
            triggerAutoSync(skipSync)
        }
    }

    fun completeTask(uuid: String, skipSync: Boolean = false) {
        viewModelScope.launch {
            repository.completeTask(uuid)
            triggerAutoSync(skipSync)
        }
    }

    fun deleteTask(uuid: String, skipSync: Boolean = false) {
        viewModelScope.launch {
            repository.deleteTask(uuid)
            triggerAutoSync(skipSync)
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

    private suspend fun triggerAutoSync(skipSync: Boolean) {
        if (skipSync) return
        val enabled = syncManager.isAutoSyncEnabled()
        _autoSyncEnabled.value = enabled
        if (enabled) {
            AutoSyncScheduler.enqueueMutationSync(getApplication())
        }
    }
}
