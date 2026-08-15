// backend/src/main/kotlin/com/givemeurhand/backend/routes/ProfessionalRoutes.kt
package com.givemeurhand.backend.routes

import com.givemeurhand.backend.professional.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.Serializable

// Precomputed once so verifying against it costs roughly the same as verifying a real
// password hash, without paying the bcrypt cost again on every login attempt (which would
// itself become a timing signal).
private val dummyPasswordHash: String by lazy { PasswordHasher.hash("dummy-password-for-timing-safety") }

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class CaseResponse(
    val id: String,
    val reasonSnippet: String,
    val status: String,
    val assignedAt: String,
    val closedAt: String?
)

private fun Assignment.toResponse() = CaseResponse(
    id = id,
    reasonSnippet = reasonSnippet,
    status = status,
    assignedAt = assignedAt.toString(),
    closedAt = closedAt?.toString()
)

private suspend fun PipelineContext<Unit, ApplicationCall>.requireProfessional(
    jwtService: JwtService,
    professionalRepository: ProfessionalRepository,
    handler: suspend (professionalId: String) -> Unit
) {
    val header = call.request.headers[HttpHeaders.Authorization]
    val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
    val professionalId = token?.let { jwtService.verify(it) }
    val professional = professionalId?.let { professionalRepository.findById(it) }
    if (professionalId == null || professional == null || !professional.active) {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }
    handler(professionalId)
}

fun Route.professionalRoutes(
    professionalRepository: ProfessionalRepository,
    assignmentRepository: AssignmentRepository,
    jwtService: JwtService
) {
    post("/professionals/login") {
        val request = call.receive<LoginRequest>()
        val professional = professionalRepository.findByUsername(request.username)
        if (professional == null) {
            // Run a bcrypt comparison against a dummy hash even though there's no account,
            // so a nonexistent username doesn't short-circuit and leak via response timing.
            PasswordHasher.verify(request.password, dummyPasswordHash)
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        if (!professional.active || !PasswordHasher.verify(request.password, professional.passwordHash)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        call.respond(LoginResponse(jwtService.issue(professional.id)))
    }

    get("/professionals/me/cases") {
        requireProfessional(jwtService, professionalRepository) { professionalId ->
            val cases = assignmentRepository.findByProfessional(professionalId)
                .sortedWith(compareBy<Assignment> { it.status != "active" }.thenByDescending { it.assignedAt })
                .map { it.toResponse() }
            call.respond(cases)
        }
    }

    post("/professionals/cases/{id}/close") {
        requireProfessional(jwtService, professionalRepository) { professionalId ->
            val caseId = call.parameters["id"]
            when {
                caseId == null -> call.respond(HttpStatusCode.BadRequest)
                assignmentRepository.close(caseId, professionalId) -> call.respond(HttpStatusCode.OK)
                else -> call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
