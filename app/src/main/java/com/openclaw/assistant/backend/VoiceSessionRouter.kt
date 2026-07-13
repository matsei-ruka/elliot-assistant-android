package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository

/**
 * Pure, testable resolution of the backend a voice session will talk to.
 *
 * The session resolves its route exactly once when it opens and keeps the
 * result for the whole request. Only an OPENCLAW_GATEWAY route depends on
 * gateway health or gateway session management; OPENCLAW_HTTP (CTB) and
 * Hermes routes must work with the gateway offline or unconfigured.
 */
object VoiceSessionRouter {

    sealed class Route {
        /** Send through [PrimaryBackendDispatcher] to this backend. */
        data class Backend(val id: String, val type: BackendType) : Route()

        /** Only an OpenClaw Gateway is configured and it is not connected. */
        object GatewayUnavailable : Route()

        /** No usable backend is configured. */
        object NotConfigured : Route()
    }

    fun resolve(
        voiceTarget: String,
        backends: List<AgentBackendConfig>,
        gatewayHealthy: Boolean,
    ): Route {
        val enabled = backends.filter { it.enabled }
        return if (voiceTarget == SettingsRepository.VOICE_TARGET_HERMES) {
            resolveHermes(enabled) ?: resolveOpenClaw(enabled, gatewayHealthy)
        } else {
            resolveOpenClaw(enabled, gatewayHealthy).let { route ->
                if (route is Route.NotConfigured) resolveHermes(enabled) ?: route else route
            }
        }
    }

    private fun resolveHermes(enabled: List<AgentBackendConfig>): Route.Backend? {
        val hermes = enabled.firstOrNull { it.isPrimary && it.type == BackendType.HERMES_API_SERVER }
            ?: enabled.firstOrNull { it.type == BackendType.HERMES_API_SERVER }
        return hermes?.let { Route.Backend(it.id, it.type) }
    }

    private fun resolveOpenClaw(
        enabled: List<AgentBackendConfig>,
        gatewayHealthy: Boolean,
    ): Route {
        val primary = enabled.firstOrNull {
            it.isPrimary && (it.type == BackendType.OPENCLAW_GATEWAY || it.type == BackendType.OPENCLAW_HTTP)
        }
        if (primary?.type == BackendType.OPENCLAW_HTTP) {
            return Route.Backend(primary.id, primary.type)
        }
        if (primary?.type == BackendType.OPENCLAW_GATEWAY && gatewayHealthy) {
            return Route.Backend(primary.id, primary.type)
        }
        // The HTTP (CTB) transport never depends on gateway health.
        enabled.firstOrNull { it.type == BackendType.OPENCLAW_HTTP }?.let {
            return Route.Backend(it.id, it.type)
        }
        val gateway = enabled.firstOrNull { it.type == BackendType.OPENCLAW_GATEWAY }
        return when {
            gateway != null && gatewayHealthy -> Route.Backend(gateway.id, gateway.type)
            gateway != null -> Route.GatewayUnavailable
            else -> Route.NotConfigured
        }
    }
}
