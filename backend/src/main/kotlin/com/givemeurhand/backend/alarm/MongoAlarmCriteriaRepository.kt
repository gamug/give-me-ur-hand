// backend/src/main/kotlin/com/givemeurhand/backend/alarm/MongoAlarmCriteriaRepository.kt
package com.givemeurhand.backend.alarm

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document

class MongoAlarmCriteriaRepository(
    private val collection: MongoCollection<Document>
) : AlarmCriteriaRepository {
    override suspend fun getCurrent(): AlarmCriteria? {
        val doc = collection.find(Filters.eq("_id", "current")).firstOrNull() ?: return null
        return doc.toAlarmCriteria()
    }

    private fun Document.toAlarmCriteria() = AlarmCriteria(
        version = getIntFlexible("version", 1),
        generatedAt = getDate("generatedAt").toInstant(),
        classificationPromptText = getString("classificationPromptText") ?: "",
        controlStrategiesText = getString("controlStrategiesText") ?: ""
    )

    private fun Document.getIntFlexible(key: String, default: Int): Int =
        (get(key) as? Number)?.toInt() ?: default
}
