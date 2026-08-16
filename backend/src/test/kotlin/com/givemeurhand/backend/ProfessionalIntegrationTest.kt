// backend/src/test/kotlin/com/givemeurhand/backend/ProfessionalIntegrationTest.kt
package com.givemeurhand.backend

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.agent.FakeDeepSeekClient
import com.givemeurhand.backend.agent.FakeMemoryService
import com.givemeurhand.backend.alarm.AlarmCriteria
import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.professional.FakeAssignmentRepository
import com.givemeurhand.backend.professional.FakeProfessionalRepository
import com.givemeurhand.backend.professional.JwtService
import com.givemeurhand.backend.professional.PasswordHasher
import com.givemeurhand.backend.professional.Professional
import com.givemeurhand.backend.rag.FakeChunkRepository
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration tests wired at the [module] seam (the same entry point [main] uses), covering
 * findings from the final whole-branch review:
 *  - finding 1: a malformed case id on POST /professionals/cases/{id}/close returns 404
 *    instead of crashing into the app-wide error handler.
 *  - finding 2: covered indirectly - a 401 for a missing token confirms the professional
 *    routes are reachable through the real module() wiring, not just in route-level tests.
 */
class ProfessionalIntegrationTest {
    private val jwtService = JwtService("integration-test-secret")

    private val alarmCriteria = AlarmCriteria(
        version = 1,
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        classificationPromptText = "criterios de prueba",
        controlStrategiesText = "estrategias de prueba"
    )

    private fun agent() = ChatAgent(
        FakeDeepSeekClient(),
        FakeChunkRepository(emptyMap()),
        FallbackOnlyAssignmentService("+57 3219699131"),
        FakeMemoryService(),
        alarmCriteria,
        "+57 3219699131",
        2
    )

    private fun professionalDeps(
        professionals: List<Professional> = emptyList(),
        assignments: FakeAssignmentRepository = FakeAssignmentRepository()
    ) = ProfessionalRouteDeps(
        professionalRepository = FakeProfessionalRepository(professionals),
        assignmentRepository = assignments,
        jwtService = jwtService
    )

    @Test
    fun `closing a case with a malformed id returns 404 through the real module wiring`() = testApplication {
        val professional = Professional(
            id = "p1", name = "Ana", phone = "+57 1", username = "ana",
            passwordHash = PasswordHasher.hash("clave123"), active = true
        )
        val deps = professionalDeps(professionals = listOf(professional))
        application { module(agent(), deps) }
        val token = jwtService.issue("p1")

        val response = client.post("/professionals/cases/not-a-valid-objectid/close") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `listing cases with no Authorization header returns 401 through the real module wiring`() = testApplication {
        val deps = professionalDeps()
        application { module(agent(), deps) }

        val response = client.get("/professionals/me/cases")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
