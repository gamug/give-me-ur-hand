// backend/src/test/kotlin/com/givemeurhand/backend/deepseek/HttpDeepSeekClientTest.kt
package com.givemeurhand.backend.deepseek

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    fun `parses a real DeepSeek response containing extra top-level fields`() = runTest {
        // DeepSeek's actual API response includes fields beyond what DeepSeekChatResponse
        // declares (id, object, created, model, usage, system_fingerprint, etc.). The client's
        // ContentNegotiation must be configured with ignoreUnknownKeys = true or every real
        // call fails deserialization even though our fixture-only tests above still pass.
        val realisticBody = """
            {
              "id": "876f10d4-c194-49a4-8025-907a796ceb1e",
              "object": "chat.completion",
              "created": 1786830248,
              "model": "deepseek-v4-flash",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "hola limpio" },
                  "logprobs": null,
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 5,
                "completion_tokens": 79,
                "total_tokens": 84,
                "prompt_tokens_details": { "cached_tokens": 0 },
                "prompt_cache_hit_tokens": 0,
                "prompt_cache_miss_tokens": 5
              },
              "system_fingerprint": "a26a7955944dc5c60445bff77fac9c8e"
            }
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = realisticBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client: DeepSeekClient = HttpDeepSeekClient(httpClient, "https://api.deepseek.com", "test-key", "deepseek-chat")

        val result = client.complete("system prompt", "hola mundo")

        assertEquals("hola limpio", result)
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
