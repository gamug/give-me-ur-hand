// backend/src/main/kotlin/com/givemeurhand/backend/professional/SeedProfessionals.kt
package com.givemeurhand.backend.professional

import com.givemeurhand.backend.config.AppConfig
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.Document
import java.io.File
import kotlin.system.exitProcess

@Serializable
data class ProfessionalSeedEntry(val name: String, val phone: String, val username: String, val password: String)

fun main(args: Array<String>) {
    val seedFilePath = args.getOrNull(0) ?: "professionals-seed.json"
    val seedFile = File(seedFilePath)
    if (!seedFile.exists()) {
        println("No se encontró el archivo de seed: $seedFilePath (copia professionals-seed.example.json y complétalo)")
        exitProcess(1)
    }

    val entries = Json.decodeFromString<List<ProfessionalSeedEntry>>(seedFile.readText())
    val config = AppConfig.fromEnv()

    runBlocking {
        val mongoClient = MongoClient.create(config.mongoUri)
        try {
            val collection = mongoClient.getDatabase(config.mongoDatabase).getCollection<Document>(PROFESSIONALS_COLLECTION)
            collection.createIndex(Indexes.ascending("username"), IndexOptions().unique(true))
            entries.forEach { entry ->
                val doc = Document()
                    .append("name", entry.name)
                    .append("phone", entry.phone)
                    .append("username", entry.username)
                    .append("passwordHash", PasswordHasher.hash(entry.password))
                    .append("active", true)
                collection.replaceOne(Filters.eq("username", entry.username), doc, ReplaceOptions().upsert(true))
                println("Profesional creado/actualizado: ${entry.username}")
            }
        } finally {
            mongoClient.close()
        }
    }
}
