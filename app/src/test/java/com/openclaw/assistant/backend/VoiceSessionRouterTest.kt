package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSessionRouterTest {

    @Test
    fun `primary http backend is usable while the gateway is offline`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(
                backend("http", BackendType.OPENCLAW_HTTP, primary = true),
                backend("gateway", BackendType.OPENCLAW_GATEWAY),
            ),
            gatewayHealthy = false,
        )
        assertEquals(VoiceSessionRouter.Route.Backend("http", BackendType.OPENCLAW_HTTP), route)
    }

    @Test
    fun `unhealthy primary gateway falls back to the http backend`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(
                backend("gateway", BackendType.OPENCLAW_GATEWAY, primary = true),
                backend("http", BackendType.OPENCLAW_HTTP),
            ),
            gatewayHealthy = false,
        )
        assertEquals(VoiceSessionRouter.Route.Backend("http", BackendType.OPENCLAW_HTTP), route)
    }

    @Test
    fun `healthy primary gateway keeps the gateway route`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(
                backend("gateway", BackendType.OPENCLAW_GATEWAY, primary = true),
                backend("http", BackendType.OPENCLAW_HTTP),
            ),
            gatewayHealthy = true,
        )
        assertEquals(VoiceSessionRouter.Route.Backend("gateway", BackendType.OPENCLAW_GATEWAY), route)
    }

    @Test
    fun `only an unhealthy gateway yields a gateway error not a config error`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(backend("gateway", BackendType.OPENCLAW_GATEWAY, primary = true)),
            gatewayHealthy = false,
        )
        assertEquals(VoiceSessionRouter.Route.GatewayUnavailable, route)
    }

    @Test
    fun `hermes target routes to the hermes backend`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_HERMES,
            backends = listOf(
                backend("http", BackendType.OPENCLAW_HTTP, primary = true),
                backend("hermes", BackendType.HERMES_API_SERVER),
            ),
            gatewayHealthy = false,
        )
        assertEquals(VoiceSessionRouter.Route.Backend("hermes", BackendType.HERMES_API_SERVER), route)
    }

    @Test
    fun `hermes target without a hermes backend falls back to the ctb http backend`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_HERMES,
            backends = listOf(backend("http", BackendType.OPENCLAW_HTTP, primary = true)),
            gatewayHealthy = false,
        )
        assertEquals(VoiceSessionRouter.Route.Backend("http", BackendType.OPENCLAW_HTTP), route)
    }

    @Test
    fun `disabled backends are ignored`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = listOf(backend("http", BackendType.OPENCLAW_HTTP, enabled = false)),
            gatewayHealthy = true,
        )
        assertEquals(VoiceSessionRouter.Route.NotConfigured, route)
    }

    @Test
    fun `no backends at all is a configuration error`() {
        val route = VoiceSessionRouter.resolve(
            voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
            backends = emptyList(),
            gatewayHealthy = true,
        )
        assertEquals(VoiceSessionRouter.Route.NotConfigured, route)
    }

    private fun backend(
        id: String,
        type: BackendType,
        primary: Boolean = false,
        enabled: Boolean = true,
    ) = AgentBackendConfig(
        id = id,
        displayName = id,
        type = type,
        enabled = enabled,
        isPrimary = primary,
        baseUrl = "https://$id.test/v1/chat/completions",
        host = "$id.test",
        port = 1234,
    )
}
