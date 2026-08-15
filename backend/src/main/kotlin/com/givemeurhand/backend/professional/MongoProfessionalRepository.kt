// backend/src/main/kotlin/com/givemeurhand/backend/professional/MongoProfessionalRepository.kt
package com.givemeurhand.backend.professional

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.Document

const val PROFESSIONALS_COLLECTION = "professionals"

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
