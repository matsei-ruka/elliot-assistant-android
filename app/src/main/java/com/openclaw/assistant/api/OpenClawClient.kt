package com.openclaw.assistant.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Simple client - POSTs to the configured HTTP connection.
 *
 * Hardened for the CTB text transport (Spec 001): 320-second read/call
 * budget, explicit non-streaming requests, cancellable in-flight calls,
 * `GET /healthz` connection verification and redacted logging.
 */
class OpenClawClient() {

    companion object {
        private const val TAG = "CtbHttp"
    }

    internal val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CtbHttpConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CtbHttpConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CtbHttpConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CtbHttpConfig.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * POST message to HTTP connection and return response.
     * @param attachments List of (mimeType, base64) pairs for image attachments.
     */
    suspend fun sendMessage(
        httpUrl: String,
        message: String,
        sessionId: String,
        authToken: String? = null,
        agentId: String? = null,
        modelName: String? = null,
        attachments: List<Pair<String, String>> = emptyList()
    ): Result<OpenClawResponse> = withContext(Dispatchers.IO) {
        if (httpUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("HTTP connection is not configured")
            )
        }

        val parsedUrl = CtbHttpConfig.validateEndpoint(httpUrl)
            ?: return@withContext Result.failure(
                IllegalArgumentException(
                    "Invalid endpoint: expected an HTTPS URL ending in " +
                        CtbHttpConfig.COMPLETIONS_PATH
                )
            )

        val requestId = CtbLog.newRequestId()
        val startedAt = System.currentTimeMillis()

        try {
            // OpenAI Chat Completions format for /v1/chat/completions
            val requestBody = JsonObject().apply {
                addProperty("model", modelName?.trim()?.takeIf { it.isNotBlank() } ?: "openclaw")
                addProperty("user", sessionId)
                // CTB rejects streaming; always request a completed reply.
                addProperty("stream", false)
                val messagesArray = JsonArray()
                val userMessage = JsonObject().apply {
                    addProperty("role", "user")
                    if (attachments.isEmpty()) {
                        // Text-only: use simple string content (backward compatible)
                        addProperty("content", message)
                    } else {
                        // Multimodal: use content array with text + images (OpenAI vision format)
                        val contentArray = JsonArray()
                        if (message.isNotBlank()) {
                            contentArray.add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", message)
                            })
                        }
                        for ((mimeType, base64) in attachments) {
                            contentArray.add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", "data:$mimeType;base64,$base64")
                                })
                            })
                        }
                        add("content", contentArray)
                    }
                }
                messagesArray.add(userMessage)
                add("messages", messagesArray)
            }

            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(parsedUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${authToken.trim()}")
            }

            if (!agentId.isNullOrBlank()) {
                requestBuilder.addHeader("x-openclaw-agent-id", agentId)
            }

            val request = requestBuilder.build()

            val reply = executeCancellable(client.newCall(request))
            Log.i(
                TAG,
                CtbLog.requestLine(
                    requestId = requestId,
                    status = reply.code,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    bodyLength = reply.body?.length?.toLong(),
                )
            )

            if (!reply.isSuccessful) {
                return@withContext Result.failure(
                    IOException("HTTP ${reply.code}: ${reply.message}")
                )
            }

            if (reply.body.isNullOrBlank()) {
                return@withContext Result.failure(
                    IOException("Empty response")
                )
            }

            // Extract response text from JSON. An empty completion is a
            // visible failure, never a successful TTS input (Spec 001 §B.4).
            val text = extractResponseText(reply.body)
            if (text.isNullOrBlank()) {
                return@withContext Result.failure(
                    IOException("Empty completion")
                )
            }
            Result.success(OpenClawResponse(response = text))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG,
                CtbLog.requestLine(
                    requestId = requestId,
                    status = null,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    bodyLength = null,
                ) + " error=${e.javaClass.simpleName}"
            )
            Result.failure(e)
        }
    }

    /**
     * Verify the CTB connection with `GET /healthz` on the endpoint origin.
     *
     * Never POSTs to the chat endpoint: a generic "ping" fallback would create
     * a real Telegram message (Spec 001 §B.5).
     */
    suspend fun testConnection(
        httpUrl: String,
        authToken: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (httpUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("HTTP connection is not configured")
            )
        }

        val parsedUrl = CtbHttpConfig.validateEndpoint(httpUrl)
            ?: return@withContext Result.failure(
                IllegalArgumentException(
                    "Invalid endpoint: expected an HTTPS URL ending in " +
                        CtbHttpConfig.COMPLETIONS_PATH
                )
            )

        val requestId = CtbLog.newRequestId()
        val startedAt = System.currentTimeMillis()

        try {
            val request = Request.Builder()
                .url(CtbHttpConfig.healthUrl(parsedUrl))
                .get()
                .build()

            val reply = executeCancellable(client.newCall(request))
            Log.i(
                TAG,
                CtbLog.requestLine(
                    requestId = requestId,
                    status = reply.code,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    bodyLength = reply.body?.length?.toLong(),
                )
            )
            if (reply.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(IOException("HTTP ${reply.code}: ${reply.message}"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class HttpReply(
        val code: Int,
        val message: String,
        val body: String?,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /**
     * Executes the call so that coroutine cancellation aborts the underlying
     * OkHttp call immediately, instead of leaving it blocked until timeout.
     * The body is read inside the callback so a cancel also interrupts a
     * response that is still streaming in.
     */
    private suspend fun executeCancellable(call: Call): HttpReply =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use {
                            HttpReply(it.code, it.message, it.body?.string())
                        }
                    } catch (e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(reply)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
            })
        }

    /**
     * Extract response text from various JSON formats
     */
    private fun extractResponseText(json: String): String? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)

            // Check for API error response
            obj.getAsJsonObject("error")?.let { error ->
                val errorMsg = error.get("message")?.asString ?: "Unknown error"
                throw IOException("API Error: $errorMsg")
            }

            // OpenAI format (primary): choices[0].message.content
            obj.getAsJsonArray("choices")?.let { choices ->
                choices.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
            }
            // Fallback formats
            ?: obj.get("response")?.asString
            ?: obj.get("text")?.asString
            ?: obj.get("message")?.asString
            ?: obj.get("content")?.asString
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Response wrapper
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null
) {
    fun getResponseText(): String? = response
}
