package com.givemeurhand.backend

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.config.AppConfig
import com.givemeurhand.backend.deepseek.HttpDeepSeekClient
import com.givemeurhand.backend.rag.MongoChunkRepository
import com.givemeurhand.backend.routes.chatRoutes
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bson.Document

fun main() {
    val config = AppConfig.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val httpClient = HttpClient(CIO) { install(ClientContentNegotiation) { json() } }
    val deepSeekClient = HttpDeepSeekClient(httpClient, config.deepSeekBaseUrl, config.deepSeekApiKey, config.deepSeekModel)

    val mongoClient = MongoClient.create(config.mongoUri)
    val database = mongoClient.getDatabase(config.mongoDatabase)
    val chunkRepository = MongoChunkRepository(database.getCollection<Document>("knowledge_chunks"))

    // Task 3 of the "Professional Coordination" plan replaces this line with a
    // Mongo-backed AssignmentService — nothing else in this file changes.
    val assignmentService = FallbackOnlyAssignmentService(config.fallbackHelpPhone)

    val agent = ChatAgent(deepSeekClient, chunkRepository, assignmentService)

    embeddedServer(Netty, port = port) { module(agent) }.start(wait = true)
}

fun Application.module(agent: ChatAgent) {
    install(ServerContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
        chatRoutes(agent)
    }
}
