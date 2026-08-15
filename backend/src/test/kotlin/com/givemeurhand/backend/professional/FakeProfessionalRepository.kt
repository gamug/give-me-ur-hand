package com.givemeurhand.backend.professional

class FakeProfessionalRepository(private val professionals: List<Professional>) : ProfessionalRepository {
    override suspend fun findActive(): List<Professional> = professionals.filter { it.active }
    override suspend fun findByUsername(username: String): Professional? =
        professionals.find { it.username == username }
}
