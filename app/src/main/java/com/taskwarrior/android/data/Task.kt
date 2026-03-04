package com.taskwarrior.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class TaskStatus {
    PENDING, COMPLETED, DELETED, WAITING, RECURRING
}

enum class TaskPriority {
    HIGH, MEDIUM, LOW, NONE
}

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    val uuid: String = UUID.randomUUID().toString(),
    val description: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: TaskPriority = TaskPriority.NONE,
    val project: String? = null,
    val tags: String? = null, // comma-separated list
    val due: Long? = null, // epoch millis
    val wait: Long? = null, // epoch millis
    val scheduled: Long? = null, // epoch millis
    val recur: String? = null,
    val entry: Long = System.currentTimeMillis(), // creation time
    val modified: Long = System.currentTimeMillis(),
    val annotations: String? = null, // JSON array of {entry, description}
    val urgency: Double = 0.0,
    val isSynced: Boolean = false,
    val isModifiedLocally: Boolean = true
) {
    fun getTagList(): List<String> {
        return tags?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    fun withTagList(tagList: List<String>): Task {
        return copy(tags = tagList.joinToString(","))
    }

    fun computedUrgency(): Double {
        var score = 0.0
        when (priority) {
            TaskPriority.HIGH -> score += 6.0
            TaskPriority.MEDIUM -> score += 3.9
            TaskPriority.LOW -> score += 1.8
            TaskPriority.NONE -> {}
        }
        if (due != null) {
            val daysUntilDue = (due - System.currentTimeMillis()) / (1000.0 * 60 * 60 * 24)
            score += when {
                daysUntilDue < 0 -> 12.0   // overdue
                daysUntilDue < 1 -> 10.0   // due today
                daysUntilDue < 7 -> 8.0    // due this week
                daysUntilDue < 14 -> 4.0   // due in 2 weeks
                daysUntilDue < 30 -> 2.0   // due this month
                else -> 0.0
            }
        }
        val ageDays = (System.currentTimeMillis() - entry) / (1000.0 * 60 * 60 * 24)
        score += minOf(ageDays * 0.01, 2.0)
        return score
    }
}
