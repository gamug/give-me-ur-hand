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
    handler: suspend (professionalId: String) -> Unit
) {
    val header = call.request.headers[HttpHeaders.Authorization]
    val token = header?.removePrefix("Bearer ")?.trim()
    val professionalId = token?.let { jwtService.verify(it) }
    if (professionalId == null) {
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
        if (professional == null || !professional.active || !PasswordHasher.verify(request.password, professional.passwordHash)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        call.respond(LoginResponse(jwtService.issue(professional.id)))
    }

    get("/professionals/me/cases") {
        requireProfessional(jwtService) { professionalId ->
            val cases = assignmentRepository.findByProfessional(professionalId)
                .sortedWith(compareBy<Assignment> { it.status != "active" }.thenByDescending { it.assignedAt })
                .map { it.toResponse() }
            call.respond(cases)
        }
    }

    post("/professionals/cases/{id}/close") {
        requireProfessional(jwtService) { professionalId ->
            val caseId = call.parameters["id"]
            when {
                caseId == null -> call.respond(HttpStatusCode.BadRequest)
                assignmentRepository.close(caseId, professionalId) -> call.respond(HttpStatusCode.OK)
                else -> call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
