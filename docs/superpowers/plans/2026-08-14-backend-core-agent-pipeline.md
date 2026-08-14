# Backend Core + Agent Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the `backend/` Ktor service with a working `POST /chat` endpoint that runs the full agent pipeline (standardize → classify → expand → RAG search → resolve) against DeepSeek and MongoDB Atlas Search.

**Architecture:** Ktor server, one pipeline step per file behind small interfaces (`DeepSeekClient`, `ChunkRepository`, `AssignmentService`) so each step is unit-testable with fakes. `ChatAgent` orchestrates the steps. This plan ships a `FallbackOnlyAssignmentService` stub for the human-help path — the real professional-assignment logic is swapped in by the "Professional Coordination" plan without touching `ChatAgent`.

**Tech Stack:** Kotlin, Ktor server (Netty engine) + Ktor client (CIO engine), kotlinx.serialization, MongoDB Kotlin coroutine driver, JUnit 5, MockK, ktor-client-mock.

**Spec:** `docs/superpowers/specs/2026-08-14-give-me-ur-hand-mvp-design.md`

## Global Constraints

- All backend secrets/config come from environment variables only — never hardcoded: `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL` (default `https://api.deepseek.com`), `DEEPSEEK_MODEL` (default `deepseek-chat`), `MONGODB_URI`, `MONGODB_DATABASE` (default `give_me_ur_hand`), `JWT_SECRET`, `FALLBACK_HELP_PHONE` (default `+57 3219699131`), `ASSIGNMENT_MAX_AGE_HOURS` (default `4`).
- All user-facing text (agent replies, error messages) must be in Spanish.
- Agent pipeline order is fixed: estandarizar → clasificar intención → (si aplica) expandir consulta → RAG search → resolución.
- Safety rule: `HUMAN_HELP_EXPLICIT` and `CRISIS_RISK` both route to the human-help path, even though only explicit requests were in the original ask.
- `knowledge_chunks` collection and its Atlas Search index (`"default"`, standard analyzer, on field `text`) are assumed to already exist (created via Atlas UI, documented in the ingestion plan) — this plan does not create the index.
- Package root: `com.givemeurhand.backend`.

---

### Task 1: Backend project scaffold + health check

**Files:**
- Create: `backend/settings.gradle.kts`
- Create: `backend/build.gradle.kts`
- Create: `backend/gradle.properties`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/Application.kt`
- Create: `backend/src/test/kotlin/com/givemeurhand/backend/ApplicationTest.kt`

**Interfaces:**
- Produces: a running Ktor `Application` module (`Application.module()`) and a `GET /health` route later tasks add routes alongside.

- [ ] **Step 1: Create the Gradle scaffold**

`backend/settings.gradle.kts`:
```kotlin
rootProject.name = "give-me-ur-hand-backend"
```

`backend/gradle.properties`:
```properties
kotlin.code.style=official
```

`backend/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "com.givemeurhand"
version = "0.1.0"

repositories { mavenCentral() }

val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("org.mongodb:mongodb-driver-kotlin-coroutine:5.1.2")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.mockk:mockk:1.13.11")
}

