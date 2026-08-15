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
