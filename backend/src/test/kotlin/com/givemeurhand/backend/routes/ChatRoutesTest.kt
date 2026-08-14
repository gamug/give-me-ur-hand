// backend/src/test/kotlin/com/givemeurhand/backend/routes/ChatRoutesTest.kt
package com.givemeurhand.backend.routes

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.agent.FakeDeepSeekClient
import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.module
import com.givemeurhand.backend.rag.FakeChunkRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoutesTest {
    @Test
    fun `POST chat returns the agent result as JSON`() = testApplication {
        val fake = FakeDeepSeekClient(mutableListOf(
            "cual es la capital de francia", "PREGUNTA_NORMAL", """["r1","r2","r3"]"""
        ))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))
        application { module(agent) }
        client.config { install(ContentNegotiation) { json() } }

        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"s1","message":"cual es la kapital de francia"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"kind\":\"out_of_scope\""))
    }

    @Test
    fun `DeepSeek failure on both attempts returns a generic error kind`() = testApplication {
        val throwing = object : DeepSeekClient {
            override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
                throw RuntimeException("boom")
            }
        }
        val agent = ChatAgent(throwing, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))
        application { module(agent) }
        client.config { install(ContentNegotiation) { json() } }

        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"s1","message":"hola"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"kind\":\"error\""))
    }
}
