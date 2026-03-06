package com.taskwarrior.android.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import org.json.JSONArray
import org.json.JSONObject

object TaskChampionCodec {

    fun encodeHistorySegment(operations: List<Operation>): ByteArray {
        val ops = JSONArray()
        for (op in operations) {
            val opObj = when (op) {
                is Operation.Create -> JSONObject().apply {
                    put("Create", JSONObject().apply {
                        put("uuid", op.uuid)
                    })
                }
                is Operation.Delete -> JSONObject().apply {
                    put("Delete", JSONObject().apply {
                        put("uuid", op.uuid)
                    })
                }
                is Operation.Update -> JSONObject().apply {
                    put("Update", JSONObject().apply {
                        put("uuid", op.uuid)
                        put("property", op.property)
                        if (op.value == null) put("value", JSONObject.NULL) else put("value", op.value)
                        put("timestamp", Instant.ofEpochMilli(op.timestamp).toString())
                    })
                }
            }
            ops.put(opObj)
        }
        return ops.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeHistorySegment(bytes: ByteArray): List<Operation> {
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        val ops = obj.getJSONArray("operations")
        return buildList {
            for (i in 0 until ops.length()) {
                val opObj = ops.optJSONObject(i) ?: continue
                when {
                    opObj.has("Create") -> {
                        val create = opObj.optJSONObject("Create") ?: continue
                        add(Operation.Create(uuid = create.optString("uuid")))
                    }
                    opObj.has("Delete") -> {
                        val del = opObj.optJSONObject("Delete") ?: continue
                        add(Operation.Delete(uuid = del.optString("uuid")))
                    }
                    opObj.has("Update") -> {
                        val upd = opObj.optJSONObject("Update") ?: continue
                        val ts = parseRfc3339ToMillis(upd.optString("timestamp"))
                        add(
                            Operation.Update(
                                uuid = upd.optString("uuid"),
                                property = upd.optString("property"),
                                value = if (upd.isNull("value")) null else upd.optString("value"),
                                timestamp = ts
                            )
                        )
                    }
                }
            }
        }
    }

    fun encodeSnapshot(snapshot: Snapshot): ByteArray {
        // TaskChampion Rust expects Vec<(Uuid, TaskMap)> which serde deserializes
        // from a JSON array of [uuid, {properties}] tuples, NOT a JSON object.
        val json = JSONArray()
        for ((uuid, props) in snapshot.tasks) {
            val taskObj = JSONObject()
            for ((k, v) in props) {
                taskObj.put(k, v)
            }
            val entry = JSONArray()
            entry.put(uuid)
            entry.put(taskObj)
            json.put(entry)
        }
        val raw = json.toString().toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        DeflaterOutputStream(baos).use { it.write(raw) }
        return baos.toByteArray()
    }

    fun decodeSnapshot(versionId: String, bytes: ByteArray): Snapshot {
        val inflater = InflaterInputStream(ByteArrayInputStream(bytes))
        val decompressed = inflater.readBytes().toString(Charsets.UTF_8)
        val tasks = mutableMapOf<String, Map<String, String>>()
        val trimmed = decompressed.trimStart()
        if (trimmed.startsWith("[")) {
            // Array format: [[uuid, {properties}], ...] (TaskChampion wire format)
            val arr = JSONArray(decompressed)
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONArray(i) ?: continue
                val uuid = entry.optString(0) ?: continue
                val taskObj = entry.optJSONObject(1) ?: continue
                val props = mutableMapOf<String, String>()
                val propKeys = taskObj.keys()
                while (propKeys.hasNext()) {
                    val key = propKeys.next()
                    props[key] = taskObj.optString(key)
                }
                tasks[uuid] = props
            }
        } else {
            // Legacy object format: {uuid: {properties}, ...}
            val json = JSONObject(decompressed)
            val keys = json.keys()
            while (keys.hasNext()) {
                val uuid = keys.next()
                val taskObj = json.optJSONObject(uuid) ?: continue
                val props = mutableMapOf<String, String>()
                val propKeys = taskObj.keys()
                while (propKeys.hasNext()) {
                    val key = propKeys.next()
                    props[key] = taskObj.optString(key)
                }
                tasks[uuid] = props
            }
        }
        return Snapshot(versionId = versionId, tasks = tasks)
    }

    private fun parseRfc3339ToMillis(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
