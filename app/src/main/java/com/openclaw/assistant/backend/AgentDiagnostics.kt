package com.openclaw.assistant.backend

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class AgentDiagnosticSnapshot(
    val backendId: String,
    val backendName: String,
    val backendType: String,
    val totalMessages: Int = 0,
    val streamsCompleted: Int = 0,
    val streamsErrored: Int = 0,
    val streamsCancelled: Int = 0,
    val totalInputChars: Long = 0,
    val totalOutputChars: Long = 0,
    val averageTimeToFirstTokenMs: Long = 0,
    val averageCompletionMs: Long = 0,
    val lastHealthOk: Boolean? = null,
    val lastHealthLatencyMs: Long? = null,
    val lastError: String? = null,
    val updatedAtMs: Long = 0,
)

/**
 * Small local-only diagnostics store shared by Hermes and OpenClaw backends.
 * It stores counts/status only; never message text, screen text, tokens, or
 * profile document contents.
 */
object AgentDiagnostics {
    private const val PREFS_NAME = "openclaw.agent.diagnostics"
    private const val KEY_SNAPSHOTS = "snapshots.v1"

    private val _snapshots = MutableStateFlow<List<AgentDiagnosticSnapshot>>(emptyList())
    val snapshots: StateFlow<List<AgentDiagnosticSnapshot>> = _snapshots.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _snapshots.value = decode(prefs?.getString(KEY_SNAPSHOTS, null).orEmpty())
        }
    }

    fun recordHealth(context: Context, backend: AgentBackendConfig, ok: Boolean, latencyMs: Long?, error: String? = null) {
        initialize(context)
        update(backend) { current ->
            current.copy(
                lastHealthOk = ok,
                lastHealthLatencyMs = latencyMs,
                lastError = error?.let { "health_error" },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun beginMessage(context: Context, backend: AgentBackendConfig, inputChars: Int): ActiveRun {
        initialize(context)
        return ActiveRun(context.applicationContext, backend, inputChars.coerceAtLeast(0), System.currentTimeMillis())
    }

    fun clear(context: Context) {
        initialize(context)
        _snapshots.value = emptyList()
        prefs?.edit { remove(KEY_SNAPSHOTS) }
    }

    class ActiveRun internal constructor(
        private val context: Context,
        private val backend: AgentBackendConfig,
        private val inputChars: Int,
        private val startedAtMs: Long,
    ) {
        private var firstTokenAtMs: Long? = null
        private var outputChars: Int = 0

        fun onToken(chars: Int) {
            if (firstTokenAtMs == null) firstTokenAtMs = System.currentTimeMillis()
            outputChars += chars.coerceAtLeast(0)
        }

        fun complete(finalOutputChars: Int? = null) {
            val now = System.currentTimeMillis()
            val ttft = (firstTokenAtMs ?: now) - startedAtMs
            val completion = now - startedAtMs
            val out = (finalOutputChars ?: outputChars).coerceAtLeast(0)
            initialize(context)
            update(backend) { current ->
                val newMessages = current.totalMessages + 1
                val newCompleted = current.streamsCompleted + 1
                current.copy(
                    totalMessages = newMessages,
                    streamsCompleted = newCompleted,
                    totalInputChars = current.totalInputChars + inputChars,
                    totalOutputChars = current.totalOutputChars + out,
                    averageTimeToFirstTokenMs = rollingAverage(current.averageTimeToFirstTokenMs, current.streamsCompleted, ttft),
                    averageCompletionMs = rollingAverage(current.averageCompletionMs, current.streamsCompleted, completion),
                    lastError = null,
                    updatedAtMs = now,
                )
            }
        }

        fun error(message: String?) {
            initialize(context)
            update(backend) { current ->
                current.copy(
                    streamsErrored = current.streamsErrored + 1,
                    lastError = message?.let { "backend_error" },
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }

        fun cancelled() {
            initialize(context)
            update(backend) { current ->
                current.copy(
                    streamsCancelled = current.streamsCancelled + 1,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun rollingAverage(previousAverage: Long, previousCount: Int, next: Long): Long {
        val count = previousCount.coerceAtLeast(0)
        return ((previousAverage * count) + next) / (count + 1)
    }

    private fun update(backend: AgentBackendConfig, block: (AgentDiagnosticSnapshot) -> AgentDiagnosticSnapshot) {
        val current = _snapshots.value
        val index = current.indexOfFirst { it.backendId == backend.id }
        val base = if (index >= 0) {
            current[index]
        } else {
            AgentDiagnosticSnapshot(
                backendId = backend.id,
                backendName = backend.displayName,
                backendType = backend.type.name,
            )
        }
        val updated = block(base.copy(backendName = backend.displayName, backendType = backend.type.name))
        val next = if (index >= 0) current.toMutableList().also { it[index] = updated } else current + updated
        _snapshots.value = next.sortedByDescending { it.updatedAtMs }
        prefs?.edit { putString(KEY_SNAPSHOTS, encode(_snapshots.value)) }
    }

    private fun encode(items: List<AgentDiagnosticSnapshot>): String =
        json.encodeToString(ListSerializer(AgentDiagnosticSnapshot.serializer()), items)

    private fun decode(raw: String): List<AgentDiagnosticSnapshot> = runCatching {
        json.decodeFromString(ListSerializer(AgentDiagnosticSnapshot.serializer()), raw)
    }.getOrDefault(emptyList())
}
