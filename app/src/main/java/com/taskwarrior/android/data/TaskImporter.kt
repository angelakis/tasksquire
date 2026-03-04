package com.taskwarrior.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TaskImporter {

    private val twDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parse(json: String): Result<List<Task>> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i -> parseTask(array.getJSONObject(i)) }
    }

    private fun parseTask(obj: JSONObject): Task {
        return Task(
            uuid = obj.optString("uuid", java.util.UUID.randomUUID().toString()),
            description = obj.getString("description"),
            status = mapStatus(obj.optString("status", "pending")),
            priority = mapPriority(obj.optString("priority", "")),
            project = obj.optStringOrNull("project"),
            tags = obj.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).joinToString(",") { arr.getString(it) }
            },
            due = parseDate(obj.optStringOrNull("due")),
            wait = parseDate(obj.optStringOrNull("wait")),
            scheduled = parseDate(obj.optStringOrNull("scheduled")),
            recur = obj.optStringOrNull("recur"),
            depends = parseDepends(obj),
            entry = parseDate(obj.optStringOrNull("entry")) ?: System.currentTimeMillis(),
            modified = parseDate(obj.optStringOrNull("modified")) ?: System.currentTimeMillis(),
            annotations = obj.optJSONArray("annotations")?.toString(),
            urgency = obj.optDouble("urgency", 0.0),
            isSynced = false,
            isModifiedLocally = true
        )
    }

    private fun mapStatus(status: String): TaskStatus = when (status.lowercase()) {
        "pending" -> TaskStatus.PENDING
        "completed" -> TaskStatus.COMPLETED
        "deleted" -> TaskStatus.DELETED
        "waiting" -> TaskStatus.WAITING
        "recurring" -> TaskStatus.RECURRING
        else -> TaskStatus.PENDING
    }

    private fun mapPriority(priority: String): TaskPriority = when (priority.uppercase()) {
        "H" -> TaskPriority.HIGH
        "M" -> TaskPriority.MEDIUM
        "L" -> TaskPriority.LOW
        else -> TaskPriority.NONE
    }

    private fun parseDate(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            twDateFormat.parse(dateStr)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDepends(obj: JSONObject): String? {
        val dependsArray = obj.optJSONArray("depends")
        if (dependsArray != null) {
            return (0 until dependsArray.length())
                .mapNotNull { dependsArray.optString(it, null)?.takeIf { v -> v.isNotBlank() } }
                .joinToString(",")
                .ifBlank { null }
        }

        return obj.optStringOrNull("depends")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(",")
            ?.ifBlank { null }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return if (has(key) && !isNull(key)) getString(key) else null
    }
}
