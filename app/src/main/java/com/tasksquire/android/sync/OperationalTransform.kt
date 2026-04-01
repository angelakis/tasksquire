package com.tasksquire.android.sync

/**
 * Operational Transformation (OT) for TaskChampion sync protocol.
 *
 * When the server has versions the replica hasn't seen, local operations must be
 * "rebased" onto the server's operations using pairwise transformation.
 *
 * For each pair (serverOp, localOp), produce (serverOp', localOp') such that
 * applying serverOp then localOp' yields the same state as applying localOp then serverOp'.
 *
 * Reference: TaskChampion Sync Protocol spec, Section 5.
 */
object OperationalTransform {

    /**
     * Result of transforming a pair of operations.
     * Either operation may be null, indicating it became a no-op.
     */
    data class TransformResult(
        val serverOp: Operation?,
        val localOp: Operation?
    )

    /**
     * Transform a single server operation against a single local operation.
     */
    fun transform(serverOp: Operation, localOp: Operation): TransformResult {
        // Different UUIDs: no conflict, both pass through unchanged
        if (serverOp.uuid() != localOp.uuid()) {
            return TransformResult(serverOp, localOp)
        }

        // Same UUID: apply transform rules
        return when (serverOp) {
            is Operation.Create -> transformServerCreate(serverOp, localOp)
            is Operation.Delete -> transformServerDelete(serverOp, localOp)
            is Operation.Update -> transformServerUpdate(serverOp, localOp)
        }
    }

    /**
     * Transform all local operations against a list of server operations.
     * Returns the transformed server operations (to apply locally) and
     * transformed local operations (to send to server).
     */
    fun rebase(
        serverOps: List<Operation>,
        localOps: List<Operation>
    ): Pair<List<Operation>, List<Operation>> {
        val transformedServerOps = mutableListOf<Operation>()
        var currentLocalOps = localOps.toMutableList()

        for (serverOp in serverOps) {
            var currentServerOp: Operation? = serverOp
            val newLocalOps = mutableListOf<Operation>()

            for (localOp in currentLocalOps) {
                if (currentServerOp == null) {
                    // Server op became a no-op; remaining local ops pass through
                    newLocalOps.add(localOp)
                    continue
                }
                val result = transform(currentServerOp!!, localOp)
                currentServerOp = result.serverOp
                if (result.localOp != null) {
                    newLocalOps.add(result.localOp)
                }
            }

            if (currentServerOp != null) {
                transformedServerOps.add(currentServerOp)
            }
            currentLocalOps = newLocalOps
        }

        return transformedServerOps to currentLocalOps
    }

    private fun transformServerCreate(serverOp: Operation.Create, localOp: Operation): TransformResult {
        return when (localOp) {
            is Operation.Create -> {
                // Both create the same UUID: both become no-ops (task already exists)
                TransformResult(null, null)
            }
            is Operation.Delete -> {
                // Server creates, local deletes: prefer Create over Delete.
                // Server Create survives, local Delete becomes no-op.
                TransformResult(serverOp, null)
            }
            is Operation.Update -> {
                // Server creates, local updates: prefer Update.
                // Server no-op, local Update passes through.
                TransformResult(null, localOp)
            }
        }
    }

    private fun transformServerDelete(serverOp: Operation.Delete, localOp: Operation): TransformResult {
        return when (localOp) {
            is Operation.Create -> {
                // Server deletes, local creates: prefer Create over Delete.
                // Server Delete becomes no-op, local Create survives.
                TransformResult(null, localOp)
            }
            is Operation.Delete -> {
                // Both delete: both become no-ops
                TransformResult(null, null)
            }
            is Operation.Update -> {
                // Server deletes, local updates: prefer Delete.
                // Server Delete passes, local Update becomes no-op.
                TransformResult(serverOp, null)
            }
        }
    }

    private fun transformServerUpdate(serverOp: Operation.Update, localOp: Operation): TransformResult {
        return when (localOp) {
            is Operation.Create -> {
                // Server updates, local creates: server update passes, local no-op
                TransformResult(serverOp, null)
            }
            is Operation.Delete -> {
                // Server updates, local deletes: server no-op, local delete passes
                TransformResult(null, localOp)
            }
            is Operation.Update -> {
                if (serverOp.property != localOp.property) {
                    // Different properties: both pass through unchanged
                    TransformResult(serverOp, localOp)
                } else if (serverOp.value == localOp.value) {
                    // Same property, same value: both become no-ops (same effect)
                    TransformResult(null, null)
                } else {
                    // Same property, different values: later timestamp wins;
                    // ties go to server (op1)
                    if (serverOp.timestamp >= localOp.timestamp) {
                        // Server wins
                        TransformResult(serverOp, null)
                    } else {
                        // Local wins
                        TransformResult(null, localOp)
                    }
                }
            }
        }
    }

    private fun Operation.uuid(): String = when (this) {
        is Operation.Create -> uuid
        is Operation.Delete -> uuid
        is Operation.Update -> uuid
    }
}
