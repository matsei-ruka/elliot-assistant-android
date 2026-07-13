package com.openclaw.assistant.voice

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CtbVoiceProtocolTest {
    @Test
    fun `serializes exact non-streaming CTB inline voice shape`() {
        val root = JsonParser.parseString(
            CtbVoiceProtocol.serializeRequest("telegram-agent", "install-random", "T2dnUw==")
        ).asJsonObject

        assertEquals("telegram-agent", root.get("model").asString)
        assertEquals("install-random", root.get("user").asString)
        assertFalse(root.get("stream").asBoolean)
        assertEquals(listOf("text", "audio"), root.getAsJsonArray("modalities").map { it.asString })
        val message = root.getAsJsonArray("messages").single().asJsonObject
        assertEquals("user", message.get("role").asString)
        val content = message.getAsJsonArray("content")
        assertEquals(1, content.size())
        val part = content.single().asJsonObject
        assertEquals("input_audio", part.get("type").asString)
        assertEquals("T2dnUw==", part.getAsJsonObject("input_audio").get("data").asString)
        assertEquals("ogg", part.getAsJsonObject("input_audio").get("format").asString)
        assertFalse(root.has("audio"))
    }

    @Test
    fun `prefers inline audio and infers current CTB fixed ogg format`() {
        val parsed = CtbVoiceProtocol.parseResponse(
            """{"choices":[{"message":{"content":null,"audio":{"data":"T2dnUw==","transcript":"safe text"}}}]}"""
        ) as CtbVoicePayload.Audio

        assertEquals("T2dnUw==", parsed.base64Data)
        assertEquals("ogg", parsed.format)
        assertEquals("safe text", parsed.transcript)
        assertEquals(null, parsed.fallbackText)
    }

    @Test
    fun `accepts explicit opus response format`() {
        val parsed = CtbVoiceProtocol.parseResponse(
            """{"choices":[{"message":{"audio":{"data":"T2dnUw==","format":"OPUS"}}}]}"""
        ) as CtbVoicePayload.Audio
        assertEquals("opus", parsed.format)
    }

    @Test
    fun `selects text only fallback when audio object is absent`() {
        val parsed = CtbVoiceProtocol.parseResponse(
            """{"choices":[{"message":{"content":"fallback once"}}]}"""
        ) as CtbVoicePayload.Text
        assertEquals("fallback once", parsed.content)
    }

    @Test
    fun `invalid audio object selects valid text content fallback`() {
        val parsed = CtbVoiceProtocol.parseResponse(
            """{"choices":[{"message":{"content":"fallback once","audio":{"data":"","format":"mp3"}}}]}"""
        ) as CtbVoicePayload.Text
        assertEquals("fallback once", parsed.content)
    }

    @Test
    fun `invalid audio without text is rejected`() {
        assertThrows(java.io.IOException::class.java) {
            CtbVoiceProtocol.parseResponse(
                """{"choices":[{"message":{"content":null,"audio":{"data":"","format":"mp3"}}}]}"""
            )
        }
    }

    @Test
    fun `empty and raw json responses are rejected`() {
        assertThrows(java.io.IOException::class.java) {
            CtbVoiceProtocol.parseResponse("""{"unexpected":"shape"}""")
        }
        assertThrows(java.io.IOException::class.java) {
            CtbVoiceProtocol.parseResponse("""{"choices":[{"message":{"content":null}}]}""")
        }
    }
}
