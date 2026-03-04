package com.taskwarrior.android.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

enum class SyncLogLevel {
    INFO,
    WARN,
    ERROR
}

data class SyncLogEntry(
    val timestamp: Long,
    val level: SyncLogLevel,
    val message: String
)

object SyncLogStore {
    private val KEY_SYNC_LOGS = stringPreferencesKey("sync_logs")
    private const val MAX_LOG_ENTRIES = 200

    suspend fun append(context: Context, level: SyncLogLevel, message: String) {
        val sanitized = message.trim().ifBlank { return }
        context.dataStore.edit { prefs ->
            val existing = parseLogs(prefs[KEY_SYNC_LOGS])
            val updated = (existing + SyncLogEntry(System.currentTimeMillis(), level, sanitized))
                .takeLast(MAX_LOG_ENTRIES)
            prefs[KEY_SYNC_LOGS] = logsToJson(updated)
        }
    }

    suspend fun read(context: Context): List<SyncLogEntry> {
        val prefs = context.dataStore.data.first()
        return parseLogs(prefs[KEY_SYNC_LOGS])
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SYNC_LOGS)
        }
    }

    private fun parseLogs(raw: String?): List<SyncLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val ts = obj.optLong("timestamp", 0L)
                    val level = runCatching {
                        SyncLogLevel.valueOf(obj.optString("level", SyncLogLevel.INFO.name))
                    }.getOrElse { SyncLogLevel.INFO }
                    val message = obj.optString("message", "")
                    if (ts > 0 && message.isNotBlank()) {
                        add(SyncLogEntry(ts, level, message))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun logsToJson(entries: List<SyncLogEntry>): String {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("level", entry.level.name)
                    put("message", entry.message)
                }
            )
        }
        return arr.toString()
    }
}
