package com.openclaw.assistant.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenClawClientCtbTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenClawClient

    private val endpoint: String
        get() = server.url("/v1/chat/completions").toString()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenClawClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun completionJson(content: String): String =
        """{"choices":[{"message":{"role":"assistant","content":"$content"}}]}"""

    // --- Timeout budget (Spec 001 §B.1) ---

    @Test
    fun `okhttp client uses the 320 second CTB budget`() {
        assertEquals(30_000, client.client.connectTimeoutMillis)
        assertEquals(30_000, client.client.writeTimeoutMillis)
        assertEquals(320_000, client.client.readTimeoutMillis)
        assertEquals(320_000, client.client.callTimeoutMillis)
    }

    // --- Request shape (Spec 001 §B.3, fixed configuration) ---

    @Test
    fun `request is non-streaming with model user and bearer token`() = runBlocking {
        server.enqueue(MockResponse().setBody(completionJson("ciao")))

        val result = client.sendMessage(
            httpUrl = endpoint,
            message = "hello",
            sessionId = "install-uuid",
            authToken = "secret-token",
            modelName = "telegram-agent",
        )

        assertTrue(result.isSuccess)
        assertEquals("ciao", result.getOrNull()?.getResponseText())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"))

        val body = JsonParser.parseString(recorded.body.readUtf8()) as JsonObject
        assertFalse(body.get("stream").asBoolean)
        assertEquals("telegram-agent", body.get("model").asString)
        assertEquals("install-uuid", body.get("user").asString)
        assertEquals(
            "hello",
            body.getAsJsonArray("messages").first().asJsonObject.get("content").asString
        )
    }

    // --- Empty completions are failures (Spec 001 §B.4) ---

    @Test
    fun `empty completion content is a failure not a TTS input`() = runBlocking {
        server.enqueue(MockResponse().setBody(completionJson("")))

        val result = client.sendMessage(endpoint, "hi", "user")

        assertTrue(result.isFailure)
        assertEquals("Empty completion", result.exceptionOrNull()?.message)
    }

    @Test
    fun `unrecognized json is never passed through as reply text`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"unexpected":"shape"}"""))

        val result = client.sendMessage(endpoint, "hi", "user")

        assertTrue(result.isFailure)
    }

    @Test
    fun `api error object surfaces as a failure with its message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"error":{"message":"model overloaded"}}""")
        )

        val result = client.sendMessage(endpoint, "hi", "user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("model overloaded"))
    }

    @Test
    fun `http 401 is a clear recoverable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.sendMessage(endpoint, "hi", "user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
    }

    // --- Endpoint validation (Spec 001 §B.5) ---

    @Test
    fun `non-https endpoint fails as a configuration error without any request`() = runBlocking {
        val result = client.sendMessage(
            httpUrl = "http://bridge.italia.ae/v1/chat/completions",
            message = "hi",
            sessionId = "user",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `base url without the completions path is rejected not rewritten`() = runBlocking {
        val result = client.sendMessage(
            httpUrl = server.url("/").toString(),
            message = "hi",
            sessionId = "user",
        )

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    // --- Connection verification (Spec 001 §B.5): GET /healthz, never ping ---

    @Test
    fun `testConnection performs a single GET healthz on the origin`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))

        val result = client.testConnection(endpoint, "secret-token")

        assertTrue(result.isSuccess)
        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/healthz", recorded.path)
        // Verification must never create a Telegram message.
        assertEquals(0, recorded.bodySize)
    }

    @Test
    fun `testConnection reports failure on unhealthy origin`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.testConnection(endpoint, null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("503"))
    }

    @Test
    fun `testConnection rejects an invalid endpoint without touching the network`() = runBlocking {
        val result = client.testConnection("https://bridge.italia.ae", null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    // --- Cancellation (Spec 001 §B.2/§D) ---

    @Test
    fun `cancelling the coroutine aborts the in-flight call immediately`() = runBlocking {
        // The server accepts the request but never answers, like CTB holding
        // the connection while it waits for a Telegram reply.
        server.enqueue(
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        )

        var completedNormally = false
        val job: Job = launch {
            client.sendMessage(endpoint, "hi", "user")
            completedNormally = true
        }

        delay(300)
        val cancelStarted = System.currentTimeMillis()
        job.cancel()
        job.join()
        val cancelElapsed = System.currentTimeMillis() - cancelStarted

        // A blocked execute() would sit for the full 20s header delay; a real
        // cancel releases the call at once.
        assertTrue("cancel took ${cancelElapsed}ms", cancelElapsed < 2_000)
        assertFalse(completedNormally)
        assertTrue(job.isCancelled)
    }
}
