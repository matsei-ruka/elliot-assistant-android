package com.openclaw.assistant.backend

import android.content.Context
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.api.OpenClawVoiceResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Single entry point used by voice (wake word, Voice Overlay, Assistant
 * activation, continuous conversation) and Chat to send a single user
 * message to whichever backend is currently Primary. Returns the final
 * assistant text (streaming is collapsed for the legacy text-only voice
 * pipeline; the new Chat UI gets per-token events through [AgentClient]
 * directly).
 */
object PrimaryBackendDispatcher {
    data class Reply(val text: String, val sourceDisplayName: String)

    /**
     * Sends to the selected enabled backend. If [backendId] is null, the
     * persisted Primary is used. Returns null only when there is no configured
     * target, allowing legacy installs without migrated backends to fall back to
     * the original settings-driven pipeline.
     */
    suspend fun send(
        context: Context,
        userText: String,
        backendId: String? = null,
        sessionId: String? = null,
        agentId: String? = null,
    ): Reply? {
        val manager = BackendManager.getInstance(context)
        val backends = manager.backends.first()
        val target = if (backendId != null) {
            backends.firstOrNull { it.id == backendId && it.enabled }
        } else {
            backends.firstOrNull { it.enabled && it.isPrimary }
        } ?: return null
        return when (target.type) {
            BackendType.HERMES_API_SERVER,
            BackendType.OPENCLAW_HTTP -> sendViaAgentClient(context, target, userText, sessionId, agentId)
            BackendType.OPENCLAW_GATEWAY -> sendViaGateway(context, target, userText)
        }
    }

    suspend fun sendPrimary(
        context: Context,
        userText: String,
        sessionId: String? = null,
        agentId: String? = null,
    ): Reply? = send(context, userText, backendId = null, sessionId = sessionId, agentId = agentId)

    /**
     * Backwards-compatible alias retained for older call sites during the
     * migration. It now sends to any Primary backend, not only Hermes.
     */
    suspend fun sendIfHermesPrimary(
        context: Context,
        userText: String,
    ): Reply? = sendPrimary(context, userText)

    /**
     * CTB-only raw voice dispatch. Resolution is against the authoritative
     * BackendRepository and never consults OpenClaw Gateway health/session APIs.
     */
    suspend fun sendCtbVoice(
        context: Context,
        inputFile: File,
        outputFile: File,
        backendId: String,
        sessionId: String,
    ): OpenClawVoiceResponse {
        val target = BackendManager.getInstance(context).backends.first()
            .firstOrNull { it.id == backendId && it.enabled }
            ?: throw IllegalStateException("Configured CTB backend is unavailable")
        if (target.type != BackendType.OPENCLAW_HTTP) {
            throw IllegalStateException("Raw voice transport requires the CTB HTTP backend")
        }
        return OpenClawClient().sendVoiceMessage(
            httpUrl = target.baseUrl.orEmpty(),
            inputFile = inputFile,
            outputFile = outputFile,
            sessionId = sessionId,
            authToken = target.apiKeyOrToken?.takeIf { it.isNotBlank() },
            modelName = target.modelName?.takeIf { it.isNotBlank() },
        ).getOrElse { throw it }
    }

    private suspend fun sendViaAgentClient(
        context: Context,
        target: AgentBackendConfig,
        userText: String,
        sessionId: String?,
        agentId: String?,
    ): Reply {
        val client = AgentClientFactory.create(target)
        val collected = StringBuilder()
        val run = AgentDiagnostics.beginMessage(context, target, userText.length)
        try {
            client.sendMessage(
                messages = listOf(AgentMessage.user(userText)),
                options = AgentSendOptions(
                    sessionId = sessionId,
                    stream = target.useStreaming,
                    extra = mapOf("agentId" to agentId.orEmpty()),
                ),
            ).collect { event ->
                when (event) {
                    is AgentEvent.TokenDelta -> {
                        collected.append(event.text)
                        run.onToken(event.text.length)
                    }
                    is AgentEvent.MessageDelta -> {
                        collected.append(event.text)
                        run.onToken(event.text.length)
                    }
                    is AgentEvent.Completed -> {
                        if (collected.isEmpty()) collected.append(event.finalText)
                        run.complete(collected.length)
                    }
                    is AgentEvent.ToolProgress -> com.openclaw.assistant.ui.backend.ToolProgressFeed.push(event)
                    is AgentEvent.Error -> {
                        run.error(event.message)
                        throw RuntimeException("${target.displayName} error: ${event.message}", event.cause)
                    }
                    else -> Unit
                }
            }
        } catch (e: Throwable) {
            run.error(e.message ?: e.javaClass.simpleName)
            throw e
        }
        if (collected.isBlank()) {
            throw IllegalStateException("${target.displayName} returned an empty response. Check the backend model/provider configuration.")
        }
        return Reply(text = collected.toString(), sourceDisplayName = target.displayName)
    }

    private suspend fun sendViaGateway(
        context: Context,
        target: AgentBackendConfig,
        userText: String,
    ): Reply {
        val runtime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (!runtime.chatHealthOk.value) {
            throw IllegalStateException("OpenClaw Gateway is not connected")
        }

        val run = AgentDiagnostics.beginMessage(context, target, userText.length)
        val assistantCountBefore = runtime.chatMessages.value.count { it.role == "assistant" }
        runtime.sendChat(
            message = userText,
            thinking = "low",
            attachments = emptyList(),
            modelName = target.modelName?.takeIf { it.isNotBlank() },
        )

        val responseText: String? = try {
            withTimeout<String>(60_000L) {
                var found: String? = null
                while (found == null) {
                    runtime.chatError.value?.takeIf { it.isNotBlank() }?.let { error ->
                        throw IllegalStateException(error)
                    }
                    val assistantText = runtime.chatMessages.value
                        .takeIf { messages -> messages.count { it.role == "assistant" } > assistantCountBefore }
                        ?.lastOrNull { it.role == "assistant" }
                        ?.content?.firstOrNull { it.type == "text" }?.text
                    if (!assistantText.isNullOrBlank()) {
                        found = assistantText
                    } else {
                        delay(250L)
                    }
                }
                found
            }
        } catch (_: TimeoutCancellationException) {
            null
        }

        if (responseText.isNullOrBlank()) {
            run.error("No reply before timeout")
            throw IllegalStateException(
                "OpenClaw Gateway accepted the message, but the agent did not return a reply. Check the host OpenClaw agent/model authentication."
            )
        }
        run.onToken(responseText.length)
        run.complete(responseText.length)
        return Reply(text = responseText, sourceDisplayName = target.displayName)
    }
}
