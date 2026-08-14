// backend/src/main/kotlin/com/givemeurhand/backend/routes/ChatRoutes.kt
package com.givemeurhand.backend.routes

import com.givemeurhand.backend.agent.AgentResult
import com.givemeurhand.backend.agent.ChatAgent
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(val sessionId: String, val message: String)

@Serializable
data class ChatResponse(val reply: String, val kind: String)

private const val TECHNICAL_ERROR_MESSAGE =
    "Hubo un problema técnico, por favor intenta de nuevo en unos minutos."

fun Route.chatRoutes(agent: ChatAgent) {
    post("/chat") {
        val request = call.receive<ChatRequest>()
        val result = runCatching { agent.handle(request.sessionId, request.message) }
            .recoverCatching { agent.handle(request.sessionId, request.message) }
            .getOrElse { AgentResult(TECHNICAL_ERROR_MESSAGE, "error") }
        call.respond(ChatResponse(result.reply, result.kind))
    }
}
