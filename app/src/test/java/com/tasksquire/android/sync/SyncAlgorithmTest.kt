package com.tasksquire.android.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the sync algorithm logic — specifically the OT rebase behavior
 * that occurs during the pull phase of sync.
 *
 * These tests verify the algorithm described in Section 9 of the spec:
 * - Pulling multiple server versions and rebasing local ops
 * - Server operations being applied after transformation
 * - Local operations surviving transformation for push
 * - Convergence: applying server ops then local' produces same state as
 *   applying local ops then server'
 */
class SyncAlgorithmTest {

    private val uuid1 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val uuid2 = "11111111-2222-3333-4444-555555555555"
    private val ts1 = 1705312200000L
    private val ts2 = 1705312300000L
    private val ts3 = 1705312400000L

    /**
     * Simulate applying operations to a task map (simplified version of the
     * server-side or client-side state).
     */
    private fun applyOps(
        tasks: MutableMap<String, MutableMap<String, String?>>,
        ops: List<Operation>
    ) {
        for (op in ops) {
            when (op) {
                is Operation.Create -> {
                    if (!tasks.containsKey(op.uuid)) {
                        tasks[op.uuid] = mutableMapOf()
                    }
                }
                is Operation.Delete -> {
                    tasks.remove(op.uuid)
                }
                is Operation.Update -> {
                    val task = tasks[op.uuid] ?: return
                    if (op.value != null) {
                        task[op.property] = op.value
                    } else {
                        task.remove(op.property)
                    }
                }
            }
        }
    }

    @Test
    fun `convergence - no conflict scenario`() {
        // Server creates task A, local creates task B
        val serverOps = listOf(
            Operation.Create(uuid1),
            Operation.Update(uuid1, "description", "Server task", ts1)
        )
        val localOps = listOf(
            Operation.Create(uuid2),
            Operation.Update(uuid2, "description", "Local task", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        // Path 1: apply server ops, then transformed local ops
        val state1 = mutableMapOf<String, MutableMap<String, String?>>()
        applyOps(state1, serverOps)
        applyOps(state1, transformedLocal)

        // Path 2: apply local ops, then transformed server ops
        val state2 = mutableMapOf<String, MutableMap<String, String?>>()
        applyOps(state2, localOps)
        applyOps(state2, transformedServer)

        assertEquals("Both paths should converge to same state", state1, state2)
        assertEquals(2, state1.size) // Both tasks should exist
    }

    @Test
    fun `convergence - concurrent update to same property, server wins`() {
        // Both update description of the same task
        val initialState = mutableMapOf(
            uuid1 to mutableMapOf<String, String?>("description" to "Original")
        )

        val serverOps = listOf(
            Operation.Update(uuid1, "description", "Server version", ts2) // later
        )
        val localOps = listOf(
            Operation.Update(uuid1, "description", "Local version", ts1) // earlier
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        // Path 1: server then local'
        val state1 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state1, serverOps)
        applyOps(state1, transformedLocal)

        // Path 2: local then server'
        val state2 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state2, localOps)
        applyOps(state2, transformedServer)

        assertEquals(state1, state2)
        assertEquals("Server version", state1[uuid1]?.get("description"))
    }

    @Test
    fun `convergence - concurrent update to same property, local wins`() {
        val initialState = mutableMapOf(
            uuid1 to mutableMapOf<String, String?>("description" to "Original")
        )

        val serverOps = listOf(
            Operation.Update(uuid1, "description", "Server version", ts1) // earlier
        )
        val localOps = listOf(
            Operation.Update(uuid1, "description", "Local version", ts2) // later
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        val state1 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state1, serverOps)
        applyOps(state1, transformedLocal)

        val state2 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state2, localOps)
        applyOps(state2, transformedServer)

        assertEquals(state1, state2)
        assertEquals("Local version", state1[uuid1]?.get("description"))
    }

