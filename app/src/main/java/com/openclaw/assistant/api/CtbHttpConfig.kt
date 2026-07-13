package com.openclaw.assistant.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * CTB (Completion Telegram Bridge) transport configuration.
 *
 * Isolates the CTB-specific endpoint validation, health-check derivation and
 * timeout budget so the generic OpenClaw Gateway code never needs to know
 * about CTB internals (Spec 001, implementation notes).
 */
object CtbHttpConfig {

    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L

    /**
     * CTB is configured to hold the HTTP request open for up to 300 seconds
     * while waiting for the Telegram agent reply, so the client read/call
     * budget must exceed that (Spec 001 §B.1).
     */
    const val READ_TIMEOUT_SECONDS = 320L
    const val CALL_TIMEOUT_SECONDS = 320L

    const val COMPLETIONS_PATH = "/v1/chat/completions"
    const val HEALTH_PATH = "/healthz"

    /**
     * Validates a configured completion endpoint without rewriting it.
     *
     * Accepted: an HTTPS URL whose path ends in [COMPLETIONS_PATH].
     * Plain HTTP is allowed only for loopback hosts so the transport can be
     * exercised against a local test server; every real endpoint must be HTTPS.
     *
     * Returns the parsed URL, or null when the endpoint is invalid — callers
     * must surface a configuration error instead of guessing (Spec 001 §B.5).
     */
    fun validateEndpoint(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps && !isLoopback(url.host)) return null
        if (!url.encodedPath.endsWith(COMPLETIONS_PATH)) return null
        return url
    }

    /** Scheme + host + port of the configured endpoint; nothing else is derived. */
    fun origin(endpoint: HttpUrl): HttpUrl = HttpUrl.Builder()
        .scheme(endpoint.scheme)
        .host(endpoint.host)
        .port(endpoint.port)
        .build()

    /**
     * Health-check URL for connection verification: `GET <origin>/healthz`.
     * Never the chat endpoint — a POST there creates a real Telegram message.
     */
    fun healthUrl(endpoint: HttpUrl): HttpUrl =
        origin(endpoint).newBuilder().encodedPath(HEALTH_PATH).build()

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1"
}
