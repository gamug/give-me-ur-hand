// backend/src/test/kotlin/com/givemeurhand/backend/agent/FakeAssignmentService.kt
package com.givemeurhand.backend.agent

import com.givemeurhand.backend.assignment.AssignResult
import com.givemeurhand.backend.assignment.AssignmentService

class FakeAssignmentService(
    private val assignmentId: String? = "assignment-1",
    private val phone: String = "+57 helper"
) : AssignmentService {
    data class AssignCall(val sessionId: String, val reason: String, val triggerSource: String)
    data class ConsentCall(val assignmentId: String, val granted: Boolean, val phone: String?, val evidenceText: String)

    val assignHelperCalls = mutableListOf<AssignCall>()
    val recordConsentCalls = mutableListOf<ConsentCall>()

    override suspend fun assignHelper(sessionId: String, reason: String, triggerSource: String): AssignResult {
        assignHelperCalls.add(AssignCall(sessionId, reason, triggerSource))
        return AssignResult(assignmentId = assignmentId, phone = phone)
    }

    override suspend fun recordConsent(assignmentId: String, granted: Boolean, phone: String?, evidenceText: String) {
        recordConsentCalls.add(ConsentCall(assignmentId, granted, phone, evidenceText))
    }
}
