package com.givemeurhand.backend

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.routes.chatRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    // Real wiring (DeepSeek/Mongo/config) is added in Task 11.
}

fun Application.module(agent: ChatAgent) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
        chatRoutes(agent)
    }
}
