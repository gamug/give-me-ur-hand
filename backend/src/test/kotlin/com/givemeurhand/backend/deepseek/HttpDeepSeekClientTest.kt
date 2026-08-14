// backend/src/test/kotlin/com/givemeurhand/backend/deepseek/HttpDeepSeekClientTest.kt
package com.givemeurhand.backend.deepseek

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpDeepSeekClientTest {
    @Test
    fun `sends OpenAI-compatible request and parses the reply`() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"hola limpio"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client: DeepSeekClient = HttpDeepSeekClient(httpClient, "https://api.deepseek.com", "test-key", "deepseek-chat")

        val result = client.complete("system prompt", "hola mundo")

        assertEquals("hola limpio", result)
        assertTrue(capturedAuth == "Bearer test-key")
    }

    @Test
    fun `throws DeepSeekException when there are no choices`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"choices":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client: DeepSeekClient = HttpDeepSeekClient(httpClient, "https://api.deepseek.com", "test-key", "deepseek-chat")

        try {
            client.complete("system", "user")
            error("should have thrown")
        } catch (e: DeepSeekException) {
            // expected
        }
    }
}
