package com.taskwarrior.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskwarrior.android.data.Task
import com.taskwarrior.android.data.TaskDatabase
import com.taskwarrior.android.data.TaskPriority
import com.taskwarrior.android.data.TaskRepository
import com.taskwarrior.android.sync.SyncManager
import com.taskwarrior.android.sync.SyncResult
import com.taskwarrior.android.sync.SyncServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _syncConfig = MutableStateFlow<SyncServerConfig?>(null)
    val syncConfig: StateFlow<SyncServerConfig?> = _syncConfig.asStateFlow()

    init {
        viewModelScope.launch {
            _syncConfig.value = syncManager.getServerConfig()
        }
    }

    fun addTask(
        description: String,
        project: String? = null,
        priority: TaskPriority = TaskPriority.NONE,
        tags: List<String> = emptyList(),
        due: Long? = null
    ) {
        viewModelScope.launch {
            val task = Task(
                description = description,
                project = project?.takeIf { it.isNotBlank() },
                priority = priority,
                tags = tags.joinToString(",").ifEmpty { null },
                due = due
            )
            repository.addTask(task)
        }
    }

    fun updateTask(task: Task) {
        viewModelScope.launch {
            repository.updateTask(task)
        }
    }

    fun completeTask(uuid: String) {
        viewModelScope.launch {
            repository.completeTask(uuid)
        }
    }

    fun deleteTask(uuid: String) {
        viewModelScope.launch {
            repository.deleteTask(uuid)
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
            _uiState.value = UiState.SyncSuccess("Settings saved")
        }
    }

    fun dismissMessage() {
        _uiState.value = UiState.Idle
    }
}
