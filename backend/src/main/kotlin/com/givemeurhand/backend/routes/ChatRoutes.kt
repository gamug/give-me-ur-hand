// backend/src/main/kotlin/com/givemeurhand/backend/routes/ChatRoutes.kt
package com.givemeurhand.backend.routes

import com.givemeurhand.backend.agent.AgentResult
import com.givemeurhand.backend.agent.ChatAgent
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ChatRequest(val sessionId: String, val message: String)

@Serializable
data class ChatResponse(val reply: String, val kind: String)

const val TECHNICAL_ERROR_MESSAGE =
    "Hubo un problema técnico, por favor intenta de nuevo en unos minutos."

private const val MESSAGE_MAX_LENGTH = 4000
private const val INVALID_MESSAGE_ERROR =
    "El mensaje no puede estar vacío ni superar los 4000 caracteres."

private val logger = LoggerFactory.getLogger("com.givemeurhand.backend.routes.ChatRoutes")

fun Route.chatRoutes(agent: ChatAgent) {
    post("/chat") {
        val request = call.receive<ChatRequest>()

        if (request.message.isBlank() || request.message.length > MESSAGE_MAX_LENGTH) {
            call.respond(
                HttpStatusCode.BadRequest,
                ChatResponse(INVALID_MESSAGE_ERROR, "error")
            )
            return@post
        }

        val result = try {
            agent.handle(request.sessionId, request.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                agent.handle(request.sessionId, request.message)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                logger.error("Error handling /chat request after retry", e2)
                AgentResult(TECHNICAL_ERROR_MESSAGE, "error")
            }
        }
        call.respond(ChatResponse(result.reply, result.kind))
    }
}
