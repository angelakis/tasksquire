package com.tasksquire.android.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple JSON serialization helpers for sync protocol.
 * Uses Android's built-in org.json rather than a third-party library.
 */
object JsonSerializer {

    fun operationsToJson(operations: List<Operation>): String {
        val array = JSONArray()
        for (op in operations) {
            val obj = JSONObject()
            when (op) {
                is Operation.Create -> {
                    obj.put("op", "Create")
                    obj.put("uuid", op.uuid)
                }
                is Operation.Update -> {
                    obj.put("op", "Update")
                    obj.put("uuid", op.uuid)
                    obj.put("property", op.property)
                    if (op.value != null) obj.put("value", op.value) else obj.put("value", JSONObject.NULL)
                    obj.put("timestamp", op.timestamp)
                }
                is Operation.Delete -> {
                    obj.put("op", "Delete")
                    obj.put("uuid", op.uuid)
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun operationsFromJson(json: String): List<Operation> {
        val array = JSONArray(json)
        val result = mutableListOf<Operation>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val op = when (obj.getString("op")) {
                "Create" -> Operation.Create(uuid = obj.getString("uuid"))
                "Update" -> Operation.Update(
                    uuid = obj.getString("uuid"),
                    property = obj.getString("property"),
                    value = if (obj.isNull("value")) null else obj.getString("value"),
                    timestamp = obj.optLong("timestamp", 0L)
                )
                "Delete" -> Operation.Delete(uuid = obj.getString("uuid"))
                else -> null
            }
            if (op != null) result.add(op)
        }
        return result
    }

    fun snapshotToJson(snapshot: Snapshot): String {
        val root = JSONObject()
        root.put("version_id", snapshot.versionId)
        val tasksObj = JSONObject()
        for ((uuid, props) in snapshot.tasks) {
            val propsObj = JSONObject()
            for ((k, v) in props) {
                propsObj.put(k, v)
            }
            tasksObj.put(uuid, propsObj)
        }
        root.put("tasks", tasksObj)
        return root.toString()
    }

    fun snapshotFromJson(json: String): Snapshot {
        val root = JSONObject(json)
        val versionId = root.getString("version_id")
        val tasksObj = root.getJSONObject("tasks")
        val tasks = mutableMapOf<String, Map<String, String>>()
        for (uuid in tasksObj.keys()) {
            val propsObj = tasksObj.getJSONObject(uuid)
            val props = mutableMapOf<String, String>()
            for (key in propsObj.keys()) {
                props[key] = propsObj.getString(key)
            }
            tasks[uuid] = props
        }
        return Snapshot(versionId = versionId, tasks = tasks)
    }

    fun versionResponseFromJson(json: String): Pair<String, List<Operation>> {
        val root = JSONObject(json)
        val versionId = root.getString("version_id")
        val opsArray = root.optJSONArray("operations") ?: JSONArray()
        val ops = operationsFromJson(opsArray.toString())
        return Pair(versionId, ops)
    }
}