application {
    mainClass.set("com.givemeurhand.backend.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/ApplicationTest.kt
package com.givemeurhand.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `health check returns 200`() = testApplication {
        application { module() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ApplicationTest"`
Expected: FAIL — compile error, `module()` does not exist yet.

- [ ] **Step 4: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/Application.kt
package com.givemeurhand.backend

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.ApplicationTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties backend/src/main/kotlin/com/givemeurhand/backend/Application.kt backend/src/test/kotlin/com/givemeurhand/backend/ApplicationTest.kt
git commit -m "feat(backend): scaffold Ktor server with health check"
```

---

### Task 2: AppConfig (environment variable loading)

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/config/AppConfig.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/config/AppConfigTest.kt`

**Interfaces:**
- Produces: `data class AppConfig(deepSeekApiKey: String, deepSeekBaseUrl: String, deepSeekModel: String, mongoUri: String, mongoDatabase: String, jwtSecret: String, fallbackHelpPhone: String, assignmentMaxAgeHours: Long)` and `AppConfig.fromEnv(env: Map<String, String> = System.getenv()): AppConfig`.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/config/AppConfigTest.kt
package com.givemeurhand.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    private val requiredOnly = mapOf(
        "DEEPSEEK_API_KEY" to "key-123",
        "MONGODB_URI" to "mongodb+srv://example",
        "JWT_SECRET" to "secret-123"
    )

    @Test
    fun `applies defaults when optional vars are missing`() {
        val config = AppConfig.fromEnv(requiredOnly)
        assertEquals("https://api.deepseek.com", config.deepSeekBaseUrl)
        assertEquals("deepseek-chat", config.deepSeekModel)
        assertEquals("give_me_ur_hand", config.mongoDatabase)
        assertEquals("+57 3219699131", config.fallbackHelpPhone)
        assertEquals(4L, config.assignmentMaxAgeHours)
    }

    @Test
    fun `throws when a required var is missing`() {
        assertFailsWith<IllegalStateException> {
            AppConfig.fromEnv(mapOf("MONGODB_URI" to "x", "JWT_SECRET" to "y"))
        }
    }

    @Test
    fun `reads overrides when present`() {
        val config = AppConfig.fromEnv(requiredOnly + mapOf("ASSIGNMENT_MAX_AGE_HOURS" to "6"))
        assertEquals(6L, config.assignmentMaxAgeHours)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.config.AppConfigTest"`
Expected: FAIL — `AppConfig` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/config/AppConfig.kt
package com.givemeurhand.backend.config

data class AppConfig(
    val deepSeekApiKey: String,
    val deepSeekBaseUrl: String,
    val deepSeekModel: String,
    val mongoUri: String,
    val mongoDatabase: String,
    val jwtSecret: String,
    val fallbackHelpPhone: String,
    val assignmentMaxAgeHours: Long
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(key: String): String =
                env[key] ?: error("Falta variable de entorno requerida: $key")

            return AppConfig(
                deepSeekApiKey = required("DEEPSEEK_API_KEY"),
                deepSeekBaseUrl = env["DEEPSEEK_BASE_URL"] ?: "https://api.deepseek.com",
                deepSeekModel = env["DEEPSEEK_MODEL"] ?: "deepseek-chat",
                mongoUri = required("MONGODB_URI"),
                mongoDatabase = env["MONGODB_DATABASE"] ?: "give_me_ur_hand",
                jwtSecret = required("JWT_SECRET"),
                fallbackHelpPhone = env["FALLBACK_HELP_PHONE"] ?: "+57 3219699131",
                assignmentMaxAgeHours = (env["ASSIGNMENT_MAX_AGE_HOURS"] ?: "4").toLong()
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.config.AppConfigTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/config/AppConfig.kt backend/src/test/kotlin/com/givemeurhand/backend/config/AppConfigTest.kt
git commit -m "feat(backend): load configuration from environment variables"
```

---

### Task 3: DeepSeek client (DTOs + interface + HTTP implementation)

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/deepseek/DeepSeekModels.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/deepseek/DeepSeekClient.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/deepseek/HttpDeepSeekClientTest.kt`

**Interfaces:**
- Produces: `interface DeepSeekClient { suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double = 0.3): String }` and `class HttpDeepSeekClient(httpClient: HttpClient, baseUrl: String, apiKey: String, model: String) : DeepSeekClient`. `class DeepSeekException(message: String) : Exception(message)`.
- Consumes: nothing from earlier tasks (standalone).

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/deepseek/HttpDeepSeekClientTest.kt
package com.givemeurhand.backend.deepseek

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpDeepSeekClientTest {
    @Test
    fun `sends OpenAI-compatible request and parses the reply`() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"hola limpio"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client: DeepSeekClient = HttpDeepSeekClient(httpClient, "https://api.deepseek.com", "test-key", "deepseek-chat")

        val result = client.complete("system prompt", "hola mundo")

        assertEquals("hola limpio", result)
        assertTrue(capturedAuth == "Bearer test-key")
    }

    @Test
    fun `throws DeepSeekException when there are no choices`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"choices":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client: DeepSeekClient = HttpDeepSeekClient(httpClient, "https://api.deepseek.com", "test-key", "deepseek-chat")

        try {
            client.complete("system", "user")
            error("should have thrown")
        } catch (e: DeepSeekException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.deepseek.HttpDeepSeekClientTest"`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/deepseek/DeepSeekModels.kt
package com.givemeurhand.backend.deepseek

import kotlinx.serialization.Serializable

@Serializable
data class DeepSeekMessage(val role: String, val content: String)

@Serializable
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val temperature: Double
)

@Serializable
data class DeepSeekChoice(val message: DeepSeekMessage)

@Serializable
data class DeepSeekChatResponse(val choices: List<DeepSeekChoice>)
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/deepseek/DeepSeekClient.kt
package com.givemeurhand.backend.deepseek

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

interface DeepSeekClient {
    suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double = 0.3): String
}

class DeepSeekException(message: String) : Exception(message)

class HttpDeepSeekClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) : DeepSeekClient {
    override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
        val response: DeepSeekChatResponse = httpClient.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                DeepSeekChatRequest(
                    model = model,
                    messages = listOf(
                        DeepSeekMessage("system", systemPrompt),
                        DeepSeekMessage("user", userPrompt)
                    ),
                    temperature = temperature
                )
            )
        }.body()

        return response.choices.firstOrNull()?.message?.content
            ?: throw DeepSeekException("Respuesta vacía de DeepSeek")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.deepseek.HttpDeepSeekClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/deepseek/ backend/src/test/kotlin/com/givemeurhand/backend/deepseek/
git commit -m "feat(backend): add DeepSeek HTTP client"
```

---

### Task 4: StandardizeStep

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/agent/StandardizeStep.kt`
- Create: `backend/src/test/kotlin/com/givemeurhand/backend/agent/FakeDeepSeekClient.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/agent/StandardizeStepTest.kt`

**Interfaces:**
- Consumes: `DeepSeekClient` (Task 3).
- Produces: `object StandardizeStep { suspend fun run(rawInput: String, client: DeepSeekClient): String }`. `FakeDeepSeekClient` (test double reused by Tasks 5, 6, 8, 9): `class FakeDeepSeekClient(private val responses: MutableList<String> = mutableListOf(), var lastSystemPrompt: String? = null, var lastUserPrompt: String? = null) : DeepSeekClient` — `complete()` pops from `responses` in order (or throws if empty and `throwOnEmpty = true`).

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/agent/FakeDeepSeekClient.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient

class FakeDeepSeekClient(
    private val responses: MutableList<String> = mutableListOf(),
    var lastSystemPrompt: String? = null,
    var lastUserPrompt: String? = null
) : DeepSeekClient {
    var throwOnCall: Exception? = null

    override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
        throwOnCall?.let { throw it }
        lastSystemPrompt = systemPrompt
        lastUserPrompt = userPrompt
        return if (responses.isNotEmpty()) responses.removeAt(0) else ""
    }
}
```

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/agent/StandardizeStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StandardizeStepTest {
    @Test
    fun `returns the cleaned text from DeepSeek, trimmed`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("  Hola, ¿cómo estás?  "))
        val result = StandardizeStep.run("ola komo estas", fake)
        assertEquals("Hola, ¿cómo estás?", result)
        assertEquals("ola komo estas", fake.lastUserPrompt)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.StandardizeStepTest"`
Expected: FAIL — `StandardizeStep` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/agent/StandardizeStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient

object StandardizeStep {
    private const val SYSTEM_PROMPT = """Eres un corrector ortográfico y gramatical. Recibes un mensaje escrito por una persona en una zona de emergencia (terremoto) desde su celular, probablemente con errores de tipeo o acentuación. Devuelve ÚNICAMENTE el mismo mensaje corregido en español, sin agregar explicaciones, sin responder la pregunta, sin agregar información nueva. Si ya está bien escrito, devuélvelo igual."""

    suspend fun run(rawInput: String, client: DeepSeekClient): String {
        return client.complete(SYSTEM_PROMPT, rawInput).trim()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.StandardizeStepTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/agent/StandardizeStep.kt backend/src/test/kotlin/com/givemeurhand/backend/agent/
git commit -m "feat(backend): add input standardization step"
```

---

### Task 5: ClassifyStep (with fail-safe default)

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/agent/ClassifyStep.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/agent/ClassifyStepTest.kt`

**Interfaces:**
- Consumes: `DeepSeekClient` (Task 3), `FakeDeepSeekClient` (Task 4).
- Produces: `enum class Intent { HUMAN_HELP_EXPLICIT, CRISIS_RISK, NORMAL_QUESTION }`, `object ClassifyStep { suspend fun run(text: String, client: DeepSeekClient): Intent }`.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/agent/ClassifyStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifyStepTest {
    @Test
    fun `maps AYUDA_HUMANA to HUMAN_HELP_EXPLICIT`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("AYUDA_HUMANA"))
        assertEquals(Intent.HUMAN_HELP_EXPLICIT, ClassifyStep.run("quiero hablar con alguien", fake))
    }

    @Test
    fun `maps RIESGO_CRISIS to CRISIS_RISK`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("RIESGO_CRISIS"))
        assertEquals(Intent.CRISIS_RISK, ClassifyStep.run("ya no quiero vivir", fake))
    }

    @Test
    fun `maps PREGUNTA_NORMAL to NORMAL_QUESTION`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("PREGUNTA_NORMAL"))
        assertEquals(Intent.NORMAL_QUESTION, ClassifyStep.run("como manejo la ansiedad", fake))
    }

    @Test
    fun `unrecognized response fails safe to CRISIS_RISK`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("no estoy seguro"))
        assertEquals(Intent.CRISIS_RISK, ClassifyStep.run("algo raro", fake))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.ClassifyStepTest"`
