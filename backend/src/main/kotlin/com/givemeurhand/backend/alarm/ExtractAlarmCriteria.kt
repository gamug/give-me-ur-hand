// backend/src/main/kotlin/com/givemeurhand/backend/alarm/ExtractAlarmCriteria.kt
package com.givemeurhand.backend.alarm

import com.givemeurhand.backend.config.AppConfig
import com.givemeurhand.backend.deepseek.HttpDeepSeekClient
import com.givemeurhand.backend.rag.KNOWLEDGE_CHUNKS_COLLECTION
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bson.Document
import java.time.Instant
import java.util.Date

private val PEIVD_TEXT = """
    Indicadores del triage psicológico (Protocolo PEIVD, Lorente 2003 / Colegio
    Oficial de Psicólogos - España):

    Evalúa el discurso de la persona según estos indicadores:
    - Coherencia/incoherencia del discurso
    - Confusión
    - Estado emocional dominante
    - Autoculpa
    - Lagunas de memoria (amnesias lacunares)
    - Ideación autolítica (autolesión o suicidio) — el de MAYOR peso: por sí
      solo basta para clasificar ROJO
    - Identificación con las víctimas
    - Negación
    - Grado de exposición al desastre (contacto con muertes)
    - Incidentes previos / traumatización acumulada
    - Planes de futuro
    - Pérdidas materiales

    Moduladores a considerar junto con lo anterior: soporte social disponible,
    persistencia de percepción de amenaza, atribución de control interno sobre
    el incidente y el futuro.

    Clasifica el caso en:
    - ROJO: actuación inmediata (p. ej. ideación autolítica, riesgo de daño a sí
      mismo o a otros, peligro de vida inmediato).
    - AMARILLO: atención diferible con vigilancia (afectación significativa pero
      sin peligro inmediato).
    - VERDE: afectación leve.
""".trimIndent()

private data class ChunkExcerpt(val text: String, val sourceDocument: String, val page: Int)

// One regex query per category; patterns are validated to return real matches against the live cluster.
private val CATEGORY_PATTERNS = linkedMapOf(
    "danger/suicide" to "suicid|self-harm|self harm|harm to (him|her|them)self|danger sign|immediate risk|life-threatening",
    "confusion/coherence/memory" to "confusion|disorientation|memory loss|amnesia|dissociat",
    "denial" to "denial|avoidance",
    "exposure/trauma" to "exposure to death|witness(ed)? (death|dying)|prior trauma|previous trauma",
    "future/loss" to "hopeless|no future|loss of (home|property|belongings)",
    "coping/strategies" to "cop(e|ing)|self-care|grounding technique|breathing exercise|relaxation|control strateg"
)

private val CLASSIFICATION_CATEGORIES = listOf(
    "danger/suicide", "confusion/coherence/memory", "denial", "exposure/trauma", "future/loss"
)

fun main() {
    val config = AppConfig.fromEnv()

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }
    val deepSeekClient = HttpDeepSeekClient(httpClient, config.deepSeekBaseUrl, config.deepSeekApiKey, config.deepSeekModel)
    val mongoClient = MongoClient.create(config.mongoUri)

    runBlocking {
        try {
            val database = mongoClient.getDatabase(config.mongoDatabase)
            val chunkCollection = database.getCollection<Document>(KNOWLEDGE_CHUNKS_COLLECTION)

            println("Buscando extractos de apoyo en $KNOWLEDGE_CHUNKS_COLLECTION...")
            val excerptsByCategory = CATEGORY_PATTERNS.mapValues { (category, pattern) ->
                val matches = chunkCollection.find(Filters.regex("text", pattern, "i")).limit(5).toList()
                println("  -> $category: ${matches.size} chunks encontrados")
                matches.map { doc ->
                    ChunkExcerpt(
                        text = doc.getString("text") ?: "",
                        sourceDocument = doc.getString("sourceDocument") ?: "",
                        page = (doc.get("page") as? Number)?.toInt() ?: 0
                    )
                }
            }

            val classificationExcerptsText = CLASSIFICATION_CATEGORIES.joinToString("\n\n") { category ->
                formatExcerpts(category, excerptsByCategory[category].orEmpty())
            }

            val systemPrompt = "Eres un experto clínico en primeros auxilios psicológicos y protocolos de triage de emergencia."

            val classificationUserPrompt = """
                A partir de la siguiente checklist de indicadores y estos extractos de apoyo de una guía
                clínica, redacta un único fragmento conciso en español, apto como parte de un system prompt,
                que un clasificador pueda usar para calificar un caso como ROJO, AMARILLO o VERDE.

                $PEIVD_TEXT

                Extractos de apoyo (con citas de fuente):
                $classificationExcerptsText
            """.trimIndent()

            println("Generando classificationPromptText con DeepSeek...")
            val classificationPromptText = deepSeekClient.complete(systemPrompt, classificationUserPrompt)

            val copingExcerptsText = formatExcerpts("coping/strategies", excerptsByCategory["coping/strategies"].orEmpty())

            val controlStrategiesUserPrompt = """
                A partir de estos extractos de una guía clínica sobre afrontamiento y autocuidado, redacta una
                lista breve, concreta y accionable en español de estrategias de autoayuda y control emocional
                para una persona en crisis.

                Extractos de apoyo (con citas de fuente):
                $copingExcerptsText
            """.trimIndent()

            println("Generando controlStrategiesText con DeepSeek...")
            val controlStrategiesText = deepSeekClient.complete(systemPrompt, controlStrategiesUserPrompt)

            val criteriaCollection = database.getCollection<Document>(ALARM_CRITERIA_COLLECTION)
            val doc = Document()
                .append("_id", "current")
                .append("version", 1)
                .append("generatedAt", Date.from(Instant.now()))
                .append("classificationPromptText", classificationPromptText)
                .append("controlStrategiesText", controlStrategiesText)
            criteriaCollection.replaceOne(Filters.eq("_id", "current"), doc, ReplaceOptions().upsert(true))

            println("Listo. Documento 'current' escrito en $ALARM_CRITERIA_COLLECTION.")
        } finally {
            mongoClient.close()
            httpClient.close()
        }
    }
}

private fun formatExcerpts(category: String, excerpts: List<ChunkExcerpt>): String {
    if (excerpts.isEmpty()) return "[$category] (sin extractos encontrados)"
    return excerpts.joinToString("\n") { excerpt ->
        "[$category | fuente: ${excerpt.sourceDocument}, página ${excerpt.page}] ${excerpt.text}"
    }
}
