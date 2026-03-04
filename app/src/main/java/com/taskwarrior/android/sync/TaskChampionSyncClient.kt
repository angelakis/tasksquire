package com.taskwarrior.android.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the TaskChampion sync protocol.
 *
 * The TaskChampion sync server exposes these endpoints:
 *   GET  /v1/client/snapshot                       - fetch current snapshot
 *   POST /v1/client/snapshot                       - upload snapshot
 *   GET  /v1/client/add-version/{parent_version_id} - get operations since version
 *   POST /v1/client/add-version/{parent_version_id} - upload new operations
 *
 * Authentication is done via HTTP Basic Auth using the client_id as the
 * username and the encryption_secret as the password.
 */
class TaskChampionSyncClient(private val config: SyncServerConfig) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val baseUrl: String = config.serverUrl.trimEnd('/')

    private fun buildRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (config.encryptionSecret != null) {
            val credentials = okhttp3.Credentials.basic(config.clientId, config.encryptionSecret)
            builder.addHeader("Authorization", credentials)
        } else {
            builder.addHeader("X-Client-Id", config.clientId)
        }
        return builder
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
                    val body = resp.body?.string() ?: throw SyncException("Empty snapshot response")
                    JsonSerializer.snapshotFromJson(body)
                }
                404 -> null
                401 -> throw SyncException("Authentication failed (401)")
                else -> throw SyncException("Unexpected status ${resp.code} fetching snapshot")
            }
        }
    }

    /**
     * Upload a new snapshot to the server.
     */
    suspend fun uploadSnapshot(snapshot: Snapshot) {
        val url = "$baseUrl/v1/client/snapshot"
        val body = JsonSerializer.snapshotToJson(snapshot).toRequestBody(jsonMediaType)
        val request = buildRequest(url).post(body).build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Network error uploading snapshot: ${e.message}", e)
        }
        response.use { resp ->
            if (resp.code !in 200..299) {
                throw SyncException("Unexpected status ${resp.code} uploading snapshot")
            }
        }
    }

    /**
     * Get operations since the given parent version.
     * Returns (newVersionId, operations) or null if the parent version is unknown (404).
     */
    suspend fun getOperationsSince(parentVersionId: String): Pair<String, List<Operation>>? {
        val url = "$baseUrl/v1/client/add-version/$parentVersionId"
        val request = buildRequest(url).get().build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Network error fetching operations: ${e.message}", e)
        }
        return response.use { resp ->
            when (resp.code) {
                200 -> {
                    val body = resp.body?.string() ?: throw SyncException("Empty operations response")
                    JsonSerializer.versionResponseFromJson(body)
                }
                404 -> null
                401 -> throw SyncException("Authentication failed (401)")
                else -> throw SyncException("Unexpected status ${resp.code} fetching operations")
            }
        }
    }

    /**
     * Upload new operations to the server.
     * Returns the new version ID on success, or null on conflict (409 - another client pushed first).
     */
    suspend fun uploadOperations(parentVersionId: String, operations: List<Operation>): String? {
        val url = "$baseUrl/v1/client/add-version/$parentVersionId"
        val body = JsonSerializer.operationsToJson(operations).toRequestBody(jsonMediaType)
        val request = buildRequest(url).post(body).build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw SyncException("Network error uploading operations: ${e.message}", e)
        }
        return response.use { resp ->
            when (resp.code) {
                200 -> {
                    val responseBody = resp.body?.string() ?: throw SyncException("Empty version response")
                    val (newVersionId, _) = JsonSerializer.versionResponseFromJson(responseBody)
                    newVersionId
                }
                409 -> null // Conflict - another client pushed first
                401 -> throw SyncException("Authentication failed (401)")
                else -> throw SyncException("Unexpected status ${resp.code} uploading operations")
            }
        }
    }
}

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
