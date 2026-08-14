# Professional Coordination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the backend core plan's `FallbackOnlyAssignmentService` stub with a real, load-balanced professional-assignment system, plus the auth/API surface the Android professional dashboard consumes (login, list assigned cases, close a case).

**Architecture:** `LoadBalancedAssignmentService` implements the `AssignmentService` interface already consumed by `ChatAgent` (backend core plan), so `ChatAgent` needs no changes — only `Application.kt`'s wiring swaps which implementation it gets. Auth is a hand-rolled bcrypt + JWT pair (no Ktor Authentication plugin, to keep configuration minimal for an MVP with one role).

**Tech Stack:** Kotlin, MongoDB Kotlin coroutine driver, jbcrypt, java-jwt (`com.auth0:java-jwt`) — both already declared as dependencies in `backend/build.gradle.kts` by the backend core plan.

**Spec:** `docs/superpowers/specs/2026-08-14-give-me-ur-hand-mvp-design.md`

## Global Constraints

- `professionals` collection: `{ name, phone, username, passwordHash, active }`. `assignments` collection: `{ professionalId, sessionId, reasonSnippet, status: "active"|"closed", assignedAt, closedAt }`.
- Load-balancing rule: among active professionals, assign to whoever has the fewest `assignments` with `status = "active"` and `assignedAt` within the last `ASSIGNMENT_MAX_AGE_HOURS` (env var, default 4). Ties go to whoever has no assignment history, then to the oldest `assignedAt`.
- If there are no active professionals, return `FALLBACK_HELP_PHONE` and create no assignment record.
- Professional accounts are pre-created via a seed script reading a local, gitignored `professionals-seed.json` — no self-registration.
- JWT is signed with `JWT_SECRET`, 12 hour expiration, subject = professional id.
- This plan assumes the backend core plan is implemented (`AppConfig`, `AssignmentService`, `ChatAgent`, `Application.module(agent: ChatAgent)`) and the content ingestion plan's `build.gradle.kts` changes are present.

---

### Task 1: Domain models, repositories, fakes, and the load-balancing algorithm

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/Professional.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/Assignment.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/LoadBalancedAssignmentService.kt`
- Create: `backend/src/test/kotlin/com/givemeurhand/backend/professional/FakeProfessionalRepository.kt`
- Create: `backend/src/test/kotlin/com/givemeurhand/backend/professional/FakeAssignmentRepository.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/professional/LoadBalancedAssignmentServiceTest.kt`

**Interfaces:**
- Consumes: `AssignmentService` (backend core plan, `com.givemeurhand.backend.assignment`).
- Produces: `data class Professional(id: String, name: String, phone: String, username: String, passwordHash: String, active: Boolean)`. `interface ProfessionalRepository { suspend fun findActive(): List<Professional>; suspend fun findByUsername(username: String): Professional? }`. `data class Assignment(id: String, professionalId: String, sessionId: String, reasonSnippet: String, status: String, assignedAt: Instant, closedAt: Instant?)`. `interface AssignmentRepository { suspend fun countActiveSince(professionalId: String, since: Instant): Int; suspend fun lastAssignedAt(professionalId: String): Instant?; suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String): Assignment; suspend fun findByProfessional(professionalId: String): List<Assignment>; suspend fun close(assignmentId: String, professionalId: String): Boolean }`. `class LoadBalancedAssignmentService(professionalRepository: ProfessionalRepository, assignmentRepository: AssignmentRepository, fallbackPhone: String, maxAgeHours: Long, clock: () -> Instant = { Instant.now() }) : AssignmentService`. Test fakes `FakeProfessionalRepository(professionals: List<Professional>)` and `FakeAssignmentRepository` (with `fun seed(professionalId: String, assignedAt: Instant, status: String = "active")` for test setup, plus the real interface methods) — reused by Task 4's route tests.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/professional/FakeProfessionalRepository.kt
package com.givemeurhand.backend.professional

class FakeProfessionalRepository(private val professionals: List<Professional>) : ProfessionalRepository {
    override suspend fun findActive(): List<Professional> = professionals.filter { it.active }
    override suspend fun findByUsername(username: String): Professional? =
        professionals.find { it.username == username }
}
```

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/professional/FakeAssignmentRepository.kt
package com.givemeurhand.backend.professional

