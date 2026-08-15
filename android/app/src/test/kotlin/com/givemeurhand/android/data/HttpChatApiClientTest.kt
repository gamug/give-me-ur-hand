package com.givemeurhand.android.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpChatApiClientTest {
    @Test
    fun `posts to baseUrl chat and parses the response`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"reply":"hola","kind":"answer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client: ChatApiClient = HttpChatApiClient(httpClient, "http://localhost:8080")

        val result = client.sendMessage("session-1", "hola")

        assertEquals(ChatResponse("hola", "answer"), result)
        assertEquals("http://localhost:8080/chat", capturedUrl)
    }
}
