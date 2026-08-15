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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpProfessionalApiClientTest {
    @Test
    fun `login stores the token on success`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"token":"abc123"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { expectSuccess = true; install(ContentNegotiation) { json() } }
        val tokenStore = InMemoryTokenStore()
        val client: ProfessionalApiClient = HttpProfessionalApiClient(httpClient, "http://localhost:8080", tokenStore)

        assertTrue(client.login("ana", "clave123"))
        assertEquals("abc123", tokenStore.get())
    }

    @Test
    fun `login returns false on 401`() = runTest {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
        val httpClient = HttpClient(engine) { expectSuccess = true; install(ContentNegotiation) { json() } }
        val client: ProfessionalApiClient =
            HttpProfessionalApiClient(httpClient, "http://localhost:8080", InMemoryTokenStore())

        assertFalse(client.login("ana", "mala-clave"))
    }

    @Test
    fun `listCases sends the stored token as a bearer header`() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = """[{"id":"1","reasonSnippet":"necesito ayuda","status":"active","assignedAt":"2026-08-14T10:00:00Z","closedAt":null}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { expectSuccess = true; install(ContentNegotiation) { json() } }
        val tokenStore = InMemoryTokenStore().apply { set("abc123") }
        val client: ProfessionalApiClient = HttpProfessionalApiClient(httpClient, "http://localhost:8080", tokenStore)

        val cases = client.listCases()

        assertEquals(1, cases.size)
        assertEquals("Bearer abc123", capturedAuth)
    }
}
