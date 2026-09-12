package com.anthonyrohde.truckscan.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestampMillis: Long,
    val message: String,
) {
    val time: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
}

/**
 * Rolling in-memory log of everything sent to and received from the adapter.
 *
 * Genuinely load-bearing for this kind of app rather than a developer luxury:
 * when a module will not answer, the only way anyone can tell why is to see the
 * actual exchange. It is capped so a long logging session cannot exhaust memory.
 */
class SessionLog(private val capacity: Int = 2_000) {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun append(message: String) {
        _entries.value = (_entries.value + LogEntry(System.currentTimeMillis(), message))
            .takeLast(capacity)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** Plain text, for sharing when asking someone else for help. */
    fun export(): String = _entries.value.joinToString("\n") { "${it.time}  ${it.message}" }
}
