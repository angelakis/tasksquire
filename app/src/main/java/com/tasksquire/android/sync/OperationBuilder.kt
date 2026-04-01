package com.tasksquire.android.sync

import com.tasksquire.android.data.Task
import com.tasksquire.android.data.TaskPriority
import com.tasksquire.android.data.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

private val KNOWN_SCALAR_PROPERTIES = setOf(
    "description", "status", "entry", "modified", "priority", "project",
    "due", "wait", "scheduled", "recur", "end", "start", "until"
)
private val KNOWN_PROPERTY_PREFIXES = listOf("tag_", "dep_", "annotation_")

/**
 * Converts between Task objects and sync Operations.
 */
object OperationBuilder {

    /**
     * Build the operations needed to create a new task on the server.
     */
    fun buildCreateOperations(task: Task): List<Operation> {
        val ops = mutableListOf<Operation>()
        val now = System.currentTimeMillis()

        ops.add(Operation.Create(uuid = task.uuid))

        ops.add(Operation.Update(task.uuid, "description", task.description, now))
        // TaskChampion only recognises: pending, completed, deleted, recurring.
        // WAITING is a local UI concept; on the wire it is "pending" with a "wait" property.
        val wireStatus = if (task.status == TaskStatus.WAITING) "pending" else task.status.name.lowercase()
        ops.add(Operation.Update(task.uuid, "status", wireStatus, now))
        ops.add(Operation.Update(task.uuid, "entry", (task.entry / 1000).toString(), now))
        ops.add(Operation.Update(task.uuid, "modified", (task.modified / 1000).toString(), now))

        if (task.priority != TaskPriority.NONE) {
            ops.add(Operation.Update(task.uuid, "priority", task.priority.name.substring(0, 1), now))
        }
        task.project?.let { ops.add(Operation.Update(task.uuid, "project", it, now)) }
        task.due?.let { ops.add(Operation.Update(task.uuid, "due", (it / 1000).toString(), now)) }
        task.wait?.let { ops.add(Operation.Update(task.uuid, "wait", (it / 1000).toString(), now)) }
        task.scheduled?.let { ops.add(Operation.Update(task.uuid, "scheduled", (it / 1000).toString(), now)) }
        task.recur?.let { ops.add(Operation.Update(task.uuid, "recur", it, now)) }
        task.end?.let { ops.add(Operation.Update(task.uuid, "end", (it / 1000).toString(), now)) }
        task.start?.let { ops.add(Operation.Update(task.uuid, "start", (it / 1000).toString(), now)) }
        task.until?.let { ops.add(Operation.Update(task.uuid, "until", (it / 1000).toString(), now)) }

        parseExtraProperties(task.extraProperties)?.forEach { (key, value) ->
            ops.add(Operation.Update(task.uuid, key, value, now))
        }

        for (dep in task.getDependencyList()) {
            ops.add(Operation.Update(task.uuid, "dep_$dep", "", now))
        }

        for (tag in task.getTagList()) {
            ops.add(Operation.Update(task.uuid, "tag_$tag", "", now))
        }

        parseAnnotationsJson(task.annotations)?.forEach { (ts, desc) ->
            ops.add(Operation.Update(task.uuid, "annotation_$ts", desc, now))
        }

        return ops
    }

    /**
     * Build operations to update a task (compared to a previous version).
     * For simplicity, we re-send all properties.
     */
    fun buildUpdateOperations(task: Task): List<Operation> {
        val ops = mutableListOf<Operation>()
        val now = task.modified

        ops.add(Operation.Update(task.uuid, "description", task.description, now))
        val wireStatus = if (task.status == TaskStatus.WAITING) "pending" else task.status.name.lowercase()
        ops.add(Operation.Update(task.uuid, "status", wireStatus, now))
        ops.add(Operation.Update(task.uuid, "modified", (now / 1000).toString(), now))

        ops.add(Operation.Update(
            task.uuid, "priority",
            if (task.priority == TaskPriority.NONE) null else task.priority.name.substring(0, 1),
            now
        ))
        ops.add(Operation.Update(task.uuid, "project", task.project, now))
        ops.add(Operation.Update(task.uuid, "due", task.due?.let { (it / 1000).toString() }, now))
        ops.add(Operation.Update(task.uuid, "wait", task.wait?.let { (it / 1000).toString() }, now))
        ops.add(Operation.Update(task.uuid, "scheduled", task.scheduled?.let { (it / 1000).toString() }, now))
        ops.add(Operation.Update(task.uuid, "recur", task.recur, now))
        ops.add(Operation.Update(task.uuid, "end", task.end?.let { (it / 1000).toString() }, now))
        ops.add(Operation.Update(task.uuid, "start", task.start?.let { (it / 1000).toString() }, now))
        ops.add(Operation.Update(task.uuid, "until", task.until?.let { (it / 1000).toString() }, now))

        for (dep in task.getDependencyList()) {
            ops.add(Operation.Update(task.uuid, "dep_$dep", "", now))
        }

        for (tag in task.getTagList()) {
            ops.add(Operation.Update(task.uuid, "tag_$tag", "", now))
        }

        parseAnnotationsJson(task.annotations)?.forEach { (ts, desc) ->
            ops.add(Operation.Update(task.uuid, "annotation_$ts", desc, now))
        }

        return ops
    }

