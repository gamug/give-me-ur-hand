package com.givemeurhand.backend.config

data class AppConfig(
    val deepSeekApiKey: String,
    val deepSeekBaseUrl: String,
    val deepSeekModel: String,
    val mongoUri: String,
    val mongoDatabase: String,
    val jwtSecret: String,
    val fallbackHelpPhone: String,
    val assignmentMaxAgeHours: Long,
    val monitorIntervalMessages: Int,
    val consentMaxAttempts: Int,
    val incoherenceMaxAttempts: Int,
    val memorySummaryMaxChars: Int
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(key: String): String =
                env[key] ?: error("Falta variable de entorno requerida: $key")

            return AppConfig(
                deepSeekApiKey = required("DEEPSEEK_API_KEY"),
                deepSeekBaseUrl = env["DEEPSEEK_BASE_URL"] ?: "https://api.deepseek.com",
                deepSeekModel = env["DEEPSEEK_MODEL"] ?: "deepseek-chat",
                mongoUri = required("MONGODB_URI"),
                mongoDatabase = env["MONGODB_DATABASE"] ?: "give_me_ur_hand",
                jwtSecret = required("JWT_SECRET"),
                fallbackHelpPhone = env["FALLBACK_HELP_PHONE"] ?: "+57 3219699131",
                assignmentMaxAgeHours = (env["ASSIGNMENT_MAX_AGE_HOURS"] ?: "4").toLong(),
                monitorIntervalMessages = (env["MONITOR_INTERVAL_MESSAGES"] ?: "6").toInt(),
                consentMaxAttempts = (env["CONSENT_MAX_ATTEMPTS"] ?: "2").toInt(),
                incoherenceMaxAttempts = (env["INCOHERENCE_MAX_ATTEMPTS"] ?: "3").toInt(),
                memorySummaryMaxChars = (env["MEMORY_SUMMARY_MAX_CHARS"] ?: "2000").toInt()
            )
        }
    }
}
