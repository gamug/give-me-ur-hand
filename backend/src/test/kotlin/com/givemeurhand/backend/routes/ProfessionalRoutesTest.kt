// backend/src/test/kotlin/com/givemeurhand/backend/routes/ProfessionalRoutesTest.kt
package com.givemeurhand.backend.routes

import com.givemeurhand.backend.professional.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfessionalRoutesTest {
    private val jwtService = JwtService("test-secret")

    private fun professional(username: String, password: String) = Professional(
        id = "p1", name = "Ana", phone = "+57 1", username = username,
        passwordHash = PasswordHasher.hash(password), active = true
    )

    @Test
    fun `login with correct credentials returns a token`() = testApplication {
        val profs = FakeProfessionalRepository(listOf(professional("ana", "clave123")))
        val assignments = FakeAssignmentRepository()
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(profs, assignments, jwtService) }
        }

        val response = client.post("/professionals/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ana","password":"clave123"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"token\""))
    }

    @Test
    fun `login with wrong password returns 401`() = testApplication {
        val profs = FakeProfessionalRepository(listOf(professional("ana", "clave123")))
        val assignments = FakeAssignmentRepository()
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(profs, assignments, jwtService) }
        }

        val response = client.post("/professionals/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ana","password":"incorrecta"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `listing cases without a token returns 401`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(FakeProfessionalRepository(emptyList()), FakeAssignmentRepository(), jwtService) }
        }

        val response = client.get("/professionals/me/cases")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `listing cases with a valid token returns the professional's cases`() = testApplication {
        val assignments = FakeAssignmentRepository()
        assignments.seed("p1", java.time.Instant.parse("2026-08-14T10:00:00Z"))
        val profs = FakeProfessionalRepository(listOf(professional("ana", "clave123")))
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(profs, assignments, jwtService) }
        }
        val token = jwtService.issue("p1")

        val response = client.get("/professionals/me/cases") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"active\""))
    }

    @Test
    fun `closing a case owned by the professional returns 200`() = testApplication {
        val assignments = FakeAssignmentRepository()
        val created = assignments.create("p1", "session-1", "necesito ayuda")
        val profs = FakeProfessionalRepository(listOf(professional("ana", "clave123")))
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(profs, assignments, jwtService) }
        }
        val token = jwtService.issue("p1")

        val response = client.post("/professionals/cases/${created.id}/close") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `closing a case owned by another professional returns 404`() = testApplication {
        val assignments = FakeAssignmentRepository()
        val created = assignments.create("someone-else", "session-1", "necesito ayuda")
        val profs = FakeProfessionalRepository(listOf(professional("ana", "clave123")))
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(profs, assignments, jwtService) }
        }
        val token = jwtService.issue("p1")

        val response = client.post("/professionals/cases/${created.id}/close") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `a deactivated professional's token is rejected`() = testApplication {
        val assignments = FakeAssignmentRepository()
        assignments.seed("p1", java.time.Instant.parse("2026-08-14T10:00:00Z"))
        val inactiveProfessional = professional("ana", "clave123").copy(active = false)
        val profs = FakeProfessionalRepository(listOf(inactiveProfessional))
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(profs, assignments, jwtService) }
        }
        val token = jwtService.issue("p1")

        val response = client.get("/professionals/me/cases") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a bare token without the Bearer prefix is rejected`() = testApplication {
        val profs = FakeProfessionalRepository(listOf(professional("ana", "clave123")))
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(profs, FakeAssignmentRepository(), jwtService) }
        }
        val token = jwtService.issue("p1")

        val response = client.get("/professionals/me/cases") {
            header(HttpHeaders.Authorization, token)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
