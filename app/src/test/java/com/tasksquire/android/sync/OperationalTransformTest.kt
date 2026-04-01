package com.tasksquire.android.sync

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Operational Transform (OT) implementation.
 *
 * Covers all operation-pair combinations from the spec (Section 5.4):
 * - Create/Create, Create/Delete, Create/Update
 * - Delete/Create, Delete/Delete, Delete/Update
 * - Update/Create, Update/Delete, Update/Update (same prop, different prop)
 * - Timestamp-based conflict resolution
 * - Different UUID cases (no conflict)
 * - Full rebase scenarios
 */
class OperationalTransformTest {

    private val uuid1 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val uuid2 = "11111111-2222-3333-4444-555555555555"
    private val ts1 = 1705312200000L // earlier
    private val ts2 = 1705312300000L // later

    // --- Different UUID: both pass through unchanged ---

    @Test
    fun `different UUIDs - both pass through unchanged`() {
        val server = Operation.Update(uuid1, "description", "Server edit", ts1)
        val local = Operation.Update(uuid2, "description", "Local edit", ts2)

        val result = OperationalTransform.transform(server, local)

        assertEquals(server, result.serverOp)
        assertEquals(local, result.localOp)
    }

    @Test
    fun `different UUIDs - Create vs Update`() {
        val server = Operation.Create(uuid1)
        val local = Operation.Update(uuid2, "status", "completed", ts1)

        val result = OperationalTransform.transform(server, local)

        assertEquals(server, result.serverOp)
        assertEquals(local, result.localOp)
    }

    // --- Same UUID: Create/Create ---

    @Test
    fun `Create vs Create same UUID - both become no-ops`() {
        val server = Operation.Create(uuid1)
        val local = Operation.Create(uuid1)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server Create should become no-op", result.serverOp)
        assertNull("Local Create should become no-op", result.localOp)
    }

    // --- Same UUID: Create/Delete ---

    @Test
    fun `Create vs Delete same UUID - prefer Create over Delete`() {
        val server = Operation.Create(uuid1)
        val local = Operation.Delete(uuid1)

        val result = OperationalTransform.transform(server, local)

        assertEquals("Server Create should survive (Create wins over Delete)", server, result.serverOp)
        assertNull("Local Delete should become no-op", result.localOp)
    }

    // --- Same UUID: Create/Update ---

    @Test
    fun `Create vs Update same UUID - server no-op, local passes`() {
        val server = Operation.Create(uuid1)
        val local = Operation.Update(uuid1, "description", "Edited", ts1)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server Create should become no-op", result.serverOp)
        assertEquals(local, result.localOp)
    }

    // --- Same UUID: Delete/Create ---

    @Test
    fun `Delete vs Create same UUID - prefer Create over Delete`() {
        val server = Operation.Delete(uuid1)
        val local = Operation.Create(uuid1)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server Delete should become no-op (Create wins over Delete)", result.serverOp)
        assertEquals("Local Create should survive", local, result.localOp)
    }

    // --- Same UUID: Delete/Delete ---

    @Test
    fun `Delete vs Delete same UUID - both become no-ops`() {
        val server = Operation.Delete(uuid1)
        val local = Operation.Delete(uuid1)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server Delete should become no-op", result.serverOp)
        assertNull("Local Delete should become no-op", result.localOp)
    }

    // --- Same UUID: Delete/Update ---

    @Test
    fun `Delete vs Update same UUID - server passes, local no-op`() {
        val server = Operation.Delete(uuid1)
        val local = Operation.Update(uuid1, "description", "Won't matter", ts1)

        val result = OperationalTransform.transform(server, local)

        assertEquals(server, result.serverOp)
        assertNull("Local Update should become no-op (task deleted)", result.localOp)
    }

    // --- Same UUID: Update/Create ---

    @Test
    fun `Update vs Create same UUID - server passes, local no-op`() {
        val server = Operation.Update(uuid1, "description", "Server edit", ts1)
        val local = Operation.Create(uuid1)

        val result = OperationalTransform.transform(server, local)

        assertEquals(server, result.serverOp)
        assertNull("Local Create should become no-op", result.localOp)
    }

