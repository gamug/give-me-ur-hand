package com.givemeurhand.backend

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.config.AppConfig
import com.givemeurhand.backend.deepseek.HttpDeepSeekClient
import com.givemeurhand.backend.memory.CHAT_MESSAGES_COLLECTION
import com.givemeurhand.backend.memory.MemoryService
import com.givemeurhand.backend.memory.MongoChatMessageRepository
import com.givemeurhand.backend.memory.MongoSessionMemoryRepository
import com.givemeurhand.backend.memory.SESSION_MEMORY_COLLECTION
import com.givemeurhand.backend.professional.ASSIGNMENTS_COLLECTION
import com.givemeurhand.backend.professional.AssignmentRepository
import com.givemeurhand.backend.professional.JwtService
import com.givemeurhand.backend.professional.LoadBalancedAssignmentService
import com.givemeurhand.backend.professional.MongoAssignmentRepository
import com.givemeurhand.backend.professional.MongoProfessionalRepository
import com.givemeurhand.backend.professional.PROFESSIONALS_COLLECTION
import com.givemeurhand.backend.professional.ProfessionalRepository
import com.givemeurhand.backend.rag.KNOWLEDGE_CHUNKS_COLLECTION
import com.givemeurhand.backend.rag.MongoChunkRepository
import com.givemeurhand.backend.routes.TECHNICAL_ERROR_MESSAGE
import com.givemeurhand.backend.routes.ChatResponse
import com.givemeurhand.backend.routes.chatRoutes
import com.givemeurhand.backend.routes.professionalRoutes
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bson.Document
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.givemeurhand.backend.Application")

data class ProfessionalRouteDeps(
    val professionalRepository: ProfessionalRepository,
    val assignmentRepository: AssignmentRepository,
    val jwtService: JwtService
)

fun main() {
    val config = AppConfig.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }
    val deepSeekClient = HttpDeepSeekClient(httpClient, config.deepSeekBaseUrl, config.deepSeekApiKey, config.deepSeekModel)

    val mongoClient = MongoClient.create(config.mongoUri)
    val database = mongoClient.getDatabase(config.mongoDatabase)
    val chunkRepository = MongoChunkRepository(database.getCollection<Document>(KNOWLEDGE_CHUNKS_COLLECTION))
    val professionalRepository = MongoProfessionalRepository(database.getCollection<Document>(PROFESSIONALS_COLLECTION))
    val assignmentRepository = MongoAssignmentRepository(database.getCollection<Document>(ASSIGNMENTS_COLLECTION))
    val jwtService = JwtService(config.jwtSecret)
    val chatMessageRepository = MongoChatMessageRepository(database.getCollection<Document>(CHAT_MESSAGES_COLLECTION))
    val sessionMemoryRepository = MongoSessionMemoryRepository(database.getCollection<Document>(SESSION_MEMORY_COLLECTION))

    val assignmentService: AssignmentService = LoadBalancedAssignmentService(
        professionalRepository, assignmentRepository, config.fallbackHelpPhone, config.assignmentMaxAgeHours
    )
    val memoryService = MemoryService(chatMessageRepository, sessionMemoryRepository, config.monitorIntervalMessages)

    val agent = ChatAgent(deepSeekClient, chunkRepository, assignmentService, memoryService)
    val professionalDeps = ProfessionalRouteDeps(professionalRepository, assignmentRepository, jwtService)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            mongoClient.close()
            httpClient.close()
        }
    )

    embeddedServer(Netty, port = port) { module(agent, professionalDeps) }.start(wait = true)
}

fun Application.module(agent: ChatAgent, professionalDeps: ProfessionalRouteDeps? = null) {
    install(ServerContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception while processing request", cause)
            if (call.request.path().startsWith("/professionals")) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Solicitud inválida"))
            } else {
                call.respond(HttpStatusCode.BadRequest, ChatResponse(TECHNICAL_ERROR_MESSAGE, "error"))
            }
        }
    }
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
        chatRoutes(agent)
        professionalDeps?.let { professionalRoutes(it.professionalRepository, it.assignmentRepository, it.jwtService) }
    }
}