import java.time.Instant

class FakeAssignmentRepository : AssignmentRepository {
    private val assignments = mutableListOf<Assignment>()
    val createdCalls = mutableListOf<Triple<String, String, String>>()

    fun seed(professionalId: String, assignedAt: Instant, status: String = "active") {
        assignments.add(
            Assignment(
                id = "seed-${assignments.size}",
                professionalId = professionalId,
                sessionId = "seed-session",
                reasonSnippet = "seed",
                status = status,
                assignedAt = assignedAt,
                closedAt = null
            )
        )
    }

    override suspend fun countActiveSince(professionalId: String, since: Instant): Int =
        assignments.count { it.professionalId == professionalId && it.status == "active" && it.assignedAt.isAfter(since) }

    override suspend fun lastAssignedAt(professionalId: String): Instant? =
        assignments.filter { it.professionalId == professionalId }.maxOfOrNull { it.assignedAt }

    override suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String): Assignment {
        createdCalls.add(Triple(professionalId, sessionId, reasonSnippet))
        val assignment = Assignment(
            id = "gen-${assignments.size}",
            professionalId = professionalId,
            sessionId = sessionId,
            reasonSnippet = reasonSnippet,
            status = "active",
            assignedAt = Instant.now(),
            closedAt = null
        )
        assignments.add(assignment)
        return assignment
    }

    override suspend fun findByProfessional(professionalId: String): List<Assignment> =
        assignments.filter { it.professionalId == professionalId }

    override suspend fun close(assignmentId: String, professionalId: String): Boolean {
        val index = assignments.indexOfFirst { it.id == assignmentId && it.professionalId == professionalId }
        if (index == -1) return false
        assignments[index] = assignments[index].copy(status = "closed", closedAt = Instant.now())
        return true
    }
}
```

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/professional/LoadBalancedAssignmentServiceTest.kt
package com.givemeurhand.backend.professional

import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LoadBalancedAssignmentServiceTest {
    private val now = Instant.parse("2026-08-14T12:00:00Z")

    private fun professional(id: String, phone: String, active: Boolean = true) =
        Professional(id = id, name = id, phone = phone, username = id, passwordHash = "hash", active = active)

    @Test
    fun `assigns to the professional with fewer active cases in the window`() = runTest {
        val profs = FakeProfessionalRepository(listOf(professional("p1", "+57 1"), professional("p2", "+57 2")))
        val assignments = FakeAssignmentRepository()
        repeat(3) { assignments.seed("p1", now.minus(Duration.ofMinutes(30))) }
        assignments.seed("p2", now.minus(Duration.ofMinutes(30)))

        val service = LoadBalancedAssignmentService(profs, assignments, "+57 fallback", maxAgeHours = 4) { now }
        val phone = service.assignHelper("session-1", "necesito ayuda")

        assertEquals("+57 2", phone)
        assertEquals(1, assignments.createdCalls.size)
        assertEquals(Triple("p2", "session-1", "necesito ayuda"), assignments.createdCalls.first())
    }

    @Test
    fun `assignments older than maxAgeHours don't count toward the load`() = runTest {
        val profs = FakeProfessionalRepository(listOf(professional("p1", "+57 1"), professional("p2", "+57 2")))
        val assignments = FakeAssignmentRepository()
        repeat(5) { assignments.seed("p1", now.minus(Duration.ofHours(10))) }

        val service = LoadBalancedAssignmentService(profs, assignments, "+57 fallback", maxAgeHours = 4) { now }
        val phone = service.assignHelper("session-1", "necesito ayuda")

        assertEquals("+57 2", phone)
    }

    @Test
    fun `ties are broken in favor of whoever was never assigned`() = runTest {
        val profs = FakeProfessionalRepository(listOf(professional("p1", "+57 1"), professional("p2", "+57 2")))
        val assignments = FakeAssignmentRepository()
        assignments.seed("p1", now.minus(Duration.ofHours(10)))

        val service = LoadBalancedAssignmentService(profs, assignments, "+57 fallback", maxAgeHours = 4) { now }
        val phone = service.assignHelper("session-1", "necesito ayuda")

        assertEquals("+57 2", phone)
    }

    @Test
    fun `returns the fallback phone and creates no assignment when there are no active professionals`() = runTest {
        val profs = FakeProfessionalRepository(emptyList())
        val assignments = FakeAssignmentRepository()

        val service = LoadBalancedAssignmentService(profs, assignments, "+57 fallback", maxAgeHours = 4) { now }
        val phone = service.assignHelper("session-1", "necesito ayuda")

        assertEquals("+57 fallback", phone)
        assertEquals(0, assignments.createdCalls.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.professional.LoadBalancedAssignmentServiceTest"`
Expected: FAIL — none of the professional/* types exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/Professional.kt
package com.givemeurhand.backend.professional

data class Professional(
    val id: String,
    val name: String,
    val phone: String,
    val username: String,
    val passwordHash: String,
    val active: Boolean
)

interface ProfessionalRepository {
    suspend fun findActive(): List<Professional>
    suspend fun findByUsername(username: String): Professional?
}
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/Assignment.kt
package com.givemeurhand.backend.professional

import java.time.Instant

data class Assignment(
    val id: String,
    val professionalId: String,
    val sessionId: String,
    val reasonSnippet: String,
    val status: String,
    val assignedAt: Instant,
    val closedAt: Instant?
)

interface AssignmentRepository {
    suspend fun countActiveSince(professionalId: String, since: Instant): Int
    suspend fun lastAssignedAt(professionalId: String): Instant?
    suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String): Assignment
    suspend fun findByProfessional(professionalId: String): List<Assignment>
    suspend fun close(assignmentId: String, professionalId: String): Boolean
}
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/LoadBalancedAssignmentService.kt
package com.givemeurhand.backend.professional

import com.givemeurhand.backend.assignment.AssignmentService
import java.time.Duration
import java.time.Instant

class LoadBalancedAssignmentService(
    private val professionalRepository: ProfessionalRepository,
    private val assignmentRepository: AssignmentRepository,
    private val fallbackPhone: String,
    private val maxAgeHours: Long,
    private val clock: () -> Instant = { Instant.now() }
) : AssignmentService {

    private data class Candidate(val professional: Professional, val load: Int, val lastAssignedAt: Instant?)

    override suspend fun assignHelper(sessionId: String, reason: String): String {
        val activeProfessionals = professionalRepository.findActive()
        if (activeProfessionals.isEmpty()) return fallbackPhone

        val since = clock().minus(Duration.ofHours(maxAgeHours))
        val candidates = activeProfessionals.map { professional ->
            Candidate(
                professional = professional,
                load = assignmentRepository.countActiveSince(professional.id, since),
                lastAssignedAt = assignmentRepository.lastAssignedAt(professional.id)
            )
        }

        val minLoad = candidates.minOf { it.load }
        val chosen = candidates
            .filter { it.load == minLoad }
            .sortedWith(compareBy(nullsFirst()) { it.lastAssignedAt })
            .first()
            .professional

        assignmentRepository.create(chosen.id, sessionId, reason)
        return chosen.phone
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.professional.LoadBalancedAssignmentServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/professional/Professional.kt backend/src/main/kotlin/com/givemeurhand/backend/professional/Assignment.kt backend/src/main/kotlin/com/givemeurhand/backend/professional/LoadBalancedAssignmentService.kt backend/src/test/kotlin/com/givemeurhand/backend/professional/
git commit -m "feat(backend): add professional/assignment models and load-balancing algorithm"
```

---

### Task 2: PasswordHasher

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/PasswordHasher.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/professional/PasswordHasherTest.kt`

**Interfaces:**
- Produces: `object PasswordHasher { fun hash(plain: String): String; fun verify(plain: String, hashed: String): Boolean }`.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/professional/PasswordHasherTest.kt
package com.givemeurhand.backend.professional

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    @Test
    fun `hashes and verifies a matching password`() {
        val hash = PasswordHasher.hash("clave-segura-123")
        assertTrue(PasswordHasher.verify("clave-segura-123", hash))
    }

    @Test
    fun `rejects a non-matching password`() {
        val hash = PasswordHasher.hash("clave-segura-123")
        assertFalse(PasswordHasher.verify("otra-clave", hash))
    }

    @Test
    fun `produces a different hash each time due to random salt`() {
        assertNotEquals(PasswordHasher.hash("misma-clave"), PasswordHasher.hash("misma-clave"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.professional.PasswordHasherTest"`
Expected: FAIL — `PasswordHasher` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/PasswordHasher.kt
package com.givemeurhand.backend.professional

import org.mindrot.jbcrypt.BCrypt

object PasswordHasher {
    fun hash(plain: String): String = BCrypt.hashpw(plain, BCrypt.gensalt())
    fun verify(plain: String, hashed: String): Boolean = BCrypt.checkpw(plain, hashed)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.professional.PasswordHasherTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/professional/PasswordHasher.kt backend/src/test/kotlin/com/givemeurhand/backend/professional/PasswordHasherTest.kt
git commit -m "feat(backend): add bcrypt password hasher"
```

---

### Task 3: JwtService

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/JwtService.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/professional/JwtServiceTest.kt`

**Interfaces:**
- Produces: `class JwtService(secret: String, expirationHours: Long = 12) { fun issue(professionalId: String): String; fun verify(token: String): String? }` — `verify` returns the professional id (JWT subject) or `null` if invalid/expired/wrong signature.

- [ ] **Step 1: Write the failing test**

```kotlin
// backend/src/test/kotlin/com/givemeurhand/backend/professional/JwtServiceTest.kt
package com.givemeurhand.backend.professional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtServiceTest {
    @Test
    fun `issues a token whose subject can be recovered`() {
        val service = JwtService("test-secret")
        val token = service.issue("professional-123")
        assertEquals("professional-123", service.verify(token))
    }

    @Test
    fun `rejects a token signed with a different secret`() {
        val issuer = JwtService("secret-a")
        val verifier = JwtService("secret-b")
        val token = issuer.issue("professional-123")
        assertNull(verifier.verify(token))
    }

    @Test
    fun `rejects a garbage token`() {
        val service = JwtService("test-secret")
        assertNull(service.verify("not-a-real-token"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.professional.JwtServiceTest"`
Expected: FAIL — `JwtService` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/JwtService.kt
package com.givemeurhand.backend.professional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Duration
import java.time.Instant
import java.util.Date

class JwtService(secret: String, private val expirationHours: Long = 12) {
    private val algorithm = Algorithm.HMAC256(secret)

    fun issue(professionalId: String): String {
        val now = Instant.now()
        return JWT.create()
            .withSubject(professionalId)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(Duration.ofHours(expirationHours))))
            .sign(algorithm)
    }

    fun verify(token: String): String? = try {
        JWT.require(algorithm).build().verify(token).subject
    } catch (e: JWTVerificationException) {
        null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.professional.JwtServiceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/professional/JwtService.kt backend/src/test/kotlin/com/givemeurhand/backend/professional/JwtServiceTest.kt
git commit -m "feat(backend): add JWT issue/verify service"
```

---

### Task 4: Professional routes (login, list cases, close case)

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/routes/ProfessionalRoutes.kt`
- Test: `backend/src/test/kotlin/com/givemeurhand/backend/routes/ProfessionalRoutesTest.kt`

**Interfaces:**
- Consumes: `ProfessionalRepository`, `AssignmentRepository`, `Professional`, `Assignment` (Task 1), `FakeProfessionalRepository`, `FakeAssignmentRepository` (Task 1), `PasswordHasher` (Task 2), `JwtService` (Task 3).
- Produces: `fun Route.professionalRoutes(professionalRepository: ProfessionalRepository, assignmentRepository: AssignmentRepository, jwtService: JwtService)` mounting `POST /professionals/login`, `GET /professionals/me/cases`, `POST /professionals/cases/{id}/close`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(FakeProfessionalRepository(emptyList()), assignments, jwtService) }
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
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(FakeProfessionalRepository(emptyList()), assignments, jwtService) }
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
        application {
            install(ContentNegotiation) { json() }
            routing { professionalRoutes(FakeProfessionalRepository(emptyList()), assignments, jwtService) }
        }
        val token = jwtService.issue("p1")

        val response = client.post("/professionals/cases/${created.id}/close") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.routes.ProfessionalRoutesTest"`
Expected: FAIL — `professionalRoutes` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./gradlew test --tests "com.givemeurhand.backend.routes.ProfessionalRoutesTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/routes/ProfessionalRoutes.kt backend/src/test/kotlin/com/givemeurhand/backend/routes/ProfessionalRoutesTest.kt
git commit -m "feat(backend): add professional login, cases list and close-case routes"
```

---

### Task 5: Mongo implementations, seed script, and production wiring

**Files:**
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoProfessionalRepository.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoAssignmentRepository.kt`
- Create: `backend/src/main/kotlin/com/givemeurhand/backend/professional/SeedProfessionals.kt`
- Create: `backend/professionals-seed.example.json`
- Modify: `backend/build.gradle.kts` (register `seedProfessionals` task)
- Modify: `backend/src/main/kotlin/com/givemeurhand/backend/Application.kt`

**Interfaces:**
- Consumes: `ProfessionalRepository`, `AssignmentRepository`, `Professional`, `Assignment` (Task 1), `PasswordHasher` (Task 2), `JwtService` (Task 3), `professionalRoutes` (Task 4), `AppConfig`, `ChatAgent`, `Application.module(agent: ChatAgent)` (backend core plan).
- Produces: `class MongoProfessionalRepository(collection: MongoCollection<Document>) : ProfessionalRepository`, `class MongoAssignmentRepository(collection: MongoCollection<Document>) : AssignmentRepository`. `data class ProfessionalRouteDeps(professionalRepository: ProfessionalRepository, assignmentRepository: AssignmentRepository, jwtService: JwtService)`. `Application.module(agent: ChatAgent, professionalDeps: ProfessionalRouteDeps? = null)` — extends the backend core plan's `module(agent)` with a defaulted parameter, so every existing caller/test keeps compiling unchanged.

This task has no new automated tests for the Mongo classes (they need a real Atlas cluster); Step 5 is a manual end-to-end verification.

- [ ] **Step 1: Write the Mongo repositories**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoProfessionalRepository.kt
package com.givemeurhand.backend.professional

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document

class MongoProfessionalRepository(
    private val collection: MongoCollection<Document>
) : ProfessionalRepository {
    override suspend fun findActive(): List<Professional> =
        collection.find(Filters.eq("active", true)).map { it.toProfessional() }.toList()

    override suspend fun findByUsername(username: String): Professional? =
        collection.find(Filters.eq("username", username)).firstOrNull()?.toProfessional()

    private fun Document.toProfessional() = Professional(
        id = get("_id").toString(),
        name = getString("name"),
        phone = getString("phone"),
        username = getString("username"),
        passwordHash = getString("passwordHash"),
        active = getBoolean("active", false)
    )
}
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoAssignmentRepository.kt
package com.givemeurhand.backend.professional

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date

class MongoAssignmentRepository(
    private val collection: MongoCollection<Document>
) : AssignmentRepository {
    override suspend fun countActiveSince(professionalId: String, since: Instant): Int {
        val filter = Filters.and(
            Filters.eq("professionalId", professionalId),
            Filters.eq("status", "active"),
            Filters.gt("assignedAt", Date.from(since))
        )
        return collection.countDocuments(filter).toInt()
    }

    override suspend fun lastAssignedAt(professionalId: String): Instant? =
        collection.find(Filters.eq("professionalId", professionalId))
            .sort(Sorts.descending("assignedAt"))
            .limit(1)
            .firstOrNull()
            ?.getDate("assignedAt")
            ?.toInstant()

    override suspend fun create(professionalId: String, sessionId: String, reasonSnippet: String): Assignment {
        val now = Date()
        val doc = Document()
            .append("professionalId", professionalId)
            .append("sessionId", sessionId)
            .append("reasonSnippet", reasonSnippet)
            .append("status", "active")
            .append("assignedAt", now)
            .append("closedAt", null)
        collection.insertOne(doc)
        return Assignment(
            id = doc.getObjectId("_id").toHexString(),
            professionalId = professionalId,
            sessionId = sessionId,
            reasonSnippet = reasonSnippet,
            status = "active",
            assignedAt = now.toInstant(),
            closedAt = null
        )
    }

    override suspend fun findByProfessional(professionalId: String): List<Assignment> =
        collection.find(Filters.eq("professionalId", professionalId)).map { it.toAssignment() }.toList()

    override suspend fun close(assignmentId: String, professionalId: String): Boolean {
        val filter = Filters.and(Filters.eq("_id", ObjectId(assignmentId)), Filters.eq("professionalId", professionalId))
        val update = Updates.combine(Updates.set("status", "closed"), Updates.set("closedAt", Date()))
        return collection.updateOne(filter, update).modifiedCount > 0
    }

    private fun Document.toAssignment() = Assignment(
        id = getObjectId("_id").toHexString(),
        professionalId = getString("professionalId"),
        sessionId = getString("sessionId"),
        reasonSnippet = getString("reasonSnippet"),
        status = getString("status"),
        assignedAt = getDate("assignedAt").toInstant(),
        closedAt = getDate("closedAt")?.toInstant()
    )
}
```

- [ ] **Step 2: Write the seed script**

```json
// backend/professionals-seed.example.json
[
  {
    "name": "Nombre Apellido",
    "phone": "+57 3000000000",
    "username": "usuario1",
    "password": "cambia-esta-clave"
  }
]
```

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/professional/SeedProfessionals.kt
package com.givemeurhand.backend.professional

import com.givemeurhand.backend.config.AppConfig
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.Document
import java.io.File

@Serializable
data class ProfessionalSeedEntry(val name: String, val phone: String, val username: String, val password: String)

fun main(args: Array<String>) {
    val seedFilePath = args.getOrNull(0) ?: "../professionals-seed.json"
    val seedFile = File(seedFilePath)
    if (!seedFile.exists()) {
        println("No se encontró el archivo de seed: $seedFilePath (copia professionals-seed.example.json y complétalo)")
        return
    }

    val entries = Json.decodeFromString<List<ProfessionalSeedEntry>>(seedFile.readText())
    val config = AppConfig.fromEnv()

    runBlocking {
        val mongoClient = MongoClient.create(config.mongoUri)
        try {
            val collection = mongoClient.getDatabase(config.mongoDatabase).getCollection<Document>("professionals")
            entries.forEach { entry ->
                val doc = Document()
                    .append("name", entry.name)
                    .append("phone", entry.phone)
                    .append("username", entry.username)
                    .append("passwordHash", PasswordHasher.hash(entry.password))
                    .append("active", true)
                collection.insertOne(doc)
                println("Creado profesional: ${entry.username}")
            }
        } finally {
            mongoClient.close()
        }
    }
}
```

- [ ] **Step 3: Register the Gradle task**

In `backend/build.gradle.kts`, alongside the existing `ingestDocuments` task, add:

```kotlin
tasks.register<JavaExec>("seedProfessionals") {
    group = "application"
    description = "Crea cuentas de profesionales a partir de professionals-seed.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.givemeurhand.backend.professional.SeedProfessionalsKt")
    if (project.hasProperty("seedFile")) {
        args = listOf(project.property("seedFile") as String)
    }
}
```

- [ ] **Step 4: Wire the real assignment service and professional routes into `Application.kt`**

```kotlin
// backend/src/main/kotlin/com/givemeurhand/backend/Application.kt
package com.givemeurhand.backend

import com.givemeurhand.backend.agent.ChatAgent
import com.givemeurhand.backend.assignment.AssignmentService
import com.givemeurhand.backend.config.AppConfig
import com.givemeurhand.backend.deepseek.HttpDeepSeekClient
import com.givemeurhand.backend.professional.*
import com.givemeurhand.backend.rag.MongoChunkRepository
import com.givemeurhand.backend.routes.chatRoutes
import com.givemeurhand.backend.routes.professionalRoutes
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

data class ProfessionalRouteDeps(
    val professionalRepository: ProfessionalRepository,
    val assignmentRepository: AssignmentRepository,
    val jwtService: JwtService
)

fun main() {
    val config = AppConfig.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val httpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }
    val deepSeekClient = HttpDeepSeekClient(httpClient, config.deepSeekBaseUrl, config.deepSeekApiKey, config.deepSeekModel)

    val mongoClient = MongoClient.create(config.mongoUri)
    val database = mongoClient.getDatabase(config.mongoDatabase)
    val chunkRepository = MongoChunkRepository(database.getCollection<Document>("knowledge_chunks"))
    val professionalRepository = MongoProfessionalRepository(database.getCollection<Document>("professionals"))
    val assignmentRepository = MongoAssignmentRepository(database.getCollection<Document>("assignments"))
    val jwtService = JwtService(config.jwtSecret)

    val assignmentService: AssignmentService = LoadBalancedAssignmentService(
        professionalRepository, assignmentRepository, config.fallbackHelpPhone, config.assignmentMaxAgeHours
    )
    val agent = ChatAgent(deepSeekClient, chunkRepository, assignmentService)
    val professionalDeps = ProfessionalRouteDeps(professionalRepository, assignmentRepository, jwtService)

    embeddedServer(Netty, port = port) { module(agent, professionalDeps) }.start(wait = true)
}

fun Application.module(agent: ChatAgent, professionalDeps: ProfessionalRouteDeps? = null) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) }
        chatRoutes(agent)
        professionalDeps?.let { professionalRoutes(it.professionalRepository, it.assignmentRepository, it.jwtService) }
    }
}
```

- [ ] **Step 5: Run the full test suite, then verify manually**

Run: `cd backend && ./gradlew test`
Expected: PASS (all tests from every plan so far)

Manual verification (requires the Atlas Search index from the ingestion plan and real `.env` values):

```bash
cd backend
cp professionals-seed.example.json professionals-seed.json
# edit professionals-seed.json with 2+ real name/phone/username/password entries
export $(cat ../.env | xargs)
./gradlew seedProfessionals

./gradlew run
# in another terminal:
curl -X POST http://localhost:8080/professionals/login -H "Content-Type: application/json" \
  -d '{"username":"usuario1","password":"la-clave-que-pusiste"}'
# copy the returned token, then:
curl http://localhost:8080/professionals/me/cases -H "Authorization: Bearer <token>"

curl -X POST http://localhost:8080/chat -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","message":"quiero hablar con una persona"}'
# repeat with a different sessionId a few times and confirm the returned phone
# rotates across your seeded professionals instead of always the fallback number
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoProfessionalRepository.kt backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoAssignmentRepository.kt backend/src/main/kotlin/com/givemeurhand/backend/professional/SeedProfessionals.kt backend/professionals-seed.example.json backend/build.gradle.kts backend/src/main/kotlin/com/givemeurhand/backend/Application.kt
git commit -m "feat(backend): wire real professional coordination (Mongo repos, seed script, routes)"
```