    @Test
    fun `convergence - concurrent updates to different properties`() {
        val initialState = mutableMapOf(
            uuid1 to mutableMapOf<String, String?>(
                "description" to "Original",
                "status" to "pending"
            )
        )

        val serverOps = listOf(
            Operation.Update(uuid1, "description", "Server desc", ts1)
        )
        val localOps = listOf(
            Operation.Update(uuid1, "status", "completed", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        val state1 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state1, serverOps)
        applyOps(state1, transformedLocal)

        val state2 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state2, localOps)
        applyOps(state2, transformedServer)

        assertEquals(state1, state2)
        assertEquals("Server desc", state1[uuid1]?.get("description"))
        assertEquals("completed", state1[uuid1]?.get("status"))
    }

    @Test
    fun `convergence - server deletes task that local modifies`() {
        val initialState = mutableMapOf(
            uuid1 to mutableMapOf<String, String?>("description" to "Existing")
        )

        val serverOps = listOf(Operation.Delete(uuid1))
        val localOps = listOf(
            Operation.Update(uuid1, "description", "Modified", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        val state1 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state1, serverOps)
        applyOps(state1, transformedLocal)

        val state2 = initialState.map { (k, v) -> k to v.toMutableMap() }.toMap().toMutableMap()
        applyOps(state2, localOps)
        applyOps(state2, transformedServer)

        assertEquals(state1, state2)
        assertFalse("Task should be deleted", state1.containsKey(uuid1))
    }

    @Test
    fun `multi-version rebase simulates version chain`() {
        // Simulate pulling 3 server versions while having local changes
        // Version N+1: server sets description
        // Version N+2: server sets priority
        // Version N+3: server sets tag
        // Local: sets status
        val serverVersions = listOf(
            listOf(Operation.Update(uuid1, "description", "V1 desc", ts1)),
            listOf(Operation.Update(uuid1, "priority", "H", ts2)),
            listOf(Operation.Update(uuid1, "tag_urgent", "", ts3))
        )

        var localOps = listOf<Operation>(
            Operation.Update(uuid1, "status", "completed", ts1)
        )

        // Simulate the pull loop: for each server version, rebase local ops
        val allTransformedServerOps = mutableListOf<Operation>()
        for (serverOps in serverVersions) {
            val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)
            allTransformedServerOps.addAll(transformedServer)
            localOps = transformedLocal
        }

        // All server ops should survive (different properties)
        assertEquals(3, allTransformedServerOps.size)
        // Local op should survive
        assertEquals(1, localOps.size)
        assertEquals("status", (localOps[0] as Operation.Update).property)
    }

    @Test
    fun `rebase handles multiple operations per version`() {
        // Server version has Create + multiple Updates
        val serverOps = listOf(
            Operation.Create(uuid1),
            Operation.Update(uuid1, "description", "New task", ts1),
            Operation.Update(uuid1, "status", "pending", ts1),
            Operation.Update(uuid1, "tag_work", "", ts1)
        )

        // Local also has an update to a different task
        val localOps = listOf(
            Operation.Update(uuid2, "description", "Local edit", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        // No conflicts (different UUIDs), all should pass through
        assertEquals(4, transformedServer.size)
        assertEquals(1, transformedLocal.size)
    }

    @Test
    fun `timestamp parsing for conflict resolution`() {
        // Verify that timestamp comparison works correctly for RFC3339 dates
        // converted to epoch millis
        val ts2024Jan = 1705312200000L // 2024-01-15T10:30:00Z
        val ts2024Feb = 1707904200000L // 2024-02-14T10:30:00Z

        val serverOp = Operation.Update(uuid1, "description", "Feb edit", ts2024Feb)
        val localOp = Operation.Update(uuid1, "description", "Jan edit", ts2024Jan)

        val result = OperationalTransform.transform(serverOp, localOp)

        // Server has later timestamp, server should win
        assertEquals(serverOp, result.serverOp)
        assertNull("Local should lose (earlier timestamp)", result.localOp)
    }
}