Expected: FAIL — `Intent`/`ClassifyStep` don't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/agent/ClassifyStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient

enum class Intent { HUMAN_HELP_EXPLICIT, CRISIS_RISK, NORMAL_QUESTION }

object ClassifyStep {
    private const val SYSTEM_PROMPT = """Clasifica el siguiente mensaje de una persona afectada por un terremoto en EXACTAMENTE una de estas tres etiquetas. Responde solo con la etiqueta, nada más:
AYUDA_HUMANA - la persona pide explícitamente hablar con una persona, un profesional o ayuda humana directa.
RIESGO_CRISIS - hay señales de posible daño a sí mismo, a otros, o peligro de vida inmediato.
PREGUNTA_NORMAL - cualquier otro caso."""

    suspend fun run(text: String, client: DeepSeekClient): Intent {
        val raw = client.complete(SYSTEM_PROMPT, text).trim().uppercase()
        return when {
            raw.contains("AYUDA_HUMANA") -> Intent.HUMAN_HELP_EXPLICIT
            raw.contains("RIESGO_CRISIS") -> Intent.CRISIS_RISK
            raw.contains("PREGUNTA_NORMAL") -> Intent.NORMAL_QUESTION
            // Respuesta ambigua o no reconocida: por seguridad se trata como
            // posible crisis, para no dejar sin atención humana un caso real.
            else -> Intent.CRISIS_RISK
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.ClassifyStepTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/agent/ClassifyStep.kt backend/src/test/kotlin/com/givemeurhand/backend/agent/ClassifyStepTest.kt
git commit -m "feat(backend): add intent classification step with safe default"
```

---

### Task 6: ExpandStep

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/agent/ExpandStep.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/agent/ExpandStepTest.kt`

**Interfaces:**
- Consumes: `DeepSeekClient` (Task 3), `FakeDeepSeekClient` (Task 4).
- Produces: `object ExpandStep { suspend fun run(text: String, client: DeepSeekClient): List<String> }` — always returns a non-empty list (falls back to `listOf(text)` on unparsable output).

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/agent/ExpandStepTest.kt
package com.givemeurhand.backend.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpandStepTest {
    @Test
    fun `parses a JSON array of 3 reformulations`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            """["¿qué es la ansiedad?", "cómo calmar el miedo", "síntomas de estrés postraumático"]"""
        ))
        val result = ExpandStep.run("tengo ansiedad", fake)
        assertEquals(3, result.size)
        assertEquals("¿qué es la ansiedad?", result[0])
    }

