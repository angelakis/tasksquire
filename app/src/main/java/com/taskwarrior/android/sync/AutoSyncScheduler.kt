package com.taskwarrior.android.sync

import android.content.Context

object AutoSyncScheduler {
    fun enqueueMutationSync(context: Context) {
        SyncWorker.enqueueMutationSync(context)
    }

    fun enqueueForegroundSync(context: Context) {
        SyncWorker.enqueueForegroundSync(context)
    }

    fun schedulePeriodicSync(context: Context, enabled: Boolean) {
        SyncWorker.schedulePeriodicSync(context, enabled)
    }
}
