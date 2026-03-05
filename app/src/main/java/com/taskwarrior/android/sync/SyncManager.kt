package com.taskwarrior.android.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taskwarrior.android.data.Task
import com.taskwarrior.android.data.TaskRepository
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

val Context.dataStore by preferencesDataStore(name = "sync_prefs")

/**
 * Coordinates sync between the local database and the TaskChampion sync server.
 *
 * Sync Algorithm:
 * 1. Load local modified tasks and build operations
 * 2. Fetch operations from server since last known version
 * 3. Apply server operations to local DB
 * 4. Upload local operations to server
 * 5. If upload conflicts (409), retry from step 2 (up to 3 times)
 */
class SyncManager(
    private val context: Context,
    private val repository: TaskRepository
) {
    companion object {
        private val KEY_LAST_VERSION = stringPreferencesKey("last_sync_version")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_CLIENT_ID = stringPreferencesKey("client_id")
        private val KEY_ENCRYPTION_SECRET = stringPreferencesKey("encryption_secret")
        private val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private const val NULL_VERSION_ID = "00000000-0000-0000-0000-000000000000"
        private const val MAX_SYNC_RETRIES = 3
    }

    suspend fun getServerConfig(): SyncServerConfig? {
        val prefs = context.dataStore.data.first()
        return getServerConfigFromPreferences(prefs)
    }

    private fun getServerConfigFromPreferences(
        prefs: androidx.datastore.preferences.core.Preferences
    ): SyncServerConfig? {
        val serverUrl = prefs[KEY_SERVER_URL]?.takeIf { it.isNotBlank() } ?: return null
        val clientId = prefs[KEY_CLIENT_ID]?.takeIf { it.isNotBlank() } ?: return null
        val secret = prefs[KEY_ENCRYPTION_SECRET]?.takeIf { it.isNotBlank() }
        return SyncServerConfig(serverUrl, clientId, secret)
    }

    suspend fun saveServerConfig(config: SyncServerConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = config.serverUrl
            prefs[KEY_CLIENT_ID] = config.clientId
            prefs[KEY_ENCRYPTION_SECRET] = config.encryptionSecret ?: ""
        }
        log(SyncLogLevel.INFO, "Saved sync server config for ${config.serverUrl}")
    }

    suspend fun isAutoSyncEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        val configured = getServerConfigFromPreferences(prefs) != null
        val explicitPreference = prefs[KEY_AUTO_SYNC_ENABLED]
        return explicitPreference ?: configured
    }

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_SYNC_ENABLED] = enabled
        }
        log(SyncLogLevel.INFO, if (enabled) "Autosync enabled" else "Autosync disabled")
    }

    suspend fun getSyncLogs(): List<SyncLogEntry> = SyncLogStore.read(context)

    suspend fun clearSyncLogs() {
        SyncLogStore.clear(context)
    }

    private suspend fun getLastVersion(): String {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_LAST_VERSION] ?: NULL_VERSION_ID
    }

    private suspend fun saveLastVersion(versionId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_VERSION] = versionId
        }
    }

    /**
     * Perform a full sync cycle.
     */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val config = getServerConfig() ?: return@withContext SyncResult.NoServerConfigured

        log(SyncLogLevel.INFO, "Starting sync with ${config.serverUrl}")
        val client = TaskChampionSyncClient(config)
        var retries = 0

        while (retries < MAX_SYNC_RETRIES) {
            try {
                val result = performSyncCycle(client)
                if (result !is SyncResult.Error || retries >= MAX_SYNC_RETRIES - 1) {
                    when (result) {
                        is SyncResult.Success -> log(
                            SyncLogLevel.INFO,
                            "Sync complete: sent=${result.tasksSent} tasks, received=${result.tasksReceived} tasks"
                        )
                        is SyncResult.Error -> log(SyncLogLevel.ERROR, "Sync failed: ${result.message}")
                        SyncResult.NoServerConfigured -> log(SyncLogLevel.WARN, "Sync skipped: no server configured")
                        SyncResult.NotModified -> log(SyncLogLevel.INFO, "Sync not modified")
                    }
                    return@withContext result
                }
            } catch (e: SyncException) {
                log(SyncLogLevel.ERROR, "Sync exception: ${e.message}")
                if (retries >= MAX_SYNC_RETRIES - 1) {
                    return@withContext SyncResult.Error(e.message ?: "Sync failed", e)
                }
            }
            log(SyncLogLevel.WARN, "Retrying sync after conflict/error (attempt ${retries + 2}/$MAX_SYNC_RETRIES)")
            retries++
        }
        log(SyncLogLevel.ERROR, "Sync failed: max retries exceeded")
        return@withContext SyncResult.Error("Max retries exceeded")
    }

    private suspend fun performSyncCycle(client: TaskChampionSyncClient): SyncResult {
        var lastVersion = getLastVersion()

        // If we have no version history, try to fetch a snapshot first
        if (lastVersion == NULL_VERSION_ID) {
            log(SyncLogLevel.INFO, "No local sync version; fetching snapshot")
            val snapshot = try {
                client.fetchSnapshot()
            } catch (e: SyncException) {
                return SyncResult.Error("Failed to fetch snapshot: ${e.message}", e)
            }

            if (snapshot != null) {
                // Apply snapshot to local DB
                val tasks = snapshot.tasks.mapNotNull { (uuid, props) ->
                    OperationBuilder.taskFromProperties(uuid, props)
                }
                repository.upsertTasksFromSync(tasks)
                lastVersion = snapshot.versionId
                saveLastVersion(lastVersion)
                log(SyncLogLevel.INFO, "Applied snapshot with ${tasks.size} tasks")
            } else {
                log(SyncLogLevel.INFO, "No snapshot on server")
            }
        }

        // Fetch operations from server since our last version
        log(SyncLogLevel.INFO, "Fetching server operations since $lastVersion")
        val serverOps = try {
            client.getOperationsSince(lastVersion)
        } catch (e: SyncException) {
            return SyncResult.Error("Failed to fetch operations: ${e.message}", e)
        }

        var serverTasksReceived = 0
        if (serverOps != null) {
            val (newVersionId, operations) = serverOps
            applyOperationsToLocal(operations)
            serverTasksReceived = operations.map { when (it) {
                is Operation.Create -> it.uuid
                is Operation.Update -> it.uuid
                is Operation.Delete -> it.uuid
            } }.distinct().size
            lastVersion = newVersionId
            saveLastVersion(lastVersion)
            log(SyncLogLevel.INFO, "Received $serverTasksReceived tasks (${operations.size} operations)")
        } else {
            log(SyncLogLevel.INFO, "No child version found for $lastVersion")
        }

        // Build operations from local modifications
        val modifiedTasks = repository.getModifiedTasks()
        val localOps = buildLocalOperations(modifiedTasks)
        log(SyncLogLevel.INFO, "Prepared ${localOps.size} local operations from ${modifiedTasks.size} modified tasks")

        if (localOps.isEmpty()) {
            return SyncResult.Success(serverTasksReceived, 0)
        }

        // Upload local operations
        log(SyncLogLevel.INFO, "Uploading ${localOps.size} local operations")
        val newVersionId = try {
            client.uploadOperations(lastVersion, localOps)
        } catch (e: SyncVersionNotFoundException) {
            // Server doesn't know our parent version — reset local state so the next
            // retry starts from scratch (fetches a fresh snapshot).
            log(SyncLogLevel.WARN, "Server does not recognise version $lastVersion, resetting sync state")
            saveLastVersion(NULL_VERSION_ID)
            return SyncResult.Error("Server version mismatch, resetting sync state: ${e.message}", e)
        } catch (e: SyncException) {
            return SyncResult.Error("Failed to upload operations: ${e.message}", e)
        }

        if (newVersionId == null) {
            // Conflict - retry
            log(SyncLogLevel.WARN, "Version conflict during upload")
            return SyncResult.Error("Version conflict, retrying...")
        }

        // Mark all uploaded tasks as synced
        for (task in modifiedTasks) {
            repository.markTaskSynced(task.uuid)
        }
        saveLastVersion(newVersionId)

        // Periodically upload a snapshot (every 50 operations approx)
        if (localOps.size > 20) {
            uploadSnapshot(client, newVersionId)
        }

        return SyncResult.Success(serverTasksReceived, modifiedTasks.size)
    }

    private suspend fun applyOperationsToLocal(operations: List<Operation>) {
        for (op in operations) {
            when (op) {
                is Operation.Create -> {
                    // Will be populated by subsequent Update operations
                    val existing = repository.getTaskByUuid(op.uuid)
                    if (existing == null) {
                        // Placeholder - will be filled in by Update ops
                    }
                }
                is Operation.Update -> {
                    val existing = repository.getTaskByUuid(op.uuid)
                    if (existing != null) {
                        val updated = applyUpdateToTask(existing, op)
                        repository.upsertTasksFromSync(listOf(updated))
                    }
                    // If task doesn't exist yet, it will be created by the Create op
                    // or we need to batch all ops for the same UUID together
                }
                is Operation.Delete -> {
                    repository.deleteTask(op.uuid)
                }
            }
        }

        // Second pass: handle tasks that were created then updated in the same batch
        val createdUuids = operations.filterIsInstance<Operation.Create>().map { it.uuid }
        if (createdUuids.isNotEmpty()) {
            val updatesByUuid = operations
                .filterIsInstance<Operation.Update>()
                .filter { it.uuid in createdUuids }
                .groupBy { it.uuid }

            for (uuid in createdUuids) {
                val existing = repository.getTaskByUuid(uuid)
                if (existing == null) {
                    val props = mutableMapOf<String, String>()
                    for (update in updatesByUuid[uuid] ?: emptyList()) {
                        if (update.value != null) {
                            props[update.property] = update.value
                        }
                    }
                    val task = OperationBuilder.taskFromProperties(uuid, props)
                    if (task != null) {
                        repository.upsertTasksFromSync(listOf(task))
                    }
                }
            }
        }
    }

    private fun applyUpdateToTask(task: Task, op: Operation.Update): Task {
        return when (op.property) {
            "description" -> task.copy(description = op.value ?: task.description)
            "status" -> task.copy(status = when (op.value?.lowercase()) {
                "pending" -> com.taskwarrior.android.data.TaskStatus.PENDING
                "completed" -> com.taskwarrior.android.data.TaskStatus.COMPLETED
                "deleted" -> com.taskwarrior.android.data.TaskStatus.DELETED
                "waiting" -> com.taskwarrior.android.data.TaskStatus.WAITING
                else -> task.status
            })
            "priority" -> task.copy(priority = when (op.value?.uppercase()) {
                "H" -> com.taskwarrior.android.data.TaskPriority.HIGH
                "M" -> com.taskwarrior.android.data.TaskPriority.MEDIUM
                "L" -> com.taskwarrior.android.data.TaskPriority.LOW
                else -> com.taskwarrior.android.data.TaskPriority.NONE
            })
            "project" -> task.copy(project = op.value)
            "due" -> task.copy(due = parseTaskChampionTimestamp(op.value))
            "wait" -> task.copy(wait = parseTaskChampionTimestamp(op.value))
            "scheduled" -> task.copy(scheduled = parseTaskChampionTimestamp(op.value))
            "recur" -> task.copy(recur = op.value)
            "entry" -> task.copy(entry = parseTaskChampionTimestamp(op.value) ?: task.entry)
            "modified" -> task.copy(modified = parseTaskChampionTimestamp(op.value) ?: task.modified)
            else -> {
                when {
                    op.property.startsWith("tag_") -> {
                        val tag = op.property.removePrefix("tag_")
                        val tags = task.getTagList().toMutableList()
                        if (op.value != null) {
                            if (!tags.contains(tag)) tags.add(tag)
                        } else {
                            tags.remove(tag)
                        }
                        task.withTagList(tags)
                    }
                    op.property.startsWith("dep_") -> {
                        val dep = op.property.removePrefix("dep_")
                        val deps = task.getDependencyList().toMutableList()
                        if (op.value != null) {
                            if (!deps.contains(dep)) deps.add(dep)
                        } else {
                            deps.remove(dep)
                        }
                        task.withDependencyList(deps)
                    }
                    op.property.startsWith("annotation_") -> {
                        val tsStr = op.property.removePrefix("annotation_")
                        val ts = tsStr.toLongOrNull() ?: return task
                        val entryMillis = ts * 1000
                        val annotations = mutableListOf<JSONObject>()
                        task.annotations?.let {
                            try {
                                val arr = JSONArray(it)
                                for (i in 0 until arr.length()) {
                                    annotations.add(arr.getJSONObject(i))
                                }
                            } catch (_: Exception) {}
                        }
                        if (op.value != null) {
                            annotations.removeAll { it.optLong("entry") == entryMillis }
                            annotations.add(JSONObject().apply {
                                put("entry", entryMillis)
                                put("description", op.value)
                            })
                        } else {
                            annotations.removeAll { it.optLong("entry") == entryMillis }
                        }
                        val jsonStr = if (annotations.isEmpty()) null else JSONArray(annotations).toString()
                        task.copy(annotations = jsonStr)
                    }
                    else -> task
                }
            }
        }
    }

    private fun buildLocalOperations(tasks: List<Task>): List<Operation> {
        val ops = mutableListOf<Operation>()
        for (task in tasks) {
            if (!task.isSynced) {
                // New task - send Create + Updates
                ops.addAll(OperationBuilder.buildCreateOperations(task))
            } else {
                // Existing task - send Updates
                ops.addAll(OperationBuilder.buildUpdateOperations(task))
            }
        }
        return ops
    }

    private suspend fun uploadSnapshot(client: TaskChampionSyncClient, versionId: String) {
        try {
            val allTasks = repository.getAllTasks()
            val taskMap = allTasks.associate { task ->
                task.uuid to OperationBuilder.taskToProperties(task)
            }
            val snapshot = Snapshot(versionId = versionId, tasks = taskMap)
            client.uploadSnapshot(snapshot)
            log(SyncLogLevel.INFO, "Uploaded snapshot with ${allTasks.size} tasks")
        } catch (e: Exception) {
            // Snapshot upload failure is non-fatal; sync already succeeded
            log(SyncLogLevel.WARN, "Snapshot upload failed: ${e.message}")
        }
    }

    private suspend fun log(level: SyncLogLevel, message: String) {
        SyncLogStore.append(context, level, message)
    }
}