    // --- Same UUID: Update/Delete ---

    @Test
    fun `Update vs Delete same UUID - server no-op, local passes`() {
        val server = Operation.Update(uuid1, "description", "Server edit", ts1)
        val local = Operation.Delete(uuid1)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server Update should become no-op", result.serverOp)
        assertEquals(local, result.localOp)
    }

    // --- Same UUID: Update/Update different properties ---

    @Test
    fun `Update vs Update different properties - both pass through`() {
        val server = Operation.Update(uuid1, "description", "Server desc", ts1)
        val local = Operation.Update(uuid1, "status", "completed", ts1)

        val result = OperationalTransform.transform(server, local)

        assertEquals(server, result.serverOp)
        assertEquals(local, result.localOp)
    }

    // --- Same UUID: Update/Update same property, server wins (later timestamp) ---

    @Test
    fun `Update vs Update same property - server has later timestamp, server wins`() {
        val server = Operation.Update(uuid1, "description", "Server desc", ts2)
        val local = Operation.Update(uuid1, "description", "Local desc", ts1)

        val result = OperationalTransform.transform(server, local)

        assertEquals(server, result.serverOp)
        assertNull("Local should become no-op (server has later timestamp)", result.localOp)
    }

    // --- Same UUID: Update/Update same property, local wins (later timestamp) ---

    @Test
    fun `Update vs Update same property - local has later timestamp, local wins`() {
        val server = Operation.Update(uuid1, "description", "Server desc", ts1)
        val local = Operation.Update(uuid1, "description", "Local desc", ts2)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server should become no-op (local has later timestamp)", result.serverOp)
        assertEquals(local, result.localOp)
    }

    // --- Same UUID: Update/Update same property, equal timestamps - server wins ---

    @Test
    fun `Update vs Update same property - equal timestamps, server wins`() {
        val server = Operation.Update(uuid1, "description", "Server desc", ts1)
        val local = Operation.Update(uuid1, "description", "Local desc", ts1)

        val result = OperationalTransform.transform(server, local)

        assertEquals("Server should win on timestamp tie", server, result.serverOp)
        assertNull("Local should become no-op on timestamp tie", result.localOp)
    }

    // --- Same UUID: Update/Update same property, same value ---

    @Test
    fun `Update vs Update same property same value - both become no-ops`() {
        val server = Operation.Update(uuid1, "description", "Same text", ts1)
        val local = Operation.Update(uuid1, "description", "Same text", ts2)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server should become no-op (same value)", result.serverOp)
        assertNull("Local should become no-op (same value)", result.localOp)
    }

    @Test
    fun `Update vs Update same property both null value - both become no-ops`() {
        val server = Operation.Update(uuid1, "start", null, ts1)
        val local = Operation.Update(uuid1, "start", null, ts2)

        val result = OperationalTransform.transform(server, local)

        assertNull("Server should become no-op (same null value)", result.serverOp)
        assertNull("Local should become no-op (same null value)", result.localOp)
    }

    // --- Same UUID: Update/Update same property with null values ---

    @Test
    fun `Update vs Update same property - server sets null, local sets value`() {
        val server = Operation.Update(uuid1, "start", null, ts2)
        val local = Operation.Update(uuid1, "start", "1705312200", ts1)

        val result = OperationalTransform.transform(server, local)

        // Server has later timestamp, server wins
        assertEquals(server, result.serverOp)
        assertNull(result.localOp)
    }

    // --- Rebase: full sequence transformation ---

