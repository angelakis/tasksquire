package com.tasksquire.android.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TaskChampion operation and snapshot serialization/deserialization.
 *
 * Verifies:
 * - Operation JSON format matches spec (Section 4.3)
 * - Sync operations do NOT include old_task or old_value
 * - Null value handling (property deletion)
 * - Timestamp format (RFC3339 with Z suffix)
 * - Snapshot encoding/decoding (array-of-tuples format)
 * - Round-trip consistency
 */
class TaskChampionCodecTest {

    // --- History Segment Encoding ---

    @Test
    fun `encodeHistorySegment Create operation format`() {
        val ops = listOf(Operation.Create("56e0be07-c61f-494c-a54c-bdcfdd52d2a7"))
        val bytes = TaskChampionCodec.encodeHistorySegment(ops)
        val json = JSONArray(bytes.toString(Charsets.UTF_8))

        assertEquals(1, json.length())
        val opObj = json.getJSONObject(0)
        assertTrue("Should have Create key", opObj.has("Create"))
        val create = opObj.getJSONObject("Create")
        assertEquals("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", create.getString("uuid"))
    }

    @Test
    fun `encodeHistorySegment Delete operation does NOT include old_task`() {
        val ops = listOf(Operation.Delete("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        val bytes = TaskChampionCodec.encodeHistorySegment(ops)
        val json = JSONArray(bytes.toString(Charsets.UTF_8))

        val opObj = json.getJSONObject(0)
        val delete = opObj.getJSONObject("Delete")
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", delete.getString("uuid"))
        assertFalse("Sync Delete should NOT include old_task", delete.has("old_task"))
    }

    @Test
    fun `encodeHistorySegment Update operation does NOT include old_value`() {
        val ops = listOf(
            Operation.Update(
                uuid = "56e0be07-c61f-494c-a54c-bdcfdd52d2a7",
                property = "description",
                value = "Buy groceries",
                timestamp = 1705312200000L // 2024-01-15T10:30:00Z in millis
            )
        )
        val bytes = TaskChampionCodec.encodeHistorySegment(ops)
        val json = JSONArray(bytes.toString(Charsets.UTF_8))

        val opObj = json.getJSONObject(0)
        val update = opObj.getJSONObject("Update")
        assertEquals("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", update.getString("uuid"))
        assertEquals("description", update.getString("property"))
        assertEquals("Buy groceries", update.getString("value"))
        assertFalse("Sync Update should NOT include old_value", update.has("old_value"))
        // Timestamp should be RFC3339
        val ts = update.getString("timestamp")
        assertTrue("Timestamp should contain 'T'", ts.contains("T"))
    }

    @Test
    fun `encodeHistorySegment Update with null value`() {
        val ops = listOf(
            Operation.Update(
                uuid = "56e0be07-c61f-494c-a54c-bdcfdd52d2a7",
                property = "start",
                value = null,
                timestamp = 1705312200000L
            )
        )
        val bytes = TaskChampionCodec.encodeHistorySegment(ops)
        val json = JSONArray(bytes.toString(Charsets.UTF_8))

        val update = json.getJSONObject(0).getJSONObject("Update")
        assertTrue("Null value should be JSON null", update.isNull("value"))
    }

    @Test
    fun `encodeHistorySegment multiple operations`() {
        val ops = listOf(
            Operation.Create("56e0be07-c61f-494c-a54c-bdcfdd52d2a7"),
            Operation.Update("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", "description", "Test", 1705312200000L),
            Operation.Update("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", "status", "pending", 1705312200000L),
            Operation.Delete("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        )
        val bytes = TaskChampionCodec.encodeHistorySegment(ops)
        val json = JSONArray(bytes.toString(Charsets.UTF_8))

        assertEquals(4, json.length())
        assertTrue(json.getJSONObject(0).has("Create"))
        assertTrue(json.getJSONObject(1).has("Update"))
        assertTrue(json.getJSONObject(2).has("Update"))
        assertTrue(json.getJSONObject(3).has("Delete"))
    }

    // --- History Segment Decoding ---

    @Test
    fun `decodeHistorySegment Create operation`() {
        val json = """[{"Create":{"uuid":"56e0be07-c61f-494c-a54c-bdcfdd52d2a7"}}]"""
        val ops = TaskChampionCodec.decodeHistorySegment(json.toByteArray())

        assertEquals(1, ops.size)
        val op = ops[0] as Operation.Create
        assertEquals("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", op.uuid)
    }

    @Test
    fun `decodeHistorySegment Delete operation`() {
        val json = """[{"Delete":{"uuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}}]"""
        val ops = TaskChampionCodec.decodeHistorySegment(json.toByteArray())

        assertEquals(1, ops.size)
        val op = ops[0] as Operation.Delete
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", op.uuid)
    }

    @Test
    fun `decodeHistorySegment Delete with old_task field is tolerated`() {
        // Rust may include old_task in serialized operations; we should tolerate it
        val json = """[{"Delete":{"uuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","old_task":{"description":"Old task"}}}]"""
        val ops = TaskChampionCodec.decodeHistorySegment(json.toByteArray())

        assertEquals(1, ops.size)
        val op = ops[0] as Operation.Delete
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", op.uuid)
    }

    @Test
    fun `decodeHistorySegment Update operation`() {
        val json = """[{"Update":{"uuid":"56e0be07-c61f-494c-a54c-bdcfdd52d2a7","property":"description","value":"Buy groceries","timestamp":"2024-01-15T10:30:00Z"}}]"""
        val ops = TaskChampionCodec.decodeHistorySegment(json.toByteArray())

        assertEquals(1, ops.size)
        val op = ops[0] as Operation.Update
        assertEquals("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", op.uuid)
        assertEquals("description", op.property)
        assertEquals("Buy groceries", op.value)
        assertTrue("Timestamp should be parsed", op.timestamp > 0)
    }

    @Test
    fun `decodeHistorySegment Update with null value`() {
        val json = """[{"Update":{"uuid":"56e0be07-c61f-494c-a54c-bdcfdd52d2a7","property":"start","value":null,"timestamp":"2024-01-15T10:30:00Z"}}]"""
        val ops = TaskChampionCodec.decodeHistorySegment(json.toByteArray())

        assertEquals(1, ops.size)
        val op = ops[0] as Operation.Update
        assertNull("Value should be null for property deletion", op.value)
    }

    @Test
    fun `decodeHistorySegment Update with old_value field is tolerated`() {
        val json = """[{"Update":{"uuid":"56e0be07-c61f-494c-a54c-bdcfdd52d2a7","property":"description","old_value":"Old desc","value":"New desc","timestamp":"2024-01-15T10:30:00Z"}}]"""
        val ops = TaskChampionCodec.decodeHistorySegment(json.toByteArray())

        assertEquals(1, ops.size)
        val op = ops[0] as Operation.Update
        assertEquals("New desc", op.value)
    }

    @Test
    fun `history segment round trip`() {
        val originalOps = listOf(
            Operation.Create("56e0be07-c61f-494c-a54c-bdcfdd52d2a7"),
            Operation.Update("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", "description", "Test task", 1705312200000L),
            Operation.Update("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", "status", "pending", 1705312200000L),
            Operation.Update("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", "tag_work", "", 1705312200000L),
            Operation.Update("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", "start", null, 1705312200000L),
            Operation.Delete("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        )

        val encoded = TaskChampionCodec.encodeHistorySegment(originalOps)
        val decoded = TaskChampionCodec.decodeHistorySegment(encoded)

        assertEquals(originalOps.size, decoded.size)

        // Verify Create
        val create = decoded[0] as Operation.Create
        assertEquals("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", create.uuid)

        // Verify Updates
        val update1 = decoded[1] as Operation.Update
        assertEquals("description", update1.property)
        assertEquals("Test task", update1.value)

        val update2 = decoded[2] as Operation.Update
        assertEquals("status", update2.property)
        assertEquals("pending", update2.value)

        val update3 = decoded[3] as Operation.Update
        assertEquals("tag_work", update3.property)

        val update4 = decoded[4] as Operation.Update
        assertEquals("start", update4.property)
        assertNull(update4.value)

        // Verify Delete
        val delete = decoded[5] as Operation.Delete
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", delete.uuid)
    }

    // --- Snapshot Encoding/Decoding ---

    @Test
    fun `snapshot encodes as array of tuples`() {
        val snapshot = Snapshot(
            versionId = "11111111-2222-3333-4444-555555555555",
            tasks = mapOf(
                "56e0be07-c61f-494c-a54c-bdcfdd52d2a7" to mapOf(
                    "description" to "Buy groceries",
                    "status" to "pending"
                )
            )
        )

        val encoded = TaskChampionCodec.encodeSnapshot(snapshot)
        // Encoded is deflate-compressed; decode to verify
        val decoded = TaskChampionCodec.decodeSnapshot("11111111-2222-3333-4444-555555555555", encoded)

        assertEquals(1, decoded.tasks.size)
        assertEquals("Buy groceries", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("description"))
        assertEquals("pending", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("status"))
    }

    @Test
    fun `snapshot round trip with multiple tasks`() {
        val tasks = mapOf(
            "56e0be07-c61f-494c-a54c-bdcfdd52d2a7" to mapOf(
                "description" to "Buy groceries",
                "status" to "pending",
                "entry" to "1693329505",
                "modified" to "1693329505",
                "tag_home" to "",
                "tag_shopping" to ""
            ),
            "4b7ed904-f7b0-4293-8a10-ad452422c7b3" to mapOf(
                "description" to "Pay rent",
                "status" to "completed",
                "end" to "1693400000",
                "priority" to "H"
            )
        )
        val snapshot = Snapshot(versionId = "ver-1", tasks = tasks)

        val encoded = TaskChampionCodec.encodeSnapshot(snapshot)
        val decoded = TaskChampionCodec.decodeSnapshot("ver-1", encoded)

        assertEquals(2, decoded.tasks.size)
        assertEquals("Buy groceries", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("description"))
        assertEquals("", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("tag_home"))
        assertEquals("Pay rent", decoded.tasks["4b7ed904-f7b0-4293-8a10-ad452422c7b3"]?.get("description"))
        assertEquals("H", decoded.tasks["4b7ed904-f7b0-4293-8a10-ad452422c7b3"]?.get("priority"))
    }

    @Test
    fun `snapshot decode handles legacy object format`() {
        // Legacy format: {uuid: {properties}, ...} instead of [[uuid, {props}], ...]
        val legacyJson = """{"56e0be07-c61f-494c-a54c-bdcfdd52d2a7":{"description":"Legacy task","status":"pending"}}"""
        val compressed = deflateCompress(legacyJson.toByteArray(Charsets.UTF_8))

        val decoded = TaskChampionCodec.decodeSnapshot("ver-1", compressed)

        assertEquals(1, decoded.tasks.size)
        assertEquals("Legacy task", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("description"))
    }

    @Test
    fun `snapshot with empty tasks`() {
        val snapshot = Snapshot(versionId = "ver-1", tasks = emptyMap())
        val encoded = TaskChampionCodec.encodeSnapshot(snapshot)
        val decoded = TaskChampionCodec.decodeSnapshot("ver-1", encoded)

        assertTrue(decoded.tasks.isEmpty())
    }

    @Test
    fun `snapshot preserves UDA properties`() {
        val tasks = mapOf(
            "56e0be07-c61f-494c-a54c-bdcfdd52d2a7" to mapOf(
                "description" to "Task with UDA",
                "status" to "pending",
                "custom_field" to "custom_value",
                "another_uda" to "42"
            )
        )
        val snapshot = Snapshot(versionId = "ver-1", tasks = tasks)

        val encoded = TaskChampionCodec.encodeSnapshot(snapshot)
        val decoded = TaskChampionCodec.decodeSnapshot("ver-1", encoded)

        assertEquals("custom_value", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("custom_field"))
        assertEquals("42", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("another_uda"))
    }

    // --- Helpers ---

    private fun deflateCompress(data: ByteArray): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }
}
