package com.tasksquire.android.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Tests the HTTP sync protocol using MockWebServer.
 *
 * These tests verify:
 * - Correct HTTP methods, paths, headers, and content types
 * - Encryption envelope round-trip through HTTP
 * - Version chain walking (multiple GetChildVersion calls)
 * - 409 conflict handling
 * - 410 Gone handling
 * - Snapshot upload/download
 * - X-Snapshot-Request header parsing
 */
class SyncProtocolTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TaskChampionSyncClient
    private val clientId = UUID.randomUUID().toString()
    private val secret = "test-secret"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = SyncServerConfig(
            serverUrl = server.url("/").toString().trimEnd('/'),
            clientId = clientId,
            encryptionSecret = secret
        )
        client = TaskChampionSyncClient(config)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- GetChildVersion (getOperationsSince) ---

    @Test
    fun `getOperationsSince sends correct request`() {
        val parentVersion = UUID.randomUUID().toString()
        val newVersion = UUID.randomUUID().toString()

        // Prepare encrypted response body
        val cryptor = TaskChampionCryptor(clientId, secret)
        val ops = listOf(Operation.Create("56e0be07-c61f-494c-a54c-bdcfdd52d2a7"))
        val payload = TaskChampionCodec.encodeHistorySegment(ops)
        val encrypted = cryptor.seal(parentVersion, payload)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", newVersion)
                .setHeader("Content-Type", "application/vnd.taskchampion.history-segment")
                .setBody(okio.Buffer().write(encrypted))
        )

        val result = runBlocking { client.getOperationsSince(parentVersion) }

        assertNotNull(result)
        assertEquals(newVersion, result!!.first)
        assertEquals(1, result!!.second.size)
        assertTrue(result!!.second[0] is Operation.Create)

        // Verify request
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/client/get-child-version/$parentVersion", request.path)
        assertEquals(clientId, request.getHeader("X-Client-Id"))
    }

    @Test
    fun `getOperationsSince returns null on 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runBlocking { client.getOperationsSince(UUID.randomUUID().toString()) }
        assertNull(result)
    }

    @Test
    fun `getOperationsSince throws SyncVersionNotFoundException on 410`() {
        server.enqueue(MockResponse().setResponseCode(410))

        try {
            runBlocking { client.getOperationsSince(UUID.randomUUID().toString()) }
            fail("Should have thrown SyncVersionNotFoundException")
        } catch (e: SyncVersionNotFoundException) {
            // Expected
        }
    }

    // --- AddVersion (uploadOperations) ---

    @Test
    fun `uploadOperations sends correct request`() {
        val parentVersion = UUID.randomUUID().toString()
        val newVersion = UUID.randomUUID().toString()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", newVersion)
        )

        val ops = listOf(
            Operation.Create("56e0be07-c61f-494c-a54c-bdcfdd52d2a7"),
            Operation.Update("56e0be07-c61f-494c-a54c-bdcfdd52d2a7", "description", "Test", System.currentTimeMillis())
        )

        val result = runBlocking { client.uploadOperations(parentVersion, ops) }

        assertNotNull(result)
        assertEquals(newVersion, result!!.newVersionId)
        assertNull(result!!.snapshotUrgency)

        // Verify request
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/client/add-version/$parentVersion", request.path)
        assertEquals(clientId, request.getHeader("X-Client-Id"))
        assertEquals("application/vnd.taskchampion.history-segment", request.getHeader("Content-Type"))

        // Verify we can decrypt the body
        val cryptor = TaskChampionCryptor(clientId, secret)
        val decrypted = cryptor.unseal(parentVersion, request.body.readByteArray())
        val decoded = TaskChampionCodec.decodeHistorySegment(decrypted)
        assertEquals(2, decoded.size)
        assertTrue(decoded[0] is Operation.Create)
        assertTrue(decoded[1] is Operation.Update)
    }

    @Test
    fun `uploadOperations throws SyncConflictException on 409`() {
        val expectedParent = UUID.randomUUID().toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("X-Parent-Version-Id", expectedParent)
        )

        val ops = listOf(Operation.Create(UUID.randomUUID().toString()))
        try {
            runBlocking { client.uploadOperations(UUID.randomUUID().toString(), ops) }
            fail("Should have thrown SyncConflictException")
        } catch (e: SyncConflictException) {
            assertEquals(expectedParent, e.expectedParentVersionId)
        }
    }

    @Test
    fun `uploadOperations SyncConflictException with missing header`() {
        server.enqueue(MockResponse().setResponseCode(409))

        val ops = listOf(Operation.Create(UUID.randomUUID().toString()))
        try {
            runBlocking { client.uploadOperations(UUID.randomUUID().toString(), ops) }
            fail("Should have thrown SyncConflictException")
        } catch (e: SyncConflictException) {
            assertNull("Missing header should give null expectedParentVersionId", e.expectedParentVersionId)
        }
    }

    @Test
    fun `uploadOperations parses X-Snapshot-Request urgency high`() {
        val newVersion = UUID.randomUUID().toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", newVersion)
                .setHeader("X-Snapshot-Request", "urgency=high")
        )

        val ops = listOf(Operation.Create(UUID.randomUUID().toString()))
        val result = runBlocking { client.uploadOperations(UUID.randomUUID().toString(), ops) }

        assertNotNull(result)
        assertEquals("high", result!!.snapshotUrgency)
    }

    @Test
    fun `uploadOperations parses X-Snapshot-Request urgency low`() {
        val newVersion = UUID.randomUUID().toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", newVersion)
                .setHeader("X-Snapshot-Request", "urgency=low")
        )

        val ops = listOf(Operation.Create(UUID.randomUUID().toString()))
        val result = runBlocking { client.uploadOperations(UUID.randomUUID().toString(), ops) }

        assertNotNull(result)
        assertEquals("low", result!!.snapshotUrgency)
    }

    @Test
    fun `uploadOperations returns null urgency when header absent`() {
        val newVersion = UUID.randomUUID().toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", newVersion)
        )

        val ops = listOf(Operation.Create(UUID.randomUUID().toString()))
        val result = runBlocking { client.uploadOperations(UUID.randomUUID().toString(), ops) }

        assertNotNull(result)
        assertNull(result!!.snapshotUrgency)
    }

    // --- Snapshot endpoints ---

    @Test
    fun `fetchSnapshot returns null on 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runBlocking { client.fetchSnapshot() }
        assertNull(result)
    }

    @Test
    fun `fetchSnapshot decrypts and parses snapshot`() {
        val snapshotVersion = UUID.randomUUID().toString()
        val cryptor = TaskChampionCryptor(clientId, secret)

        val snapshot = Snapshot(
            versionId = snapshotVersion,
            tasks = mapOf(
                "56e0be07-c61f-494c-a54c-bdcfdd52d2a7" to mapOf(
                    "description" to "Test task",
                    "status" to "pending"
                )
            )
        )
        val encoded = TaskChampionCodec.encodeSnapshot(snapshot)
        val encrypted = cryptor.seal(snapshotVersion, encoded)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", snapshotVersion)
                .setHeader("Content-Type", "application/vnd.taskchampion.snapshot")
                .setBody(okio.Buffer().write(encrypted))
        )

        val result = runBlocking { client.fetchSnapshot() }

        assertNotNull(result)
        assertEquals(snapshotVersion, result!!.versionId)
        assertEquals(1, result!!.tasks.size)
        assertEquals("Test task", result!!.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("description"))

        // Verify request path
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/client/snapshot", request.path)
    }

    @Test
    fun `uploadSnapshot sends correct request`() {
        val snapshotVersion = UUID.randomUUID().toString()
        server.enqueue(MockResponse().setResponseCode(200))

        val snapshot = Snapshot(
            versionId = snapshotVersion,
            tasks = mapOf(
                "56e0be07-c61f-494c-a54c-bdcfdd52d2a7" to mapOf(
                    "description" to "Snapshot task",
                    "status" to "completed"
                )
            )
        )

        runBlocking { client.uploadSnapshot(snapshot) }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/client/add-snapshot/$snapshotVersion", request.path)
        assertEquals(clientId, request.getHeader("X-Client-Id"))
        assertEquals("application/vnd.taskchampion.snapshot", request.getHeader("Content-Type"))

        // Verify we can decrypt the body
        val cryptor = TaskChampionCryptor(clientId, secret)
        val decrypted = cryptor.unseal(snapshotVersion, request.body.readByteArray())
        val decoded = TaskChampionCodec.decodeSnapshot(snapshotVersion, decrypted)
        assertEquals(1, decoded.tasks.size)
        assertEquals("Snapshot task", decoded.tasks["56e0be07-c61f-494c-a54c-bdcfdd52d2a7"]?.get("description"))
    }

    // --- Version chain walking simulation ---

    @Test
    fun `version chain walking fetches multiple versions`() {
        val version0 = UUID.randomUUID().toString()
        val version1 = UUID.randomUUID().toString()
        val version2 = UUID.randomUUID().toString()

        val cryptor = TaskChampionCryptor(clientId, secret)

        // First call: returns version 1
        val ops1 = listOf(Operation.Create("aaaaaaaa-bbbb-cccc-dddd-111111111111"))
        val enc1 = cryptor.seal(version0, TaskChampionCodec.encodeHistorySegment(ops1))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", version1)
                .setHeader("Content-Type", "application/vnd.taskchampion.history-segment")
                .setBody(okio.Buffer().write(enc1))
        )

        // Second call: returns version 2
        val ops2 = listOf(
            Operation.Update("aaaaaaaa-bbbb-cccc-dddd-111111111111", "description", "Updated", System.currentTimeMillis())
        )
        val enc2 = cryptor.seal(version1, TaskChampionCodec.encodeHistorySegment(ops2))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Version-Id", version2)
                .setHeader("Content-Type", "application/vnd.taskchampion.history-segment")
                .setBody(okio.Buffer().write(enc2))
        )

        // Third call: 404 (up to date)
        server.enqueue(MockResponse().setResponseCode(404))

        // Simulate the pull loop
        var currentVersion = version0
        val allOps = mutableListOf<Operation>()

        while (true) {
            val result = runBlocking { client.getOperationsSince(currentVersion) } ?: break
            allOps.addAll(result.second)
            currentVersion = result.first
        }

        assertEquals(version2, currentVersion)
        assertEquals(2, allOps.size)
        assertTrue(allOps[0] is Operation.Create)
        assertTrue(allOps[1] is Operation.Update)

        // Verify 3 requests were made
        assertEquals(3, server.requestCount)
    }

    // --- Error handling ---

    @Test
    fun `uploadOperations throws SyncVersionNotFoundException on 500`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Unknown version"))

        try {
            val ops = listOf(Operation.Create(UUID.randomUUID().toString()))
            runBlocking { client.uploadOperations(UUID.randomUUID().toString(), ops) }
            fail("Should have thrown SyncVersionNotFoundException")
        } catch (e: SyncVersionNotFoundException) {
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun `getOperationsSince throws SyncException on unexpected status`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

        try {
            runBlocking { client.getOperationsSince(UUID.randomUUID().toString()) }
            fail("Should have thrown SyncException")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    // Helper to run suspend functions in tests
    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
