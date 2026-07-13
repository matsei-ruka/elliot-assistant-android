package com.openclaw.assistant.api

import java.util.UUID

/**
 * Redacted request logging for the CTB transport (Spec 001 §B.6).
 *
 * A log line may carry the request ID, HTTP status, elapsed time and body
 * length. It must never contain prompt text, reply text, bearer values, the
 * endpoint URL or its query string.
 */
object CtbLog {

    fun newRequestId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun requestLine(
        requestId: String,
        status: Int?,
        elapsedMs: Long,
        bodyLength: Long?,
    ): String = buildString {
        append("request id=").append(requestId)
        append(" status=").append(status?.toString() ?: "none")
        append(" elapsedMs=").append(elapsedMs)
        append(" bodyLength=").append(bodyLength?.toString() ?: "none")
    }
}