    @Test
    fun `rebase - no conflicts, all ops pass through`() {
        val serverOps = listOf(
            Operation.Create(uuid1),
            Operation.Update(uuid1, "description", "Server task", ts1)
        )
        val localOps = listOf(
            Operation.Create(uuid2),
            Operation.Update(uuid2, "description", "Local task", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        assertEquals(2, transformedServer.size)
        assertEquals(2, transformedLocal.size)
    }

    @Test
    fun `rebase - server deletes task that local updates`() {
        val serverOps = listOf(Operation.Delete(uuid1))
        val localOps = listOf(
            Operation.Update(uuid1, "description", "Updated", ts1),
            Operation.Update(uuid1, "status", "completed", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        assertEquals("Server delete should pass through", 1, transformedServer.size)
        assertTrue(transformedServer[0] is Operation.Delete)
        assertEquals("Local updates should be eliminated", 0, transformedLocal.size)
    }

    @Test
    fun `rebase - concurrent updates to different properties`() {
        val serverOps = listOf(
            Operation.Update(uuid1, "description", "Server desc", ts1)
        )
        val localOps = listOf(
            Operation.Update(uuid1, "priority", "H", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        assertEquals(1, transformedServer.size)
        assertEquals(1, transformedLocal.size)
        assertEquals("description", (transformedServer[0] as Operation.Update).property)
        assertEquals("priority", (transformedLocal[0] as Operation.Update).property)
    }

    @Test
    fun `rebase - concurrent updates to same property, server wins`() {
        val serverOps = listOf(
            Operation.Update(uuid1, "description", "Server desc", ts2)
        )
        val localOps = listOf(
            Operation.Update(uuid1, "description", "Local desc", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        assertEquals(1, transformedServer.size)
        assertEquals("Server desc", (transformedServer[0] as Operation.Update).value)
        assertEquals("Local op should be eliminated", 0, transformedLocal.size)
    }

    @Test
    fun `rebase - concurrent updates to same property, local wins`() {
        val serverOps = listOf(
            Operation.Update(uuid1, "description", "Server desc", ts1)
        )
        val localOps = listOf(
            Operation.Update(uuid1, "description", "Local desc", ts2)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        assertEquals("Server op should be eliminated", 0, transformedServer.size)
        assertEquals(1, transformedLocal.size)
        assertEquals("Local desc", (transformedLocal[0] as Operation.Update).value)
    }

    @Test
    fun `rebase - multiple server versions against local ops`() {
        // Simulate: server has 2 updates, local has 1 update to same task
        val serverOps = listOf(
            Operation.Update(uuid1, "description", "Server desc", ts1),
            Operation.Update(uuid1, "status", "completed", ts2)
        )
        val localOps = listOf(
            Operation.Update(uuid1, "priority", "H", ts1)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        // All different properties, all should pass through
        assertEquals(2, transformedServer.size)
        assertEquals(1, transformedLocal.size)
    }

    @Test
    fun `rebase - empty server ops`() {
        val serverOps = emptyList<Operation>()
        val localOps = listOf(Operation.Create(uuid1))

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        assertEquals(0, transformedServer.size)
        assertEquals(1, transformedLocal.size)
    }

    @Test
    fun `rebase - empty local ops`() {
        val serverOps = listOf(Operation.Create(uuid1))
        val localOps = emptyList<Operation>()

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        assertEquals(1, transformedServer.size)
        assertEquals(0, transformedLocal.size)
    }

    @Test
    fun `rebase - complex scenario with creates, updates, and deletes`() {
        // Server: creates task A, updates task B
        // Local: creates task A (conflict!), deletes task B
        val serverOps = listOf(
            Operation.Create(uuid1),
            Operation.Update(uuid2, "description", "Server updated B", ts1)
        )
        val localOps = listOf(
            Operation.Create(uuid1),
            Operation.Delete(uuid2)
        )

        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps, localOps)

        // Server Create(uuid1) vs Local Create(uuid1) => both no-op
        // Server Update(uuid2) vs Local Delete(uuid2) => server Update no-op (Delete wins), local Delete passes
        assertEquals("Server ops should be eliminated", 0, transformedServer.size)
        assertEquals("Local Delete(uuid2) should survive", 1, transformedLocal.size)
        assertTrue(transformedLocal[0] is Operation.Delete)
    }
}
