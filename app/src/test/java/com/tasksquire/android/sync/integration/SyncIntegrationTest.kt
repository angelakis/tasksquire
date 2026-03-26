package com.tasksquire.android.sync.integration

import com.tasksquire.android.sync.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Integration tests that run against a REAL taskchampion-sync-server instance.
 *
 * These tests are SKIPPED unless the environment variable SYNC_TEST_SERVER_URL is set.
 *
 * To run:
 *   1. Start the sync server:
 *      docker compose -f docker-compose.test.yml up -d
 *
 *   2. Run with the server URL:
 *      SYNC_TEST_SERVER_URL=http://localhost:8080 ./gradlew test
 *
 * Or use the helper script:
 *   ./scripts/test-integration.sh
 *
 * These tests exercise the FULL sync protocol:
 *   - Encryption (PBKDF2 key derivation + ChaCha20-Poly1305)
 *   - HTTP transport (correct headers, content types, paths)
 *   - Version chain (create -> fetch -> verify)
 *   - Snapshots (upload and download)
 *   - Multi-replica sync (two clients sharing the same client_id)
 *   - Conflict detection and resolution via OT
 */
class SyncIntegrationTest {

    private lateinit var serverUrl: String
    private lateinit var clientId: String
    private lateinit var secret: String

    @Before
    fun setUp() {
        // Auto-detect: use env var if set, otherwise try localhost:8080 (CI default).
        // Skip only if the server is genuinely unreachable.
        val url = System.getenv("SYNC_TEST_SERVER_URL")
            ?: "http://localhost:8080"
        val reachable = try {
            val conn = java.net.URL("$url/").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            conn.responseCode // any response means server is up
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue(
            "Skipping integration test: no sync server reachable at $url " +
                "(start one with: docker compose -f docker-compose.test.yml up -d)",
            reachable
        )
        serverUrl = url
        clientId = UUID.randomUUID().toString()
        secret = "integration-test-secret-${UUID.randomUUID()}"
    }

    private fun makeClient(
        clientId: String = this.clientId,
        secret: String = this.secret
    ): TaskChampionSyncClient {
        return TaskChampionSyncClient(
            SyncServerConfig(serverUrl, clientId, secret)
        )
    }

    // --- Basic connectivity ---

    @Test
    fun `fresh client gets 404 for nil version`() {
        val client = makeClient()
        val nilVersion = "00000000-0000-0000-0000-000000000000"

        val result = runBlocking { client.getOperationsSince(nilVersion) }
        assertNull("Fresh server should return 404 (no versions yet)", result)
    }

    @Test
    fun `fresh client gets no snapshot`() {
        val client = makeClient()

        val result = runBlocking { client.fetchSnapshot() }
        assertNull("Fresh server should have no snapshot", result)
    }

    // --- Single-client round trip ---

    @Test
    fun `create task and fetch it back`() {
        val client = makeClient()
        val nilVersion = "00000000-0000-0000-0000-000000000000"
        val taskUuid = UUID.randomUUID().toString()

        // Upload a version with Create + Update operations
        val ops = listOf(
            Operation.Create(taskUuid),
            Operation.Update(taskUuid, "description", "Integration test task", System.currentTimeMillis()),
            Operation.Update(taskUuid, "status", "pending", System.currentTimeMillis()),
            Operation.Update(taskUuid, "tag_test", "", System.currentTimeMillis())
        )

        val uploadResult = runBlocking { client.uploadOperations(nilVersion, ops) }
        assertNotNull("Upload should succeed", uploadResult)
        val versionId = uploadResult!!.newVersionId

        // Fetch the version back
        val fetchResult = runBlocking { client.getOperationsSince(nilVersion) }
        assertNotNull("Should get version back", fetchResult)
        assertEquals(versionId, fetchResult!!.first)
        assertEquals(4, fetchResult!!.second.size)

        // Verify operations match what we sent
        val create = fetchResult!!.second[0] as Operation.Create
        assertEquals(taskUuid, create.uuid)

        val descUpdate = fetchResult!!.second[1] as Operation.Update
        assertEquals(taskUuid, descUpdate.uuid)
        assertEquals("description", descUpdate.property)
        assertEquals("Integration test task", descUpdate.value)

        // No more versions after this
        val nextResult = runBlocking { client.getOperationsSince(versionId) }
        assertNull("Should be up to date", nextResult)
    }

    @Test
    fun `upload and download snapshot`() {
        val client = makeClient()
        val nilVersion = "00000000-0000-0000-0000-000000000000"
        val taskUuid = UUID.randomUUID().toString()

        // First push a version so we have a valid version ID
        val ops = listOf(
            Operation.Create(taskUuid),
            Operation.Update(taskUuid, "description", "Snapshot test", System.currentTimeMillis())
        )
        val uploadResult = runBlocking { client.uploadOperations(nilVersion, ops) }!!
        val versionId = uploadResult.newVersionId

        // Upload a snapshot at this version
        val snapshot = Snapshot(
            versionId = versionId,
            tasks = mapOf(
                taskUuid to mapOf(
                    "description" to "Snapshot test",
                    "status" to "pending",
                    "entry" to (System.currentTimeMillis() / 1000).toString()
                )
            )
        )
        runBlocking { client.uploadSnapshot(snapshot) }

        // Fetch the snapshot
        val fetched = runBlocking { client.fetchSnapshot() }
        assertNotNull("Should get snapshot back", fetched)
        assertEquals(1, fetched!!.tasks.size)
        assertEquals("Snapshot test", fetched!!.tasks[taskUuid]?.get("description"))
        assertEquals("pending", fetched!!.tasks[taskUuid]?.get("status"))
    }

    // --- Multi-version chain ---

    @Test
    fun `version chain walking across multiple pushes`() {
        val client = makeClient()
        val nilVersion = "00000000-0000-0000-0000-000000000000"

        // Push 3 versions sequentially
        var parentVersion = nilVersion
        val taskUuids = mutableListOf<String>()
        for (i in 1..3) {
            val taskUuid = UUID.randomUUID().toString()
            taskUuids.add(taskUuid)
            val ops = listOf(
                Operation.Create(taskUuid),
                Operation.Update(taskUuid, "description", "Task $i", System.currentTimeMillis())
            )
            val result = runBlocking { client.uploadOperations(parentVersion, ops) }!!
            parentVersion = result.newVersionId
        }

        // Walk the entire version chain from nil
        var currentVersion = nilVersion
        var totalOps = 0
        var versionsWalked = 0
        while (true) {
            val result = runBlocking { client.getOperationsSince(currentVersion) } ?: break
            totalOps += result.second.size
            currentVersion = result.first
            versionsWalked++
        }

        assertEquals("Should have walked 3 versions", 3, versionsWalked)
        assertEquals("Should have 6 total operations (2 per version)", 6, totalOps)
        assertEquals("Final version should match last push", parentVersion, currentVersion)
    }

    // --- Multi-replica sync ---

    @Test
    fun `two replicas sharing same client_id see each other's changes`() {
        val replicaA = makeClient()
        val replicaB = makeClient() // Same clientId and secret
        val nilVersion = "00000000-0000-0000-0000-000000000000"
        val taskUuid = UUID.randomUUID().toString()

        // Replica A creates a task
        val ops = listOf(
            Operation.Create(taskUuid),
            Operation.Update(taskUuid, "description", "Created by replica A", System.currentTimeMillis()),
            Operation.Update(taskUuid, "status", "pending", System.currentTimeMillis())
        )
        val resultA = runBlocking { replicaA.uploadOperations(nilVersion, ops) }!!

        // Replica B fetches and should see replica A's task
        val fetchResult = runBlocking { replicaB.getOperationsSince(nilVersion) }
        assertNotNull(fetchResult)
        assertEquals(resultA.newVersionId, fetchResult!!.first)
        assertEquals(3, fetchResult!!.second.size)

        val create = fetchResult!!.second[0] as Operation.Create
        assertEquals(taskUuid, create.uuid)
    }

    @Test
    fun `conflict detection returns 409 on concurrent push`() {
        val replicaA = makeClient()
        val replicaB = makeClient()
        val nilVersion = "00000000-0000-0000-0000-000000000000"

        // Replica A pushes a version
        val taskA = UUID.randomUUID().toString()
        val opsA = listOf(
            Operation.Create(taskA),
            Operation.Update(taskA, "description", "Task from A", System.currentTimeMillis())
        )
        val resultA = runBlocking { replicaA.uploadOperations(nilVersion, opsA) }!!

        // Replica B tries to push against the nil version (stale parent)
        val taskB = UUID.randomUUID().toString()
        val opsB = listOf(
            Operation.Create(taskB),
            Operation.Update(taskB, "description", "Task from B", System.currentTimeMillis())
        )

        // This should return null (409 conflict) because nilVersion is no longer the latest
        val resultB = runBlocking { replicaB.uploadOperations(nilVersion, opsB) }
        assertNull("Replica B should get 409 conflict when pushing against stale parent", resultB)

        // Replica B should be able to push after pulling replica A's version
        val fetchResult = runBlocking { replicaB.getOperationsSince(nilVersion) }!!
        val newParent = fetchResult.first

        val resultB2 = runBlocking { replicaB.uploadOperations(newParent, opsB) }
        assertNotNull("Replica B should succeed after pulling", resultB2)
    }

    // --- Encryption interop ---

    @Test
    fun `encryption is consistent across client instances`() {
        // Two clients with the same credentials should be able to decrypt each other's data
        val cryptor1 = TaskChampionCryptor(clientId, secret)
        val cryptor2 = TaskChampionCryptor(clientId, secret)
        val versionId = UUID.randomUUID().toString()

        val plaintext = """[{"Create":{"uuid":"56e0be07-c61f-494c-a54c-bdcfdd52d2a7"}}]""".toByteArray()

        val encrypted = cryptor1.seal(versionId, plaintext)
        val decrypted = cryptor2.unseal(versionId, encrypted)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `full OT sync scenario between two replicas`() {
        val replicaA = makeClient()
        val replicaB = makeClient()
        val nilVersion = "00000000-0000-0000-0000-000000000000"
        val taskUuid = UUID.randomUUID().toString()

        // Both replicas start by creating the same task (via snapshot or previous sync)
        // Replica A pushes Create + initial description
        val createOps = listOf(
            Operation.Create(taskUuid),
            Operation.Update(taskUuid, "description", "Shared task", System.currentTimeMillis()),
            Operation.Update(taskUuid, "status", "pending", System.currentTimeMillis())
        )
        val v1 = runBlocking { replicaA.uploadOperations(nilVersion, createOps) }!!.newVersionId

        // Both replicas pull to v1
        val _ = runBlocking { replicaB.getOperationsSince(nilVersion) }

        // Now both replicas make concurrent changes to DIFFERENT properties
        val now = System.currentTimeMillis()

        // Replica A changes description
        val opsA = listOf(
            Operation.Update(taskUuid, "description", "Updated by A", now)
        )
        val v2 = runBlocking { replicaA.uploadOperations(v1, opsA) }!!.newVersionId

        // Replica B tries to change status (against stale v1)
        val opsB = listOf(
            Operation.Update(taskUuid, "status", "completed", now)
        )
        val resultB = runBlocking { replicaB.uploadOperations(v1, opsB) }
        assertNull("Should get 409 since v1 is stale", resultB)

        // Replica B pulls v2 (replica A's changes)
        val serverOps = runBlocking { replicaB.getOperationsSince(v1) }!!
        assertEquals(v2, serverOps.first)

        // Replica B transforms its local ops against server ops via OT
        val (transformedServer, transformedLocal) = OperationalTransform.rebase(serverOps.second, opsB)

        // Different properties: both should survive transformation
        assertEquals(1, transformedServer.size)
        assertEquals(1, transformedLocal.size)
        assertEquals("description", (transformedServer[0] as Operation.Update).property)
        assertEquals("status", (transformedLocal[0] as Operation.Update).property)

        // Replica B pushes its transformed local ops
        val v3 = runBlocking { replicaB.uploadOperations(v2, transformedLocal) }
        assertNotNull("Replica B should succeed after OT rebase", v3)
    }

    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
