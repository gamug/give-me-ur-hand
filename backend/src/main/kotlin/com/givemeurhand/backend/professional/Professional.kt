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
    suspend fun findById(id: String): Professional?
}
