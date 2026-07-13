package com.openclaw.assistant.voice

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

sealed class CtbVoicePayload {
    data class Audio(
        val base64Data: String,
        val format: String,
        val transcript: String?,
        val fallbackText: String?,
    ) : CtbVoicePayload()

    data class Text(val content: String) : CtbVoicePayload()
}

/** Pure CTB request serialization and response selection. */
object CtbVoiceProtocol {
    private val gson = Gson()
    private val allowedFormats = setOf("ogg", "opus")

    fun serializeRequest(
        model: String,
        sessionId: String,
        inputAudioBase64: String,
    ): String {
        if (inputAudioBase64.isEmpty() || inputAudioBase64.length > CtbVoiceLimits.MAX_BASE64_CHARS) {
            throw InvalidVoiceAudioException("Voice input Base64 exceeds the size limit")
        }
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("user", sessionId)
            addProperty("stream", false)
            add("modalities", JsonArray().apply {
                add("text")
                add("audio")
            })
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "input_audio")
                            add("input_audio", JsonObject().apply {
                                addProperty("data", inputAudioBase64)
                                addProperty("format", "ogg")
                            })
                        })
                    })
                })
            })
        }
        val json = gson.toJson(root)
        if (json.toByteArray(Charsets.UTF_8).size > CtbVoiceLimits.MAX_JSON_BYTES) {
            throw InvalidVoiceAudioException("Voice request exceeds the JSON size limit")
        }
        return json
    }

    fun parseResponse(json: String): CtbVoicePayload {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IOException("Invalid completion response", e)
        }
        if (root.has("error")) throw IOException("Completion returned an error response")

        val message = root.getAsJsonArray("choices")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.getAsJsonObject("message")
            ?: throw IOException("Completion response has no assistant message")

        val content = message.stringOrNull("content")
        if (message.has("audio") && !message.get("audio").isJsonNull) {
            val audio = message.get("audio")
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return content?.let(CtbVoicePayload::Text)
                    ?: throw IOException("Completion audio object is invalid")
            val data = audio.stringOrNull("data")
                ?: return content?.let(CtbVoicePayload::Text)
                    ?: throw IOException("Completion audio data is empty")
            if (data.length > CtbVoiceLimits.MAX_BASE64_CHARS) {
                return content?.let(CtbVoicePayload::Text)
                    ?: throw InvalidVoiceAudioException("Voice reply audio exceeds the size limit")
            }
            // CTB v0.2.0 does not emit format. Its fixed contract is OGG/Opus;
            // still validate an optional future field before trusting it.
            val format = audio.stringOrNull("format")?.lowercase() ?: "ogg"
            if (format !in allowedFormats) {
                return content?.let(CtbVoicePayload::Text)
                    ?: throw IOException("Completion audio format is unsupported")
            }
            return CtbVoicePayload.Audio(
                base64Data = data,
                format = format,
                transcript = audio.stringOrNull("transcript"),
                fallbackText = content,
            )
        }

        return content?.let(CtbVoicePayload::Text)
            ?: throw IOException("Completion response is empty")
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString.trim().takeIf { it.isNotEmpty() }
    }
}
