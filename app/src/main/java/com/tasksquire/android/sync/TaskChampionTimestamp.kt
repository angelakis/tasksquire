package com.tasksquire.android.sync

/**
 * Parse a TaskChampion timestamp value (epoch seconds as a decimal integer string)
 * into local epoch milliseconds.
 *
 * Per the spec, all TaskChampion timestamps are "UNIX epoch timestamps,
 * in the form of an integer" (seconds).
 */
fun parseTaskChampionTimestamp(value: String?): Long? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val seconds = raw.toLongOrNull() ?: return null
    return seconds * 1000
}
