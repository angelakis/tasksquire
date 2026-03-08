package com.tasksquire.android.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for taskchampion-sync-server protocol.
 *
 * Endpoints:
 *   GET  /v1/client/snapshot                       - fetch current snapshot
 *   POST /v1/client/add-snapshot/{version_id}      - upload snapshot
 *   GET  /v1/client/get-child-version/{parent_version_id} - get child version
 *   POST /v1/client/add-version/{parent_version_id} - upload new operations
 */
class TaskChampionSyncClient(private val config: SyncServerConfig) {
    companion object {
        private const val HISTORY_SEGMENT_CONTENT_TYPE = "application/vnd.taskchampion.history-segment"
        private const val SNAPSHOT_CONTENT_TYPE = "application/vnd.taskchampion.snapshot"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String = config.serverUrl.trimEnd('/')
    private val cryptor = TaskChampionCryptor(config.clientId, config.encryptionSecret)
    private val historyContentType = HISTORY_SEGMENT_CONTENT_TYPE.toMediaType()
    private val snapshotContentType = SNAPSHOT_CONTENT_TYPE.toMediaType()

    private fun buildRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("X-Client-Id", config.clientId)
    }

    /**
     * Fetch the latest snapshot from the server.
     * Returns null if no snapshot exists yet (404).
     */
    suspend fun fetchSnapshot(): Snapshot? {
        val url = "$baseUrl/v1/client/snapshot"
        val request = buildRequest(url).get().build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Network error fetching snapshot: ${e.message}", e)
        }
        return response.use { resp ->
            when (resp.code) {
                200 -> {
                    val versionId = requiredUuidHeader(resp, "X-Version-Id")
                    requireContentType(resp, SNAPSHOT_CONTENT_TYPE, "fetching snapshot")
                    val body = resp.body?.bytes() ?: throw SyncException("Empty snapshot response")
                    val snapshotBytes = try {
                        cryptor.unseal(versionId, body)
                    } catch (e: Exception) {
                        throw SyncException("Failed to decrypt snapshot: ${e.message}", e)
                    }
                    try {
                        TaskChampionCodec.decodeSnapshot(versionId, snapshotBytes)
                    } catch (e: Exception) {
                        throw SyncException("Failed to decode snapshot (${snapshotBytes.size} bytes): ${e.message}", e)
                    }
                }
                404 -> null
                else -> throw SyncException(unexpectedStatusMessage(resp.code, "fetching snapshot", resp.body?.string()))
            }
        }
    }

    /**
     * Upload a new snapshot to the server.
     */
    suspend fun uploadSnapshot(snapshot: Snapshot) {
        val url = "$baseUrl/v1/client/add-snapshot/${snapshot.versionId}"
        val payload = TaskChampionCodec.encodeSnapshot(snapshot)
        val sealed = cryptor.seal(snapshot.versionId, payload)
        val requestBody = sealed.toRequestBody(snapshotContentType)
        val request = buildRequest(url)
            .addHeader("Content-Type", SNAPSHOT_CONTENT_TYPE)
            .post(requestBody)
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Network error uploading snapshot: ${e.message}", e)
        }
        response.use { resp ->
            if (resp.code !in 200..299) {
                throw SyncException(unexpectedStatusMessage(resp.code, "uploading snapshot", resp.body?.string()))
            }
        }
    }

    /**
     * Get child version for the given parent version.
     * Returns (newVersionId, operations) or null when no child exists (404).
     */
    suspend fun getOperationsSince(parentVersionId: String): Pair<String, List<Operation>>? {
        val url = "$baseUrl/v1/client/get-child-version/$parentVersionId"
        val request = buildRequest(url).get().build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Network error fetching operations: ${e.message}", e)
        }
        return response.use { resp ->
            when (resp.code) {
                200 -> {
                    val versionId = requiredUuidHeader(resp, "X-Version-Id")
                    requireContentType(resp, HISTORY_SEGMENT_CONTENT_TYPE, "fetching operations")
                    val body = resp.body?.bytes() ?: throw SyncException("Empty operations response")
                    val historySegment = try {
                        cryptor.unseal(parentVersionId, body)
                    } catch (e: Exception) {
                        throw SyncException("Failed to decrypt history segment for parent $parentVersionId: ${e.message}", e)
                    }
                    val operations = try {
                        TaskChampionCodec.decodeHistorySegment(historySegment)
                    } catch (e: Exception) {
                        throw SyncException("Failed to decode history segment (${historySegment.size} bytes): ${e.message}", e)
                    }
                    versionId to operations
                }
                404 -> null // Client is up-to-date; no new versions exist
                410 -> throw SyncVersionNotFoundException(
                    // Version has been pruned/expired from the server. Caller must reset
                    // local sync state and re-initialise from a fresh snapshot.
                    "Version $parentVersionId is gone from server (410); local sync state is stale"
                )
                else -> throw SyncException(unexpectedStatusMessage(resp.code, "fetching operations", resp.body?.string()))
            }
        }
    }

    /**
     * Upload new operations to the server.
     * Returns an [UploadResult] on success (may signal a snapshot request), or null on conflict
     * (409 - another client pushed first).
     */
    suspend fun uploadOperations(parentVersionId: String, operations: List<Operation>): UploadResult? {
        val url = "$baseUrl/v1/client/add-version/$parentVersionId"
        val historySegment = TaskChampionCodec.encodeHistorySegment(operations)
        val sealed = cryptor.seal(parentVersionId, historySegment)
        val body = sealed.toRequestBody(historyContentType)
        val request = buildRequest(url)
            .addHeader("Content-Type", HISTORY_SEGMENT_CONTENT_TYPE)
            .post(body)
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Network error uploading operations: ${e.message}", e)
        }
        return response.use { resp ->
            when (resp.code) {
                200 -> {
                    val versionId = requiredUuidHeader(resp, "X-Version-Id")
                    // The server may request a snapshot via X-Snapshot-Request: low|high.
                    // Mobile clients should at minimum honour "high" urgency.
                    val snapshotUrgency = resp.header("X-Snapshot-Request")?.trim()?.lowercase()
                    UploadResult(versionId, snapshotUrgency)
                }
                409 -> null // Conflict - another client pushed first
                else -> {
                    val bodyText = resp.body?.string()
                    val message = unexpectedStatusMessage(resp.code, "uploading operations", bodyText)
                    if (resp.code == 500) {
                        // Server doesn't recognize our parent version (e.g. server was reset).
                        // Throw a specific exception so the caller can reset local version state.
                        throw SyncVersionNotFoundException(message)
                    }
                    throw SyncException(message)
                }
            }
        }
    }

    private fun requiredUuidHeader(resp: okhttp3.Response, name: String): String {
        val value = resp.header(name)?.trim()
            ?: throw SyncException("Response missing required header $name")
        return try {
            java.util.UUID.fromString(value)
            value
        } catch (e: Exception) {
            throw SyncException("Response header $name is not a valid UUID: $value", e)
        }
    }

    private fun requireContentType(resp: okhttp3.Response, expected: String, action: String) {
        val contentType = resp.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase()
        if (contentType != expected.lowercase()) {
            throw SyncException(
                "Unexpected content-type '${resp.header("Content-Type")}' while $action (expected $expected)"
            )
        }
    }

    private fun unexpectedStatusMessage(code: Int, action: String, body: String?): String {
        val bodySnippet = body
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(300)
            ?.takeIf { it.isNotBlank() }
        return if (bodySnippet != null) {
            "Unexpected status $code $action. Server response: $bodySnippet"
        } else {
            "Unexpected status $code $action"
        }
    }
}

/**
 * Result returned by [TaskChampionSyncClient.uploadOperations] on HTTP 200.
 *
 * @param newVersionId The new version ID assigned by the server.
 * @param snapshotUrgency Value of the `X-Snapshot-Request` header if present (`"low"` or
 *   `"high"`), or null if the server did not request a snapshot. Mobile clients should upload
 *   a snapshot at least when urgency is `"high"`.
 */
data class UploadResult(val newVersionId: String, val snapshotUrgency: String?)

open class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the server does not recognize the parent version ID we sent.
 * This typically means the server has been reset or the local version state is stale.
 * The caller should reset its stored version and re-sync from scratch.
 */
class SyncVersionNotFoundException(message: String, cause: Throwable? = null) : SyncException(message, cause)
