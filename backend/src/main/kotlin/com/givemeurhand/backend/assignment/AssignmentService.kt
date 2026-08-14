// backend/src/main/kotlin/com/givemeurhand/backend/assignment/AssignmentService.kt
package com.givemeurhand.backend.assignment

interface AssignmentService {
    suspend fun assignHelper(sessionId: String, reason: String): String
}

class FallbackOnlyAssignmentService(private val fallbackPhone: String) : AssignmentService {
    override suspend fun assignHelper(sessionId: String, reason: String): String = fallbackPhone
}
