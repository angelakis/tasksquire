package com.taskwarrior.android.sync

import com.taskwarrior.android.data.Task
import com.taskwarrior.android.data.TaskPriority
import com.taskwarrior.android.data.TaskStatus

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
        ops.add(Operation.Update(task.uuid, "status", task.status.name.lowercase(), now))
        ops.add(Operation.Update(task.uuid, "entry", task.entry.toString(), now))

        if (task.priority != TaskPriority.NONE) {
            ops.add(Operation.Update(task.uuid, "priority", task.priority.name.substring(0, 1), now))
        }
        task.project?.let { ops.add(Operation.Update(task.uuid, "project", it, now)) }
        task.due?.let { ops.add(Operation.Update(task.uuid, "due", it.toString(), now)) }
        task.wait?.let { ops.add(Operation.Update(task.uuid, "wait", it.toString(), now)) }
        task.scheduled?.let { ops.add(Operation.Update(task.uuid, "scheduled", it.toString(), now)) }
        task.recur?.let { ops.add(Operation.Update(task.uuid, "recur", it, now)) }
        task.depends?.let { ops.add(Operation.Update(task.uuid, "depends", it, now)) }

        val tagList = task.getTagList()
        if (tagList.isNotEmpty()) {
            for (tag in tagList) {
                ops.add(Operation.Update(task.uuid, "tag.$tag", "", now))
            }
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
        ops.add(Operation.Update(task.uuid, "status", task.status.name.lowercase(), now))
        ops.add(Operation.Update(task.uuid, "modified", now.toString(), now))

        ops.add(Operation.Update(
            task.uuid, "priority",
            if (task.priority == TaskPriority.NONE) null else task.priority.name.substring(0, 1),
            now
        ))
        ops.add(Operation.Update(task.uuid, "project", task.project, now))
        ops.add(Operation.Update(task.uuid, "due", task.due?.toString(), now))
        ops.add(Operation.Update(task.uuid, "wait", task.wait?.toString(), now))
        ops.add(Operation.Update(task.uuid, "scheduled", task.scheduled?.toString(), now))
        ops.add(Operation.Update(task.uuid, "recur", task.recur, now))
        ops.add(Operation.Update(task.uuid, "depends", task.depends, now))

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
            .filter { it.startsWith("tag.") }
            .map { it.removePrefix("tag.") }
            .joinToString(",")
            .ifEmpty { null }

        return Task(
            uuid = uuid,
            description = description,
            status = status,
            priority = priority,
            project = props["project"],
            tags = tags,
            due = props["due"]?.toLongOrNull(),
            wait = props["wait"]?.toLongOrNull(),
            scheduled = props["scheduled"]?.toLongOrNull(),
            recur = props["recur"],
            depends = props["depends"],
            entry = props["entry"]?.toLongOrNull() ?: System.currentTimeMillis(),
            modified = props["modified"]?.toLongOrNull() ?: System.currentTimeMillis(),
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
        props["status"] = task.status.name.lowercase()
        props["entry"] = task.entry.toString()
        props["modified"] = task.modified.toString()
        if (task.priority != TaskPriority.NONE) {
            props["priority"] = task.priority.name.substring(0, 1)
        }
        task.project?.let { props["project"] = it }
        task.due?.let { props["due"] = it.toString() }
        task.wait?.let { props["wait"] = it.toString() }
        task.scheduled?.let { props["scheduled"] = it.toString() }
        task.recur?.let { props["recur"] = it }
        task.depends?.let { props["depends"] = it }
        for (tag in task.getTagList()) {
            props["tag.$tag"] = ""
        }
        return props
    }
}
