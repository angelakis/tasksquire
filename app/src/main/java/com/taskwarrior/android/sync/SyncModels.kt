package com.taskwarrior.android.sync

/**
 * TaskChampion Sync Protocol Models
 *
 * The TaskChampion sync protocol uses a version-based operation log.
 * Each client tracks a "version" (UUID) and sends/receives operations
 * relative to that version.
 *
 * Reference: https://github.com/GothenburgBitFactory/taskchampion
 */

/**
 * A sync operation representing a change to the task database.
 * Operations are sent to and received from the sync server.
 */
sealed class Operation {
    /** Create a new task with the given UUID */
    data class Create(val uuid: String) : Operation()

    /** Update a property of a task (null value means deletion of the property) */
    data class Update(
        val uuid: String,
        val property: String,
        val value: String?,
        val timestamp: Long
    ) : Operation()

    /** Mark a task as deleted */
    data class Delete(val uuid: String) : Operation()

    fun toMap(): Map<String, Any?> = when (this) {
        is Create -> mapOf("op" to "Create", "uuid" to uuid)
        is Update -> mapOf(
            "op" to "Update",
            "uuid" to uuid,
            "property" to property,
            "value" to value,
            "timestamp" to timestamp
        )
        is Delete -> mapOf("op" to "Delete", "uuid" to uuid)
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): Operation? {
            return when (map["op"] as? String) {
                "Create" -> Create(uuid = map["uuid"] as? String ?: return null)
                "Update" -> Update(
                    uuid = map["uuid"] as? String ?: return null,
                    property = map["property"] as? String ?: return null,
                    value = map["value"] as? String,
                    timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
                )
                "Delete" -> Delete(uuid = map["uuid"] as? String ?: return null)
                else -> null
            }
        }
    }
}

/**
 * A snapshot of the entire task database, used as a checkpoint
 * in the sync protocol to avoid storing all operations since the beginning.
 */
data class Snapshot(
    val versionId: String,
    val tasks: Map<String, Map<String, String>> // uuid -> {property -> value}
)

/**
 * Result of a sync operation
 */
sealed class SyncResult {
    data class Success(val tasksReceived: Int, val tasksSent: Int) : SyncResult()
    data class Error(val message: String, val cause: Throwable? = null) : SyncResult()
    object NoServerConfigured : SyncResult()
    object NotModified : SyncResult()
}

/**
 * Server configuration for sync
 */
data class SyncServerConfig(
    val serverUrl: String,
    val clientId: String,
    val encryptionSecret: String? = null
)