    @Test
    fun `falls back to the original text when the response is not valid JSON`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("esto no es json"))
        val result = ExpandStep.run("tengo ansiedad", fake)
        assertEquals(listOf("tengo ansiedad"), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.ExpandStepTest"`
Expected: FAIL — `ExpandStep` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/agent/ExpandStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

object ExpandStep {
    private const val SYSTEM_PROMPT = """Genera 3 reformulaciones de la siguiente pregunta, cada una con una perspectiva más amplia que la original (sinónimos, contexto relacionado, ángulos distintos del mismo tema), en español. Responde ÚNICAMENTE con un array JSON de 3 strings, sin texto adicional. Formato: ["reformulación 1", "reformulación 2", "reformulación 3"]"""

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(text: String, client: DeepSeekClient): List<String> {
        val raw = client.complete(SYSTEM_PROMPT, text).trim()
        return try {
            val parsed = json.decodeFromString<List<String>>(raw)
            parsed.ifEmpty { listOf(text) }
        } catch (e: Exception) {
            listOf(text)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.ExpandStepTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/agent/ExpandStep.kt backend/src/test/kotlin/com/givemeurhand/backend/agent/ExpandStepTest.kt
git commit -m "feat(backend): add query expansion step"
```

---

### Task 7: Chunk model, ChunkRepository, RagSearchStep (merge/dedupe)

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/rag/Chunk.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/rag/ChunkRepository.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/rag/RagSearchStep.kt`
- Create: `backend/src/test/kotlin/com/givemeurhand/backend/rag/FakeChunkRepository.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/rag/RagSearchStepTest.kt`

**Interfaces:**
- Produces: `data class Chunk(val id: String, val text: String, val sourceDocument: String, val page: Int, val chunkIndex: Int, val score: Double = 0.0)`. `interface ChunkRepository { suspend fun search(query: String, limit: Int): List<Chunk> }`. `object RagSearchStep { suspend fun search(queries: List<String>, repository: ChunkRepository, perQueryLimit: Int = 6, finalLimit: Int = 6): List<Chunk> }`. `class FakeChunkRepository(private val byQuery: Map<String, List<Chunk>>) : ChunkRepository`.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/rag/FakeChunkRepository.kt
package com.givemeurhand.backend.rag

class FakeChunkRepository(private val byQuery: Map<String, List<Chunk>>) : ChunkRepository {
    override suspend fun search(query: String, limit: Int): List<Chunk> =
        (byQuery[query] ?: emptyList()).take(limit)
}
```

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/rag/RagSearchStepTest.kt
package com.givemeurhand.backend.rag

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RagSearchStepTest {
    private fun chunk(id: String, score: Double) =
        Chunk(id = id, text = "texto $id", sourceDocument = "doc.pdf", page = 1, chunkIndex = 0, score = score)

    @Test
    fun `dedupes by id keeping the highest score and sorts descending`() = runTest {
        val repo = FakeChunkRepository(
            mapOf(
                "q1" to listOf(chunk("a", 0.5), chunk("b", 0.9)),
                "q2" to listOf(chunk("a", 0.8), chunk("c", 0.3))
            )
        )
        val result = RagSearchStep.search(listOf("q1", "q2"), repo, finalLimit = 6)
        assertEquals(listOf("b", "a", "c"), result.map { it.id })
        assertEquals(0.8, result.first { it.id == "a" }.score)
    }

    @Test
    fun `respects finalLimit`() = runTest {
        val repo = FakeChunkRepository(
            mapOf("q1" to listOf(chunk("a", 0.9), chunk("b", 0.8), chunk("c", 0.7)))
        )
        val result = RagSearchStep.search(listOf("q1"), repo, finalLimit = 2)
        assertEquals(2, result.size)
    }

    @Test
    fun `returns empty list when nothing matches`() = runTest {
        val repo = FakeChunkRepository(emptyMap())
        val result = RagSearchStep.search(listOf("sin resultados"), repo)
        assertEquals(emptyList(), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.rag.RagSearchStepTest"`
Expected: FAIL — types don't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/rag/Chunk.kt
package com.givemeurhand.backend.rag

data class Chunk(
    val id: String,
    val text: String,
    val sourceDocument: String,
    val page: Int,
    val chunkIndex: Int,
    val score: Double = 0.0
)
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/rag/ChunkRepository.kt
package com.givemeurhand.backend.rag

interface ChunkRepository {
    suspend fun search(query: String, limit: Int): List<Chunk>
}
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/rag/RagSearchStep.kt
package com.givemeurhand.backend.rag

object RagSearchStep {
    suspend fun search(
        queries: List<String>,
        repository: ChunkRepository,
        perQueryLimit: Int = 6,
        finalLimit: Int = 6
    ): List<Chunk> {
        val all = queries.flatMap { repository.search(it, perQueryLimit) }
        return all.groupBy { it.id }
            .map { (_, dupes) -> dupes.maxBy { it.score } }
            .sortedByDescending { it.score }
            .take(finalLimit)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.rag.RagSearchStepTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/rag/ backend/src/test/kotlin/com/givemeurhand/backend/rag/
git commit -m "feat(backend): add chunk model, repository interface and RAG merge/dedupe"
```

---

### Task 8: AnswerStep

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/agent/AnswerStep.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/agent/AnswerStepTest.kt`

**Interfaces:**
- Consumes: `DeepSeekClient` (Task 3), `Chunk` (Task 7), `FakeDeepSeekClient` (Task 4).
- Produces: `object AnswerStep { suspend fun run(question: String, chunks: List<Chunk>, client: DeepSeekClient): String }`.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/agent/AnswerStepTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.rag.Chunk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerStepTest {
    @Test
    fun `passes chunk text as context and returns the trimmed answer`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("  Respira profundo y busca un lugar seguro.  "))
        val chunks = listOf(
            Chunk(id = "1", text = "Grounding techniques help regulate breathing.", sourceDocument = "pfa.pdf", page = 3, chunkIndex = 0)
        )

        val result = AnswerStep.run("¿cómo calmo la ansiedad?", chunks, fake)

        assertEquals("Respira profundo y busca un lugar seguro.", result)
        assertTrue(fake.lastSystemPrompt!!.contains("Grounding techniques help regulate breathing."))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.AnswerStepTest"`
Expected: FAIL — `AnswerStep` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/agent/AnswerStep.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.rag.Chunk

object AnswerStep {
    private const val SYSTEM_PROMPT_TEMPLATE = """Eres un asistente de primeros auxilios psicológicos para personas afectadas por un terremoto. Responde ÚNICAMENTE en español, con un tono calmado, cercano y psicoeducativo. No diagnostiques. Basa tu respuesta SOLO en el siguiente contenido de referencia (puede estar en inglés: tradúcelo y sintetízalo al responder). Si el contenido no alcanza para responder con seguridad, sugiere buscar ayuda profesional.

Contenido de referencia:
%s"""

    suspend fun run(question: String, chunks: List<Chunk>, client: DeepSeekClient): String {
        val context = chunks.joinToString(separator = "\n---\n") { it.text }
        val systemPrompt = SYSTEM_PROMPT_TEMPLATE.format(context)
        return client.complete(systemPrompt, question).trim()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.AnswerStepTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/agent/AnswerStep.kt backend/src/test/kotlin/com/givemeurhand/backend/agent/AnswerStepTest.kt
git commit -m "feat(backend): add grounded answer generation step"
```

---

### Task 9: AssignmentService stub + ChatAgent orchestrator

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/assignment/AssignmentService.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/agent/ChatAgent.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/agent/ChatAgentTest.kt`

**Interfaces:**
- Consumes: `DeepSeekClient`, `FakeDeepSeekClient` (Task 3/4), `Intent` (Task 5), `ChunkRepository`, `FakeChunkRepository`, `Chunk`, `RagSearchStep` (Task 7), `AnswerStep` (Task 8).
- Produces: `interface AssignmentService { suspend fun assignHelper(sessionId: String, reason: String): String }`, `class FallbackOnlyAssignmentService(private val fallbackPhone: String) : AssignmentService` — **this is the seam the "Professional Coordination" plan replaces** with a real Mongo-backed implementation, without changing `ChatAgent`. `data class AgentResult(val reply: String, val kind: String)` (`kind` ∈ `"answer" | "human_help" | "out_of_scope"`). `class ChatAgent(deepSeekClient: DeepSeekClient, chunkRepository: ChunkRepository, assignmentService: AssignmentService) { suspend fun handle(sessionId: String, rawMessage: String): AgentResult }`.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/agent/ChatAgentTest.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.rag.Chunk
import com.givemeurhand.backend.rag.FakeChunkRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatAgentTest {
    private val expandJson = """["reform 1", "reform 2", "reform 3"]"""

    @Test
    fun `explicit human help request returns the fallback phone`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("Quiero hablar con alguien", "AYUDA_HUMANA"))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "kiero ablar con alguien")

        assertEquals("human_help", result.kind)
        assertEquals(true, result.reply.contains("+57 3219699131"))
    }

    @Test
    fun `crisis risk also escalates to human help`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf("ya no quiero vivir", "RIESGO_CRISIS"))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "ya no kiero vivir")

        assertEquals("human_help", result.kind)
    }

    @Test
    fun `normal question with matching chunks returns a grounded answer`() = runTest {
        val chunk = Chunk(id = "1", text = "Breathing exercises help.", sourceDocument = "pfa.pdf", page = 1, chunkIndex = 0, score = 0.9)
        val repo = FakeChunkRepository(
            mapOf(
                "como manejo la ansiedad" to listOf(chunk),
                "reform 1" to listOf(chunk),
                "reform 2" to listOf(chunk),
                "reform 3" to listOf(chunk)
            )
        )
        val fake = FakeDeepSeekClient(mutableListOf(
            "como manejo la ansiedad", // standardize
            "PREGUNTA_NORMAL",          // classify
            expandJson,                 // expand
            "Respira profundo."         // answer
        ))
        val agent = ChatAgent(fake, repo, FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "komo manejo la anciedad")

        assertEquals("answer", result.kind)
        assertEquals("Respira profundo.", result.reply)
    }

    @Test
    fun `normal question with no matching chunks returns the out-of-scope message`() = runTest {
        val fake = FakeDeepSeekClient(mutableListOf(
            "cual es la capital de francia",
            "PREGUNTA_NORMAL",
            expandJson
        ))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))

        val result = agent.handle("session-1", "cual es la kapital de francia")

        assertEquals("out_of_scope", result.kind)
        assertEquals("Tu pregunta no está relacionada con el propósito de esta aplicación.", result.reply)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.ChatAgentTest"`
Expected: FAIL — `ChatAgent`/`AssignmentService` don't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/assignment/AssignmentService.kt
package com.givemeurhand.backend.assignment

interface AssignmentService {
    suspend fun assignHelper(sessionId: String, reason: String): String
}

class FallbackOnlyAssignmentService(private val fallbackPhone: String) : AssignmentService {
    override suspend fun assignHelper(sessionId: String, reason: String): String = fallbackPhone
}
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/agent/ChatAgent.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.rag.ChunkRepository
import com.givemeurhand.backend.rag.RagSearchStep

data class AgentResult(val reply: String, val kind: String)

class ChatAgent(
    private val deepSeekClient: DeepSeekClient,
    private val chunkRepository: ChunkRepository,
    private val assignmentService: AssignmentService
) {
    suspend fun handle(sessionId: String, rawMessage: String): AgentResult {
        val clean = StandardizeStep.run(rawMessage, deepSeekClient)
        val intent = ClassifyStep.run(clean, deepSeekClient)

        if (intent == Intent.HUMAN_HELP_EXPLICIT || intent == Intent.CRISIS_RISK) {
            val phone = assignmentService.assignHelper(sessionId, clean)
            return AgentResult(humanHelpMessage(phone), "human_help")
        }

        val reformulations = ExpandStep.run(clean, deepSeekClient)
        val chunks = RagSearchStep.search(listOf(clean) + reformulations, chunkRepository)

        if (chunks.isEmpty()) {
            return AgentResult(
                "Tu pregunta no está relacionada con el propósito de esta aplicación.",
                "out_of_scope"
            )
        }

        val answer = AnswerStep.run(clean, chunks, deepSeekClient)
        return AgentResult(answer, "answer")
    }

    private fun humanHelpMessage(phone: String) =
        "Entiendo que esto es difícil y quiero que hables con una persona real que pueda acompañarte ahora mismo. Por favor comunícate con este número: $phone"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.agent.ChatAgentTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/assignment/ backend/src/main/kotlin/com/givemeurhand/backend/agent/ChatAgent.kt backend/src/test/kotlin/com/givemeurhand/backend/agent/ChatAgentTest.kt
git commit -m "feat(backend): add ChatAgent orchestrator and fallback assignment service"
```

---

### Task 10: `POST /chat` route with retry + error handling

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/routes/ChatRoutes.kt`
- Modify: `backend/src/main/kotlin/com/givemeurhand/backend/Application.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/routes/ChatRoutesTest.kt`

**Interfaces:**
- Consumes: `ChatAgent`, `AgentResult` (Task 9).
- Produces: `@Serializable data class ChatRequest(val sessionId: String, val message: String)`, `@Serializable data class ChatResponse(val reply: String, val kind: String)`, `fun Route.chatRoutes(agent: ChatAgent)`. `Application.module()` gains a `ChatAgent` parameter (default wired with fakes removed in Task 11's production wiring).

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/routes/ChatRoutesTest.kt
package com.givemeurhand.backend.routes

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.agent.FakeDeepSeekClient
import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.deepseek.DeepSeekClient
import com.givemeurhand.backend.module
import com.givemeurhand.backend.rag.FakeChunkRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoutesTest {
    @Test
    fun `POST chat returns the agent result as JSON`() = testApplication {
        val fake = FakeDeepSeekClient(mutableListOf(
            "cual es la capital de francia", "PREGUNTA_NORMAL", """["r1","r2","r3"]"""
        ))
        val agent = ChatAgent(fake, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))
        application { module(agent) }
        client.config { install(ContentNegotiation) { json() } }

        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"s1","message":"cual es la kapital de francia"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"kind\":\"out_of_scope\""))
    }

    @Test
    fun `DeepSeek failure on both attempts returns a generic error kind`() = testApplication {
        val throwing = object : DeepSeekClient {
            override suspend fun complete(systemPrompt: String, userPrompt: String, temperature: Double): String {
                throw RuntimeException("boom")
            }
        }
        val agent = ChatAgent(throwing, FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))
        application { module(agent) }
        client.config { install(ContentNegotiation) { json() } }

        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"s1","message":"hola"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"kind\":\"error\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.routes.ChatRoutesTest"`
Expected: FAIL — `chatRoutes`/`module(agent)` don't exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

Update `Application.kt` to accept an agent and mount the route:

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/Application.kt
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
```

Update `ApplicationTest.kt` (Task 1) to pass a fake agent, since `module()` now requires one:

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/ApplicationTest.kt
package com.givemeurhand.backend

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.agent.FakeDeepSeekClient
import com.givemeurhand.backend.assignment.FallbackOnlyAssignmentService
import com.givemeurhand.backend.rag.FakeChunkRepository
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `health check returns 200`() = testApplication {
        val agent = ChatAgent(FakeDeepSeekClient(), FakeChunkRepository(emptyMap()), FallbackOnlyAssignmentService("+57 3219699131"))
        application { module(agent) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test`
Expected: PASS (all tests so far)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/routes/ backend/src/main/kotlin/com/givemeurhand/backend/Application.kt backend/src/test/kotlin/com/givemeurhand/backend/routes/ backend/src/test/kotlin/com/givemeurhand/backend/ApplicationTest.kt
git commit -m "feat(backend): add POST /chat route with retry and error handling"
```

---

### Task 11: Real Mongo + DeepSeek wiring in `main()`

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/rag/MongoChunkRepository.kt`
- Modify: `backend/src/main/kotlin/com/givemeurhand/backend/Application.kt`

**Interfaces:**
- Consumes: `AppConfig` (Task 2), `HttpDeepSeekClient` (Task 3), `ChunkRepository` (Task 7), `FallbackOnlyAssignmentService`, `ChatAgent` (Task 9), `Application.module(agent: ChatAgent)` (Task 10).
- Produces: `class MongoChunkRepository(collection: MongoCollection<Document>) : ChunkRepository` using Atlas Search `$search`. Production `main()` that reads env vars and starts the server.

- [ ] **Step 1: Write the Mongo-backed repository**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/rag/MongoChunkRepository.kt
package com.givemeurhand.backend.rag

import com.mongodb.client.model.Aggregates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document

class MongoChunkRepository(
    private val collection: MongoCollection<Document>,
    private val searchIndexName: String = "default"
) : ChunkRepository {
    override suspend fun search(query: String, limit: Int): List<Chunk> {
        val pipeline = listOf(
            Document(
                "\$search",
                Document("index", searchIndexName)
                    .append("text", Document("query", query).append("path", "text"))
            ),
            Aggregates.limit(limit).toBsonDocument().let { Document(it) },
            Document("\$addFields", Document("score", Document("\$meta", "searchScore")))
        )
        return collection.aggregate(pipeline).map { doc -> doc.toChunk() }.toList()
    }

    private fun Document.toChunk() = Chunk(
        id = get("_id").toString(),
        text = getString("text") ?: "",
        sourceDocument = getString("sourceDocument") ?: "",
        page = getInteger("page", 0),
        chunkIndex = getInteger("chunkIndex", 0),
        score = getDouble("score") ?: 0.0
    )
}
```

- [ ] **Step 2: Wire it into `main()`**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/Application.kt
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
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bson.Document

fun main() {
    val config = AppConfig.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
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
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
        chatRoutes(agent)
    }
}
```

- [ ] **Step 3: Run the full test suite**

Run: `cd backend && ./gradlew test`
Expected: PASS (this task adds no new automated tests — `main()` wiring against real Mongo/DeepSeek is verified manually next)

- [ ] **Step 4: Manual smoke test (requires a real `.env` and an Atlas Search index already created on `knowledge_chunks.text`, named `default`)**

```bash
cd backend
export $(cat ../.env | xargs)   # or set the vars manually on Windows
./gradlew run
# in another terminal:
curl -X POST http://localhost:8080/chat -H "Content-Type: application/json" \
  -d '{"sessionId":"test-1","message":"komo manejo la anciedad despues del terremoto"}'
```

Expected: a JSON response with `kind` set to `"answer"` (if `knowledge_chunks` has been populated — see the ingestion plan) or `"out_of_scope"` if the collection is still empty. A request like `{"message":"quiero hablar con una persona"}` should return `kind: "human_help"` with the fallback phone number.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/rag/MongoChunkRepository.kt backend/src/main/kotlin/com/givemeurhand/backend/Application.kt
git commit -m "feat(backend): wire real MongoDB and DeepSeek clients into the server"
```
