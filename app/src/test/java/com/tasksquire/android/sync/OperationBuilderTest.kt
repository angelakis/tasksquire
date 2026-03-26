package com.tasksquire.android.sync

import com.tasksquire.android.data.Task
import com.tasksquire.android.data.TaskPriority
import com.tasksquire.android.data.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for OperationBuilder — converting between Task objects and sync Operations,
 * and between property maps and Task objects.
 */
class OperationBuilderTest {

    private val testUuid = "56e0be07-c61f-494c-a54c-bdcfdd52d2a7"
    private val testEntry = 1693329505000L  // millis
    private val testModified = 1693329505000L // millis

    // --- buildCreateOperations ---

    @Test
    fun `buildCreateOperations produces Create followed by Updates`() {
        val task = Task(
            uuid = testUuid,
            description = "Buy groceries",
            status = TaskStatus.PENDING,
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildCreateOperations(task)

        assertTrue("First op should be Create", ops[0] is Operation.Create)
        assertEquals(testUuid, (ops[0] as Operation.Create).uuid)

        // Should have Updates for description, status, entry, modified at minimum
        val updates = ops.filterIsInstance<Operation.Update>()
        assertTrue(updates.any { it.property == "description" && it.value == "Buy groceries" })
        assertTrue(updates.any { it.property == "status" && it.value == "pending" })
        assertTrue(updates.any { it.property == "entry" })
        assertTrue(updates.any { it.property == "modified" })
    }

    @Test
    fun `buildCreateOperations includes priority when not NONE`() {
        val task = Task(
            uuid = testUuid,
            description = "Urgent task",
            priority = TaskPriority.HIGH,
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildCreateOperations(task)
        val updates = ops.filterIsInstance<Operation.Update>()

        assertTrue(updates.any { it.property == "priority" && it.value == "H" })
    }

    @Test
    fun `buildCreateOperations does not include priority when NONE`() {
        val task = Task(
            uuid = testUuid,
            description = "Normal task",
            priority = TaskPriority.NONE,
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildCreateOperations(task)
        val updates = ops.filterIsInstance<Operation.Update>()

        assertFalse(updates.any { it.property == "priority" })
    }

    @Test
    fun `buildCreateOperations includes tags as individual tag_ properties`() {
        val task = Task(
            uuid = testUuid,
            description = "Tagged task",
            tags = "home,work",
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildCreateOperations(task)
        val updates = ops.filterIsInstance<Operation.Update>()

        assertTrue(updates.any { it.property == "tag_home" })
        assertTrue(updates.any { it.property == "tag_work" })
    }

    @Test
    fun `buildCreateOperations includes dependencies as dep_ properties`() {
        val depUuid = "11111111-2222-3333-4444-555555555555"
        val task = Task(
            uuid = testUuid,
            description = "Dependent task",
            depends = depUuid,
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildCreateOperations(task)
        val updates = ops.filterIsInstance<Operation.Update>()

        assertTrue(updates.any { it.property == "dep_$depUuid" })
    }

    @Test
    fun `buildCreateOperations includes annotations as annotation_ properties`() {
        val annotations = JSONArray().apply {
            put(JSONObject().apply {
                put("entry", 1693329505000L) // millis
                put("description", "A note")
            })
        }.toString()

        val task = Task(
            uuid = testUuid,
            description = "Annotated task",
            annotations = annotations,
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildCreateOperations(task)
        val updates = ops.filterIsInstance<Operation.Update>()

        // Annotation key should be annotation_<seconds>
        assertTrue(updates.any { it.property == "annotation_1693329505" && it.value == "A note" })
    }

    @Test
    fun `buildCreateOperations converts timestamps to seconds`() {
        val dueMillis = 1700000000000L
        val task = Task(
            uuid = testUuid,
            description = "Due task",
            due = dueMillis,
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildCreateOperations(task)
        val updates = ops.filterIsInstance<Operation.Update>()

        val dueUpdate = updates.first { it.property == "due" }
        assertEquals("1700000000", dueUpdate.value)

        val entryUpdate = updates.first { it.property == "entry" }
        assertEquals((testEntry / 1000).toString(), entryUpdate.value)
    }

    // --- buildUpdateOperations ---

    @Test
    fun `buildUpdateOperations sends all properties`() {
        val task = Task(
            uuid = testUuid,
            description = "Updated task",
            status = TaskStatus.COMPLETED,
            priority = TaskPriority.MEDIUM,
            project = "myproject",
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildUpdateOperations(task)

        // Should all be Update operations (no Create)
        assertTrue(ops.all { it is Operation.Update })
        val updates = ops.filterIsInstance<Operation.Update>()
        assertTrue(updates.any { it.property == "description" && it.value == "Updated task" })
        assertTrue(updates.any { it.property == "status" && it.value == "completed" })
        assertTrue(updates.any { it.property == "priority" && it.value == "M" })
        assertTrue(updates.any { it.property == "project" && it.value == "myproject" })
    }

    @Test
    fun `buildUpdateOperations sends null for NONE priority`() {
        val task = Task(
            uuid = testUuid,
            description = "No priority",
            priority = TaskPriority.NONE,
            entry = testEntry,
            modified = testModified
        )

        val ops = OperationBuilder.buildUpdateOperations(task)
        val updates = ops.filterIsInstance<Operation.Update>()

        val priorityOp = updates.first { it.property == "priority" }
        assertNull("Priority NONE should send null value", priorityOp.value)
    }

    // --- buildDeleteOperation ---

    @Test
    fun `buildDeleteOperation creates Delete operation`() {
        val op = OperationBuilder.buildDeleteOperation(testUuid)

        assertTrue(op is Operation.Delete)
        assertEquals(testUuid, (op as Operation.Delete).uuid)
    }

    // --- taskFromProperties ---

    @Test
    fun `taskFromProperties basic task`() {
        val props = mapOf(
            "description" to "Buy groceries",
            "status" to "pending",
            "entry" to "1693329505",
            "modified" to "1693329505"
        )

        val task = OperationBuilder.taskFromProperties(testUuid, props)

        assertNotNull(task)
        assertEquals(testUuid, task!!.uuid)
        assertEquals("Buy groceries", task.description)
        assertEquals(TaskStatus.PENDING, task.status)
        assertEquals(1693329505000L, task.entry)
        assertEquals(1693329505000L, task.modified)
        assertTrue(task.isSynced)
        assertFalse(task.isModifiedLocally)
    }

    @Test
    fun `taskFromProperties returns null when description missing`() {
        val props = mapOf("status" to "pending")
        val task = OperationBuilder.taskFromProperties(testUuid, props)
        assertNull("Should return null without description", task)
    }

    @Test
    fun `taskFromProperties handles all statuses`() {
        for ((statusStr, expected) in listOf(
            "pending" to TaskStatus.PENDING,
            "completed" to TaskStatus.COMPLETED,
            "deleted" to TaskStatus.DELETED,
            "waiting" to TaskStatus.WAITING,
            "recurring" to TaskStatus.RECURRING
        )) {
            val props = mapOf("description" to "Task", "status" to statusStr)
            val task = OperationBuilder.taskFromProperties(testUuid, props)
            assertEquals("Status '$statusStr' should map to $expected", expected, task!!.status)
        }
    }

    @Test
    fun `taskFromProperties handles priorities`() {
        for ((prioStr, expected) in listOf(
            "H" to TaskPriority.HIGH,
            "M" to TaskPriority.MEDIUM,
            "L" to TaskPriority.LOW
        )) {
            val props = mapOf("description" to "Task", "priority" to prioStr)
            val task = OperationBuilder.taskFromProperties(testUuid, props)
            assertEquals(expected, task!!.priority)
        }
    }

    @Test
    fun `taskFromProperties extracts tags from tag_ keys`() {
        val props = mapOf(
            "description" to "Tagged",
            "tag_home" to "",
            "tag_work" to "x"
        )

        val task = OperationBuilder.taskFromProperties(testUuid, props)
        val tags = task!!.getTagList()

        assertTrue(tags.contains("home"))
        assertTrue(tags.contains("work"))
    }

    @Test
    fun `taskFromProperties extracts dependencies from dep_ keys`() {
        val depUuid = "11111111-2222-3333-4444-555555555555"
        val props = mapOf(
            "description" to "Dependent",
            "dep_$depUuid" to ""
        )

        val task = OperationBuilder.taskFromProperties(testUuid, props)
        val deps = task!!.getDependencyList()

        assertTrue(deps.contains(depUuid))
    }

    @Test
    fun `taskFromProperties builds annotations from annotation_ keys`() {
        val props = mapOf(
            "description" to "Annotated",
            "annotation_1693329505" to "My note"
        )

        val task = OperationBuilder.taskFromProperties(testUuid, props)
        assertNotNull(task!!.annotations)

        val arr = JSONArray(task.annotations)
        assertEquals(1, arr.length())
        val ann = arr.getJSONObject(0)
        assertEquals("My note", ann.getString("description"))
        // Timestamp should be converted to millis
        assertEquals(1693329505000L, ann.getLong("entry"))
    }

    @Test
    fun `taskFromProperties defaults missing status to pending`() {
        val props = mapOf("description" to "No status")
        val task = OperationBuilder.taskFromProperties(testUuid, props)
        assertEquals(TaskStatus.PENDING, task!!.status)
    }

    @Test
    fun `taskFromProperties handles date fields`() {
        val props = mapOf(
            "description" to "Dated task",
            "due" to "1700000000",
            "wait" to "1700100000",
            "scheduled" to "1700200000"
        )

        val task = OperationBuilder.taskFromProperties(testUuid, props)

        assertEquals(1700000000000L, task!!.due)
        assertEquals(1700100000000L, task.wait)
        assertEquals(1700200000000L, task.scheduled)
    }

    // --- taskToProperties ---

    @Test
    fun `taskToProperties round trip`() {
        val task = Task(
            uuid = testUuid,
            description = "Round trip task",
            status = TaskStatus.COMPLETED,
            priority = TaskPriority.HIGH,
            project = "myproject",
            tags = "home,work",
            due = 1700000000000L,
            wait = 1700100000000L,
            recur = "weekly",
            depends = "11111111-2222-3333-4444-555555555555",
            entry = testEntry,
            modified = testModified
        )

        val props = OperationBuilder.taskToProperties(task)
        val reconstructed = OperationBuilder.taskFromProperties(testUuid, props)

        assertNotNull(reconstructed)
        assertEquals(task.description, reconstructed!!.description)
        assertEquals(task.status, reconstructed.status)
        assertEquals(task.priority, reconstructed.priority)
        assertEquals(task.project, reconstructed.project)
        assertEquals(task.due, reconstructed.due)
        assertEquals(task.wait, reconstructed.wait)
        assertEquals(task.recur, reconstructed.recur)
        assertEquals(task.getTagList().sorted(), reconstructed.getTagList().sorted())
        assertEquals(task.getDependencyList(), reconstructed.getDependencyList())
    }

    @Test
    fun `taskToProperties converts timestamps to seconds`() {
        val task = Task(
            uuid = testUuid,
            description = "Test",
            entry = 1693329505000L,
            modified = 1693329505000L,
            due = 1700000000000L
        )

        val props = OperationBuilder.taskToProperties(task)

        assertEquals("1693329505", props["entry"])
        assertEquals("1693329505", props["modified"])
        assertEquals("1700000000", props["due"])
    }

    @Test
    fun `taskToProperties omits null optional fields`() {
        val task = Task(
            uuid = testUuid,
            description = "Minimal",
            entry = testEntry,
            modified = testModified
        )

        val props = OperationBuilder.taskToProperties(task)

        assertFalse(props.containsKey("project"))
        assertFalse(props.containsKey("due"))
        assertFalse(props.containsKey("wait"))
        assertFalse(props.containsKey("scheduled"))
        assertFalse(props.containsKey("recur"))
    }

    @Test
    fun `taskToProperties omits priority when NONE`() {
        val task = Task(
            uuid = testUuid,
            description = "No priority",
            priority = TaskPriority.NONE,
            entry = testEntry,
            modified = testModified
        )

        val props = OperationBuilder.taskToProperties(task)

        assertFalse("Priority NONE should not be in properties", props.containsKey("priority"))
    }
}