    /**
     * Build operation to delete a task.
     */
    fun buildDeleteOperation(uuid: String): Operation {
        return Operation.Delete(uuid = uuid)
    }

    /**
     * Convert a map of task properties (from a snapshot or server) to a Task object.
     */
    fun taskFromProperties(uuid: String, props: Map<String, String>): Task? {
        val description = props["description"] ?: return null

        val statusStr = props["status"] ?: "pending"
        val status = when (statusStr.lowercase()) {
            "pending" -> TaskStatus.PENDING
            "completed" -> TaskStatus.COMPLETED
            "deleted" -> TaskStatus.DELETED
            "waiting" -> TaskStatus.WAITING
            "recurring" -> TaskStatus.RECURRING
            else -> TaskStatus.PENDING
        }

        val priorityStr = props["priority"]
        val priority = when (priorityStr?.uppercase()) {
            "H" -> TaskPriority.HIGH
            "M" -> TaskPriority.MEDIUM
            "L" -> TaskPriority.LOW
            else -> TaskPriority.NONE
        }

        val tags = props.keys
            .filter { it.startsWith("tag_") }
            .map { it.removePrefix("tag_") }
            .joinToString(",")
            .ifEmpty { null }

        val depends = props.keys
            .filter { it.startsWith("dep_") }
            .map { it.removePrefix("dep_") }
            .joinToString(",")
            .ifEmpty { null }

        val annotations = buildAnnotationsJson(props)

        val extra = props.entries
            .filter { (key, _) ->
                key !in KNOWN_SCALAR_PROPERTIES &&
                KNOWN_PROPERTY_PREFIXES.none { key.startsWith(it) }
            }
            .associate { (k, v) -> k to v }
        val extraJson = if (extra.isEmpty()) null else JSONObject(extra).toString()

        return Task(
            uuid = uuid,
            description = description,
            status = status,
            priority = priority,
            project = props["project"],
            tags = tags,
            due = parseTaskChampionTimestamp(props["due"]),
            wait = parseTaskChampionTimestamp(props["wait"]),
            scheduled = parseTaskChampionTimestamp(props["scheduled"]),
            recur = props["recur"],
            depends = depends,
            entry = parseTaskChampionTimestamp(props["entry"]) ?: System.currentTimeMillis(),
            modified = parseTaskChampionTimestamp(props["modified"]) ?: System.currentTimeMillis(),
            end = parseTaskChampionTimestamp(props["end"]),
            start = parseTaskChampionTimestamp(props["start"]),
            until = parseTaskChampionTimestamp(props["until"]),
            annotations = annotations,
            extraProperties = extraJson,
            isSynced = true,
            isModifiedLocally = false
        )
    }

    /**
     * Convert a Task to a map of properties for snapshot storage.
     */
    fun taskToProperties(task: Task): Map<String, String> {
        val props = mutableMapOf<String, String>()
        props["description"] = task.description
        props["status"] = if (task.status == TaskStatus.WAITING) "pending" else task.status.name.lowercase()
        props["entry"] = (task.entry / 1000).toString()
        props["modified"] = (task.modified / 1000).toString()
        if (task.priority != TaskPriority.NONE) {
            props["priority"] = task.priority.name.substring(0, 1)
        }
        task.project?.let { props["project"] = it }
        task.due?.let { props["due"] = (it / 1000).toString() }
        task.wait?.let { props["wait"] = (it / 1000).toString() }
        task.scheduled?.let { props["scheduled"] = (it / 1000).toString() }
        task.recur?.let { props["recur"] = it }
        task.end?.let { props["end"] = (it / 1000).toString() }
        task.start?.let { props["start"] = (it / 1000).toString() }
        task.until?.let { props["until"] = (it / 1000).toString() }
        parseExtraProperties(task.extraProperties)?.forEach { (k, v) -> props[k] = v }
        for (dep in task.getDependencyList()) {
            props["dep_$dep"] = ""
        }
        for (tag in task.getTagList()) {
            props["tag_$tag"] = ""
        }
        parseAnnotationsJson(task.annotations)?.forEach { (ts, desc) ->
            props["annotation_$ts"] = desc
        }
        return props
    }

    /**
     * Parse the stored extra-properties JSON object into a map.
     */
    internal fun parseExtraProperties(json: String?): Map<String, String>? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.getString(k)
            }
            result.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse the local JSON annotations string into a list of (timestamp_seconds, description) pairs.
     */
    private fun parseAnnotationsJson(json: String?): List<Pair<String, String>>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val entry = obj.optLong("entry", 0L)
                val desc = obj.optString("description", "") ?: return@mapNotNull null
                if (entry == 0L || desc.isEmpty()) return@mapNotNull null
                // entry is stored as millis locally; convert to seconds for the wire format
                (entry / 1000).toString() to desc
            }.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a JSON annotations string from annotation_<timestamp> properties.
     */
    private fun buildAnnotationsJson(props: Map<String, String>): String? {
        val annotations = props.entries
            .filter { it.key.startsWith("annotation_") }
            .mapNotNull { (key, value) ->
                val tsStr = key.removePrefix("annotation_")
                val ts = tsStr.toLongOrNull() ?: return@mapNotNull null
                JSONObject().apply {
                    put("entry", ts * 1000) // convert seconds to millis for local storage
                    put("description", value)
                }
            }
        if (annotations.isEmpty()) return null
        return JSONArray(annotations).toString()
    }
}
