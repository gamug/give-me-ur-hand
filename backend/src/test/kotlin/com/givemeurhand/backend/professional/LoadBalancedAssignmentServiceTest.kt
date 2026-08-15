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
        // p1 has only OLD (out-of-window) history, p2 has one RECENT (in-window) assignment.
        // A correct implementation excludes p1's old assignments, leaving p1 with load 0
        // and p2 with load 1, so p1 wins. A broken/no-op windowing implementation would
        // see p1 as more loaded (5 vs 1) and incorrectly pick p2 instead.
        val profs = FakeProfessionalRepository(listOf(professional("p1", "+57 1"), professional("p2", "+57 2")))
        val assignments = FakeAssignmentRepository()
        repeat(5) { assignments.seed("p1", now.minus(Duration.ofHours(10))) }
        assignments.seed("p2", now.minus(Duration.ofMinutes(30)))

        val service = LoadBalancedAssignmentService(profs, assignments, "+57 fallback", maxAgeHours = 4) { now }
        val phone = service.assignHelper("session-1", "necesito ayuda")

        assertEquals("+57 1", phone)
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
    fun `ties among professionals who both have history are broken in favor of the oldest lastAssignedAt`() = runTest {
        // Both p1 and p2 have exactly one active, in-window assignment, so their load is tied
        // at 1. Neither is null (both have history), so this exercises the "oldest assignedAt
        // wins" half of the tie-break rule rather than the "never assigned wins" half.
        val profs = FakeProfessionalRepository(listOf(professional("p1", "+57 1"), professional("p2", "+57 2")))
        val assignments = FakeAssignmentRepository()
        assignments.seed("p1", now.minus(Duration.ofHours(3)))
        assignments.seed("p2", now.minus(Duration.ofHours(1)))

        val service = LoadBalancedAssignmentService(profs, assignments, "+57 fallback", maxAgeHours = 4) { now }
        val phone = service.assignHelper("session-1", "necesito ayuda")

        assertEquals("+57 1", phone)
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

    @Test
    fun `resolves to the fallback phone and creates no assignment when a repository call throws`() = runTest {
        val throwingProfessionalRepository = object : ProfessionalRepository {
            override suspend fun findActive(): List<Professional> = throw RuntimeException("db unreachable")
            override suspend fun findByUsername(username: String): Professional? = null
            override suspend fun findById(id: String): Professional? = null
        }
        val assignments = FakeAssignmentRepository()

        val service = LoadBalancedAssignmentService(
            throwingProfessionalRepository,
            assignments,
            "+57 fallback",
            maxAgeHours = 4
        ) { now }
        val phone = service.assignHelper("session-1", "necesito ayuda")

        assertEquals("+57 fallback", phone)
        assertEquals(0, assignments.createdCalls.size)
    }
}
